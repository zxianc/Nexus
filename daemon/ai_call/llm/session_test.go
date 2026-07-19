package llm

import (
	"strings"
	"testing"
)

func TestCallSession_BuildsWithSystemAndHistory(t *testing.T) {
	s := NewCallSession(10)
	s.AppendUser("外卖到了")
	s.AppendAssistant("好的，放门口。")
	msgs := s.Messages("sys")
	if len(msgs) != 3 {
		t.Fatalf("len=%d msgs=%v", len(msgs), msgs)
	}
	if msgs[0].Role != "system" || !strings.Contains(msgs[0].Content, "sys") {
		t.Fatalf("system=%+v", msgs[0])
	}
	if !strings.Contains(msgs[0].Content, "当前时间：") {
		t.Fatalf("expected time injection: %+v", msgs[0])
	}
	if msgs[1].Role != "user" || msgs[1].Content != "外卖到了" {
		t.Fatalf("user=%+v", msgs[1])
	}
	if msgs[2].Role != "assistant" || msgs[2].Content != "好的，放门口。" {
		t.Fatalf("asst=%+v", msgs[2])
	}
}

func TestCallSession_TrimKeepsRecent(t *testing.T) {
	s := NewCallSession(2) // max 2 non-system messages
	s.AppendUser("一")
	s.AppendAssistant("A")
	s.AppendUser("二")
	s.AppendAssistant("B")
	s.AppendUser("三")
	msgs := s.Messages("sys")
	// system + last 2: user三 is incomplete pair — trim by message count
	if len(msgs) != 3 { // sys + 二? wait after append user三 we have 5 hist, trim to 2
		t.Fatalf("len=%d msgs=%v", len(msgs), msgs)
	}
	// After user三: history was [u1,a1,u2,a2,u3] trim to last 2 → [a2,u3]
	if msgs[1].Content != "B" || msgs[2].Content != "三" {
		t.Fatalf("want B,三 got %+v %+v", msgs[1], msgs[2])
	}
}

func TestCallSession_Reset(t *testing.T) {
	s := NewCallSession(10)
	s.AppendUser("hi")
	s.AppendAssistant("yo")
	s.Reset()
	msgs := s.Messages("sys")
	if len(msgs) != 1 || msgs[0].Role != "system" {
		t.Fatalf("after reset %v", msgs)
	}
	if s.Turns() != 0 {
		t.Fatalf("turns=%d", s.Turns())
	}
}

func TestCallSession_AppendAssistantGen_IgnoresStale(t *testing.T) {
	s := NewCallSession(10)
	gen := s.Generation()
	if !s.AppendUserGen(gen, "hi") {
		t.Fatal("append user")
	}
	s.Reset()
	if s.AppendAssistantGen(gen, "stale reply") {
		t.Fatal("stale assistant should fail")
	}
	if s.AppendUserGen(gen, "also stale") {
		t.Fatal("stale user should fail")
	}
	msgs := s.Messages("sys")
	if len(msgs) != 1 {
		t.Fatalf("stale should be dropped, got %v", msgs)
	}
	g2 := s.Generation()
	s.AppendUserGen(g2, "new")
	s.AppendAssistantGen(g2, "fresh")
	msgs = s.Messages("sys")
	if len(msgs) != 3 || msgs[2].Content != "fresh" {
		t.Fatalf("got %v", msgs)
	}
}

