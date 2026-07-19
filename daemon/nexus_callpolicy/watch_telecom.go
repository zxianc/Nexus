package main

import (
	"context"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// TelecomWatcher polls dumpsys for live RINGING (Lineage / AOSP shaped).
// Primary: telephony.registry (per-phone mCallState). Fallback: telecom live call lines.
type TelecomWatcher struct {
	Interval time.Duration
}

var (
	rePhoneID     = regexp.MustCompile(`(?m)^  Phone Id=(\d+)\s*$`)
	reCallState   = regexp.MustCompile(`(?m)^\s*mCallState=(\d+)`)
	reIncoming    = regexp.MustCompile(`(?m)^\s*mCallIncomingNumber=(.*)$`)
	reRingingLive = regexp.MustCompile(`(?i)Call TC@\d+[^\n]*\bRINGING\b`)
	reSlot        = regexp.MustCompile(`(?i)(?:slotId|slot_id|phoneId|phone_id|mSlotId)[=:\s]+(\d)`)
	reTel         = regexp.MustCompile(`tel:([+\d*#]+)`)
	reAccountSlot = regexp.MustCompile(`TelephonyConnectionService\},\s*(\d+)`)
)

func (w *TelecomWatcher) Poll(ctx context.Context) ([]Incoming, error) {
	out, err := exec.CommandContext(ctx, "dumpsys", "telephony.registry").CombinedOutput()
	if err == nil {
		return parseRegistry(string(out)), nil
	}
	out2, err2 := exec.CommandContext(ctx, "dumpsys", "telecom").CombinedOutput()
	if err2 != nil {
		return nil, err
	}
	return parseTelecomLive(string(out2)), nil
}

func parseRegistryStates(s string) map[int]int {
	out := map[int]int{}
	idxs := rePhoneID.FindAllStringSubmatchIndex(s, -1)
	if len(idxs) == 0 {
		if m := reCallState.FindStringSubmatch(s); len(m) == 2 {
			st, _ := strconv.Atoi(m[1])
			out[0] = st
		}
		return out
	}
	for i, loc := range idxs {
		slot, _ := strconv.Atoi(s[loc[2]:loc[3]])
		start := loc[1]
		end := len(s)
		if i+1 < len(idxs) {
			end = idxs[i+1][0]
		}
		block := s[start:end]
		if sm := reCallState.FindStringSubmatch(block); len(sm) == 2 {
			st, _ := strconv.Atoi(sm[1])
			out[slot] = st
		}
	}
	return out
}

func parseRegistry(s string) []Incoming {
	idxs := rePhoneID.FindAllStringSubmatchIndex(s, -1)
	if len(idxs) == 0 {
		if m := reCallState.FindStringSubmatch(s); len(m) == 2 && m[1] == "1" {
			peer := ""
			if im := reIncoming.FindStringSubmatch(s); len(im) == 2 {
				peer = strings.TrimSpace(im[1])
			}
			return []Incoming{{
				Key:     peer + "|reg|0",
				Slot:    0,
				Peer:    peer,
				RawHint: "registry(mCallState=1)",
			}}
		}
		return nil
	}
	var out []Incoming
	for i, loc := range idxs {
		slot, _ := strconv.Atoi(s[loc[2]:loc[3]])
		start := loc[1]
		end := len(s)
		if i+1 < len(idxs) {
			end = idxs[i+1][0]
		}
		block := s[start:end]
		sm := reCallState.FindStringSubmatch(block)
		if len(sm) != 2 || sm[1] != "1" {
			continue
		}
		peer := ""
		if im := reIncoming.FindStringSubmatch(block); len(im) == 2 {
			peer = strings.TrimSpace(im[1])
		}
		out = append(out, Incoming{
			Key:     peer + "|reg|" + strconv.Itoa(slot),
			Slot:    slot,
			Peer:    peer,
			RawHint: "registry phoneId=" + strconv.Itoa(slot),
		})
	}
	return out
}

// parseTelecomLive only accepts a Call line that currently includes RINGING
// (not historical "Enter RINGING" / "Ringing calls:" section headers).
func parseTelecomLive(s string) []Incoming {
	if !reRingingLive.MatchString(s) {
		return nil
	}
	blocks := splitCallBlocks(s)
	var out []Incoming
	for _, b := range blocks {
		head := firstLine(b)
		if !strings.Contains(strings.ToUpper(head), "RINGING") {
			continue
		}
		if strings.Contains(b, "endTime:") || strings.Contains(b, "callTerminationReason:") {
			continue
		}
		slot := 0
		if m := reSlot.FindStringSubmatch(b); len(m) == 2 {
			slot, _ = strconv.Atoi(m[1])
		} else if m := reAccountSlot.FindStringSubmatch(b); len(m) == 2 {
			// On this device PhoneAccount id 2 → SORT_ORDER 0, id 1 → SORT_ORDER 1
			id, _ := strconv.Atoi(m[1])
			if id == 1 {
				slot = 1
			} else {
				slot = 0
			}
		}
		peer := ""
		if m := reTel.FindStringSubmatch(b); len(m) == 2 {
			peer = m[1]
		}
		out = append(out, Incoming{
			Key:     peer + "|tc|s" + strconv.Itoa(slot),
			Slot:    slot,
			Peer:    peer,
			RawHint: truncate(head, 80),
		})
	}
	return out
}

func firstLine(s string) string {
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		return strings.TrimSpace(s[:i])
	}
	return strings.TrimSpace(s)
}

func splitCallBlocks(s string) []string {
	parts := regexp.MustCompile(`(?m)^(?:Call TC@|Call: )`).Split(s, -1)
	if len(parts) <= 1 {
		return []string{s}
	}
	return parts[1:]
}

func truncate(s string, n int) string {
	s = strings.ReplaceAll(s, "\n", " ")
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
