package llm

import (
	"strings"
	"testing"
	"time"
)

func TestExpandSystemPromptReplacesNOW(t *testing.T) {
	// Monday 2026-07-20 15:04 CST
	now := time.Date(2026, 7, 20, 15, 4, 0, 0, time.FixedZone("CST", 8*3600))
	got := ExpandSystemPrompt("时间={{NOW}}", now)
	if !strings.Contains(got, "2026年7月20日") || !strings.Contains(got, "星期一") {
		t.Fatalf("got=%q", got)
	}
	if !strings.Contains(got, "工作日") {
		t.Fatalf("want 工作日: %q", got)
	}
	if strings.Contains(got, "{{NOW}}") {
		t.Fatal("placeholder left")
	}
}

func TestExpandSystemPromptWeekend(t *testing.T) {
	now := time.Date(2026, 7, 19, 19, 0, 0, 0, time.FixedZone("CST", 8*3600)) // Sunday
	got := ExpandSystemPrompt("{{NOW}}", now)
	if !strings.Contains(got, "休息日") || !strings.Contains(got, "星期日") {
		t.Fatalf("got=%q", got)
	}
}

func TestExpandSystemPromptAppendsIfNoPlaceholder(t *testing.T) {
	now := time.Date(2026, 7, 20, 10, 0, 0, 0, time.FixedZone("CST", 8*3600))
	got := ExpandSystemPrompt("你好", now)
	if !strings.Contains(got, "你好") || !strings.Contains(got, "当前时间：") {
		t.Fatalf("got=%q", got)
	}
}
