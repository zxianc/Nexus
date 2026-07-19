package main

import (
	"context"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Telephony CALL_STATE_*: 0 idle, 1 ringing, 2 offhook.
var (
	rePhoneID    = regexp.MustCompile(`(?m)^  Phone Id=(\d+)\s*$`)
	reCallState  = regexp.MustCompile(`(?m)^\s*mCallState=(\d+)`)
	reIncomingNr = regexp.MustCompile(`(?m)^\s*mCallIncomingNumber=(.*)$`)
)

// callIdentity remembers peer/slot seen while a call is active (for archive header).
type callIdentity struct {
	mu      sync.Mutex
	Peer    string
	Slot    int
	HasSlot bool
}

func (c *callIdentity) remember(peer string, slot int, hasSlot bool) {
	if c == nil {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if p := strings.TrimSpace(peer); p != "" {
		c.Peer = p
	}
	if hasSlot {
		c.Slot = slot
		c.HasSlot = true
	}
}

func (c *callIdentity) snapshot() (peer string, slot int, hasSlot bool) {
	if c == nil {
		return "", 0, false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.Peer, c.Slot, c.HasSlot
}

func (c *callIdentity) clear() {
	if c == nil {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.Peer = ""
	c.Slot = 0
	c.HasSlot = false
}

func parseRegistryCallStates(dump string) map[int]int {
	out := map[int]int{}
	idxs := rePhoneID.FindAllStringSubmatchIndex(dump, -1)
	if len(idxs) == 0 {
		if m := reCallState.FindStringSubmatch(dump); len(m) == 2 {
			st, _ := strconv.Atoi(m[1])
			out[0] = st
		}
		return out
	}
	for i, loc := range idxs {
		slot, _ := strconv.Atoi(dump[loc[2]:loc[3]])
		start := loc[1]
		end := len(dump)
		if i+1 < len(idxs) {
			end = idxs[i+1][0]
		}
		block := dump[start:end]
		if sm := reCallState.FindStringSubmatch(block); len(sm) == 2 {
			st, _ := strconv.Atoi(sm[1])
			out[slot] = st
		}
	}
	return out
}

func anyCallActive(states map[int]int) bool {
	for _, st := range states {
		if st == 1 || st == 2 {
			return true
		}
	}
	return false
}

// parseActiveCallMeta returns peer + slot for a RINGING/OFFHOOK line (prefers non-empty peer).
func parseActiveCallMeta(dump string) (peer string, slot int, ok bool) {
	idxs := rePhoneID.FindAllStringSubmatchIndex(dump, -1)
	type hit struct {
		slot int
		peer string
	}
	var hits []hit
	if len(idxs) == 0 {
		st := 0
		if m := reCallState.FindStringSubmatch(dump); len(m) == 2 {
			st, _ = strconv.Atoi(m[1])
		}
		if st == 1 || st == 2 {
			p := ""
			if im := reIncomingNr.FindStringSubmatch(dump); len(im) == 2 {
				p = strings.TrimSpace(im[1])
			}
			hits = append(hits, hit{slot: 0, peer: p})
		}
	} else {
		for i, loc := range idxs {
			sl, _ := strconv.Atoi(dump[loc[2]:loc[3]])
			start := loc[1]
			end := len(dump)
			if i+1 < len(idxs) {
				end = idxs[i+1][0]
			}
			block := dump[start:end]
			sm := reCallState.FindStringSubmatch(block)
			if len(sm) != 2 {
				continue
			}
			st, _ := strconv.Atoi(sm[1])
			if st != 1 && st != 2 {
				continue
			}
			p := ""
			if im := reIncomingNr.FindStringSubmatch(block); len(im) == 2 {
				p = strings.TrimSpace(im[1])
			}
			hits = append(hits, hit{slot: sl, peer: p})
		}
	}
	if len(hits) == 0 {
		return "", 0, false
	}
	for _, h := range hits {
		if h.peer != "" {
			return h.peer, h.slot, true
		}
	}
	return hits[0].peer, hits[0].slot, true
}

func dumpTelephonyRegistry(ctx context.Context) (string, error) {
	cctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	out, err := exec.CommandContext(cctx, "dumpsys", "telephony.registry").CombinedOutput()
	return string(out), err
}

// watchCallEndFinalizer polls telephony.registry and finalizes the LLM archive when
// the call leaves RINGING/OFFHOOK (debounced). This covers hangups where HAL keeps
// pcm.sock open so runStream never returns.
func watchCallEndFinalizer(ctx context.Context, llmCfg llmOut, idleDebounce time.Duration) {
	if !llmCfg.Enabled || llmCfg.Session == nil {
		return
	}
	if idleDebounce <= 0 {
		idleDebounce = 1500 * time.Millisecond
	}
	t := time.NewTicker(800 * time.Millisecond)
	defer t.Stop()

	seenActive := false
	var idleSince time.Time

	for {
		select {
		case <-ctx.Done():
			finalizeCallArchive(context.Background(), llmCfg, "shutdown")
			return
		case <-t.C:
			dump, err := dumpTelephonyRegistry(ctx)
			if err != nil {
				continue
			}
			active := anyCallActive(parseRegistryCallStates(dump))
			if active {
				if peer, slot, ok := parseActiveCallMeta(dump); ok {
					llmCfg.Identity.remember(peer, slot, true)
				}
				seenActive = true
				idleSince = time.Time{}
				continue
			}
			if !seenActive {
				continue
			}
			if idleSince.IsZero() {
				idleSince = time.Now()
				continue
			}
			if time.Since(idleSince) < idleDebounce {
				continue
			}
			finalizeCallArchive(ctx, llmCfg, "telephony-idle")
			seenActive = false
			idleSince = time.Time{}
		}
	}
}
