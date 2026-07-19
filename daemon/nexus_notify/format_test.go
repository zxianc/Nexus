package main

import (
	"strings"
	"testing"

	"nexus.nexuscfg"
)

func TestFormatCallMessageTruncate(t *testing.T) {
	long := strings.Repeat("对", 100)
	msg := FormatCallMessage(CallNotify{
		Time:       "2026-07-19 19:09:00",
		Peer:       "177",
		Local:      "卡1",
		Policy:     "ai",
		Summary:    "测试摘要",
		Transcript: long,
		Archive:    "/data/vendor/ai_hook/calls/call_20260719_190900.txt",
		MaxChars:   20,
	})
	if !strings.Contains(msg, "【通话】") || !strings.Contains(msg, "测试摘要") {
		t.Fatalf("msg=%q", msg)
	}
	if !strings.Contains(msg, "call_20260719_190900.txt") {
		t.Fatalf("missing truncate note: %q", msg)
	}
	if strings.Count(msg, "对") > 25 {
		t.Fatalf("not truncated: %q", msg)
	}
}

func TestFormatSMSMessage(t *testing.T) {
	msg := FormatSMSMessage(SMSNotify{
		Time:   "2026-07-19 19:10:00",
		Sender: "10086",
		Local:  "卡2 CHN-UNICOM",
		Body:   "您好",
	})
	if !strings.Contains(msg, "【短信】") || !strings.Contains(msg, "10086") || !strings.Contains(msg, "您好") {
		t.Fatalf("%q", msg)
	}
}

func TestFormatSimLocal(t *testing.T) {
	sims := []nexuscfg.Sim{{Slot: 0, Label: "卡1", Carrier: "CMCC", Number: "+86139"}}
	got := FormatSimLocal(sims, 0)
	if !strings.Contains(got, "CMCC") || !strings.Contains(got, "+86139") {
		t.Fatalf("%q", got)
	}
}

func TestSubIDToSlotFallback(t *testing.T) {
	// Naive helper kept for empty-map fallback only; device uses SubSlotMap.
	if SubIDToSlot(1) != 0 || SubIDToSlot(2) != 1 {
		t.Fatal(SubIDToSlot(1), SubIDToSlot(2))
	}
}

func TestParseCallArchive(t *testing.T) {
	body := `# 通话记录
时间: 2026-07-19 15:59:01
主叫: 177
本机: 卡1 CMCC
策略: ai

## 摘要
对方要放门口。

## 对话
对方: 外卖到了
助理: 好的。
`
	tm, sum, tr, peer, local, pol := ParseCallArchive(body)
	if tm != "2026-07-19 15:59:01" || peer != "177" || local != "卡1 CMCC" || pol != "ai" {
		t.Fatalf("meta tm=%q peer=%q local=%q pol=%q", tm, peer, local, pol)
	}
	if sum != "对方要放门口。" || !strings.Contains(tr, "外卖到了") {
		t.Fatalf("sum=%q tr=%q", sum, tr)
	}
}

func TestTruncateRunes(t *testing.T) {
	s, trunc := truncateRunes("你好世界", 2)
	if s != "你好" || !trunc {
		t.Fatalf("%q %v", s, trunc)
	}
}
