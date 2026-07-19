package main

import (
	"context"
	"fmt"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	reLogicalSlot = regexp.MustCompile(`(?m)^\s*Logical SIM slot\s+(\d+):\s*subId=(\d+)`)
	reSimInfoRow = regexp.MustCompile(`(?m)^Row:\s*\d+\s+(.*)$`)
)

// SubSlotMap maps Android subscription id (SMS sub_id) → physical/logical SIM slot.
type SubSlotMap struct {
	mu      sync.Mutex
	m       map[int]int
	loaded  time.Time
	Query   func(ctx context.Context) (string, error) // override for tests
	maxAge  time.Duration
}

func (s *SubSlotMap) Slot(ctx context.Context, subID int) int {
	s.refresh(ctx)
	s.mu.Lock()
	defer s.mu.Unlock()
	if slot, ok := s.m[subID]; ok {
		return slot
	}
	// Fallback only when map empty / unknown: do NOT assume sub_id-1
	// (on this device slot0=subId2, slot1=subId1).
	if len(s.m) == 0 && subID >= 0 && subID < 10 {
		return subID
	}
	return 0
}

func (s *SubSlotMap) refresh(ctx context.Context) {
	age := s.maxAge
	if age <= 0 {
		age = 5 * time.Minute
	}
	s.mu.Lock()
	fresh := s.m != nil && time.Since(s.loaded) < age
	s.mu.Unlock()
	if fresh {
		return
	}
	raw, err := s.dump(ctx)
	if err != nil {
		return
	}
	m := parseSubSlotMap(raw)
	if len(m) == 0 {
		return
	}
	s.mu.Lock()
	s.m = m
	s.loaded = time.Now()
	s.mu.Unlock()
}

func (s *SubSlotMap) dump(ctx context.Context) (string, error) {
	if s.Query != nil {
		return s.Query(ctx)
	}
	cctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	out, err := exec.CommandContext(cctx, "dumpsys", "isub").CombinedOutput()
	if err == nil && len(out) > 0 {
		return string(out), nil
	}
	out2, err2 := exec.CommandContext(cctx, "content", "query", "--uri", "content://telephony/siminfo").CombinedOutput()
	if err2 != nil {
		if err != nil {
			return "", fmt.Errorf("isub: %v; siminfo: %v", err, err2)
		}
		return "", err2
	}
	return string(out2), nil
}

// parseSubSlotMap accepts dumpsys isub or content://telephony/siminfo output.
func parseSubSlotMap(raw string) map[int]int {
	out := map[int]int{}
	for _, m := range reLogicalSlot.FindAllStringSubmatch(raw, -1) {
		if len(m) < 3 {
			continue
		}
		slot, _ := strconv.Atoi(m[1])
		sub, _ := strconv.Atoi(m[2])
		out[sub] = slot
	}
	if len(out) > 0 {
		return out
	}
	// siminfo rows: _id=<subId>, sim_id=<slot>
	for _, m := range reSimInfoRow.FindAllStringSubmatch(raw, -1) {
		if len(m) < 2 {
			continue
		}
		kv := parseContentRow(m[1])
		sub, _ := strconv.Atoi(firstKV(kv, "_id", "id"))
		slotStr := firstKV(kv, "sim_id", "slot_index", "simSlotIndex")
		if sub == 0 || slotStr == "" {
			continue
		}
		slot, err := strconv.Atoi(slotStr)
		if err != nil {
			continue
		}
		out[sub] = slot
	}
	return out
}

func firstKV(kv map[string]string, keys ...string) string {
	for _, k := range keys {
		if v, ok := kv[k]; ok && strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

// SubIDToSlot is kept for tests/compat; prefer SubSlotMap on device.
// Deprecated naive mapping — wrong when subscription ids are not slot+1.
func SubIDToSlot(subID int) int {
	if subID <= 0 {
		return 0
	}
	if subID == 1 || subID == 2 {
		return subID - 1
	}
	if subID < 10 {
		return subID
	}
	return 0
}
