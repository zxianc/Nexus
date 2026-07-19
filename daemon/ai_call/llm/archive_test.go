package llm

import (
	"strings"
	"testing"
)

func TestFormatTranscript(t *testing.T) {
	got := FormatTranscript([]Message{
		{Role: "user", Content: "外卖到了"},
		{Role: "assistant", Content: "好的，放门口。"},
		{Role: "user", Content: "谢谢"},
	})
	want := "对方: 外卖到了\n助理: 好的，放门口。\n对方: 谢谢\n"
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

func TestFormatTranscript_Empty(t *testing.T) {
	if FormatTranscript(nil) != "" {
		t.Fatal("want empty")
	}
}

func TestSnapshotAndReset(t *testing.T) {
	s := NewCallSession(10)
	s.AppendUser("a")
	s.AppendAssistant("b")
	hist := s.SnapshotAndReset()
	if len(hist) != 2 {
		t.Fatalf("hist=%v", hist)
	}
	if s.Turns() != 0 {
		t.Fatal("not reset")
	}
	if FormatTranscript(hist) != "对方: a\n助理: b\n" {
		t.Fatalf("%q", FormatTranscript(hist))
	}
}

func TestBuildCallArchive(t *testing.T) {
	body := BuildCallArchive("2026-07-19 15:59:01", "对方要放门口。", "对方: 外卖到了\n助理: 好的。\n")
	for _, p := range []string{"## 摘要", "对方要放门口。", "## 对话", "对方: 外卖到了", "# 通话记录"} {
		if !strings.Contains(body, p) {
			t.Fatalf("missing %q in %s", p, body)
		}
	}
}
