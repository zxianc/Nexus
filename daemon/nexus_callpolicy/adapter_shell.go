package main

import (
	"context"
	"fmt"
	"log"
	"os/exec"
	"strings"
	"time"
)

// ShellAdapter answers/rejects via ITelecomService + keyevent, verified against registry.
// Note: bare `service call phone N` often returns Parcel exit 0 without doing anything —
// never treat shell success alone as call control success.
//
// On this Lineage build, acceptRingingCall silently no-ops when ANSWER_PHONE_CALLS
// appops is ignore for the calling package; endCall then falls through to KEYCODE_ENDCALL.
// Answer needs HEADSETHOOK (and granted appops) — KEYCODE_CALL alone often does nothing.
type ShellAdapter struct{}

func (a *ShellAdapter) Answer(ctx context.Context, slot int) error {
	ensureAnswerAppOps()
	return a.act(ctx, slot, "answer", [][]string{
		// Headset hook is the most reliable "answer" gesture across dialers.
		{"input", "keyevent", "KEYCODE_HEADSETHOOK"},
		{"input", "keyevent", "79"},
		// Default dialer package — appops checked against this name.
		{"service", "call", "telecom", "36", "s16", "com.android.dialer"},
		{"service", "call", "telecom", "36", "s16", "com.android.shell"},
		{"service", "call", "telecom", "36", "s16", "android"},
		{"service", "call", "telecom", "37", "s16", "com.android.shell", "i32", "0"},
		{"input", "keyevent", "KEYCODE_CALL"},
		{"input", "keyevent", "5"},
	}, func(st int) bool { return st == 2 /* OFFHOOK */ })
}

func (a *ShellAdapter) Reject(ctx context.Context, slot int) error {
	ensureAnswerAppOps()
	return a.act(ctx, slot, "reject", [][]string{
		{"service", "call", "telecom", "35", "s16", "com.android.shell"}, // endCall
		{"service", "call", "telecom", "35", "s16", "com.android.dialer"},
		{"input", "keyevent", "KEYCODE_ENDCALL"},
		{"input", "keyevent", "6"},
		// Headset hook while ringing can also reject on some builds if already answered path fails.
		{"input", "keyevent", "KEYCODE_HEADSETHOOK"},
	}, func(st int) bool { return st != 1 /* not RINGING */ })
}

func (a *ShellAdapter) act(ctx context.Context, slot int, name string, cmds [][]string, ok func(int) bool) error {
	var last error
	for _, args := range cmds {
		before := slotCallState(ctx, slot)
		if err := runShell(ctx, args); err != nil {
			log.Printf("%s try %v: run err=%v (state=%d)", name, args, err, before)
			last = err
		} else {
			log.Printf("%s try %v: ran (state before=%d)", name, args, before)
		}
		// Answer may need a bit longer for IMS to go OFFHOOK.
		budget := 1500 * time.Millisecond
		if name == "answer" {
			budget = 2500 * time.Millisecond
		}
		st, err := waitSlotState(ctx, slot, budget, ok)
		if err == nil {
			log.Printf("%s ok via %v (state=%d)", name, args, st)
			return nil
		}
		last = fmt.Errorf("%v → state=%d: %v", args, st, err)
	}
	if last == nil {
		return fmt.Errorf("%s: no command", name)
	}
	return fmt.Errorf("%s failed: %v", name, last)
}

func runShell(ctx context.Context, args []string) error {
	cctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	cmd := exec.CommandContext(cctx, args[0], args[1:]...)
	out, err := cmd.CombinedOutput()
	text := string(out)
	if err != nil {
		return fmt.Errorf("%v: %v (%s)", args, err, truncate(text, 80))
	}
	low := strings.ToLower(text)
	if strings.Contains(low, "securityexception") || strings.Contains(low, "exception") {
		return fmt.Errorf("%v: %s", args, truncate(text, 80))
	}
	return nil
}

func waitSlotState(ctx context.Context, slot int, budget time.Duration, ok func(int) bool) (int, error) {
	deadline := time.Now().Add(budget)
	last := -1
	for {
		st := slotCallState(ctx, slot)
		last = st
		if ok(st) {
			return st, nil
		}
		if time.Now().After(deadline) {
			return last, fmt.Errorf("timeout waiting state change")
		}
		select {
		case <-ctx.Done():
			return last, ctx.Err()
		case <-time.After(200 * time.Millisecond):
		}
	}
}

// slotCallState returns 0 idle / 1 ringing / 2 offhook / -1 unknown.
func slotCallState(ctx context.Context, slot int) int {
	cctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	out, err := exec.CommandContext(cctx, "dumpsys", "telephony.registry").CombinedOutput()
	if err != nil {
		return -1
	}
	states := parseRegistryStates(string(out))
	if st, ok := states[slot]; ok {
		return st
	}
	return 0
}

func ensureAnswerAppOps() {
	// Silent no-op inside TelecomServiceImpl when appops is ignore.
	_ = exec.Command("appops", "set", "com.android.shell", "ANSWER_PHONE_CALLS", "allow").Run()
	_ = exec.Command("appops", "set", "com.android.dialer", "ANSWER_PHONE_CALLS", "allow").Run()
	_ = exec.Command("appops", "set", "com.android.shell", "CALL_PHONE", "allow").Run()
}
