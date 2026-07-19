package main

import (
	"fmt"
	"path/filepath"
	"strings"
	"unicode/utf8"

	"nexus.nexuscfg"
)

// CallNotify holds fields for a hangup push.
type CallNotify struct {
	Time       string
	Peer       string
	Local      string
	Policy     string
	Summary    string
	Transcript string
	Archive    string // basename for truncation note
	MaxChars   int
}

// SMSNotify holds fields for an inbox SMS push.
type SMSNotify struct {
	Time    string
	Sender  string
	Local   string
	Body    string
}

// FormatCallMessage builds the WeCom text for a call archive.
func FormatCallMessage(c CallNotify) string {
	peer := strings.TrimSpace(c.Peer)
	if peer == "" {
		peer = "未知"
	}
	local := strings.TrimSpace(c.Local)
	if local == "" {
		local = "未知"
	}
	policy := strings.TrimSpace(c.Policy)
	if policy == "" {
		policy = "-"
	}
	summary := strings.TrimSpace(c.Summary)
	if summary == "" {
		summary = "(无摘要)"
	}
	max := c.MaxChars
	if max <= 0 {
		max = 3500
	}
	tr, truncated := truncateRunes(strings.TrimSpace(c.Transcript), max)
	var b strings.Builder
	b.WriteString("【通话】\n")
	b.WriteString("时间：")
	b.WriteString(strings.TrimSpace(c.Time))
	b.WriteByte('\n')
	b.WriteString("主叫：")
	b.WriteString(peer)
	b.WriteByte('\n')
	b.WriteString("本机：")
	b.WriteString(local)
	b.WriteByte('\n')
	b.WriteString("策略：")
	b.WriteString(policy)
	b.WriteString("\n\n摘要：\n")
	b.WriteString(summary)
	b.WriteString("\n\n对话：\n")
	b.WriteString(tr)
	if truncated {
		name := c.Archive
		if name == "" {
			name = "call_*.txt"
		}
		b.WriteString("\n…(全文已存手机 ")
		b.WriteString(filepath.Base(name))
		b.WriteString(")")
	}
	return b.String()
}

// FormatSMSMessage builds the WeCom text for an SMS.
func FormatSMSMessage(s SMSNotify) string {
	local := strings.TrimSpace(s.Local)
	if local == "" {
		local = "未知"
	}
	var b strings.Builder
	b.WriteString("【短信】\n")
	b.WriteString("时间：")
	b.WriteString(strings.TrimSpace(s.Time))
	b.WriteByte('\n')
	b.WriteString("发件人：")
	b.WriteString(strings.TrimSpace(s.Sender))
	b.WriteByte('\n')
	b.WriteString("收件：")
	b.WriteString(local)
	b.WriteString("\n正文：\n")
	b.WriteString(strings.TrimSpace(s.Body))
	return b.String()
}

// FormatSimLocal renders "卡N carrier (number)" from sims config.
func FormatSimLocal(sims []nexuscfg.Sim, slot int) string {
	for _, s := range sims {
		if s.Slot != slot {
			continue
		}
		label := s.Label
		if label == "" {
			label = fmt.Sprintf("卡%d", slot+1)
		}
		parts := []string{label}
		if c := strings.TrimSpace(s.Carrier); c != "" {
			parts = append(parts, c)
		}
		if n := strings.TrimSpace(s.Number); n != "" {
			parts = append(parts, "("+n+")")
		}
		return strings.Join(parts, " ")
	}
	return fmt.Sprintf("卡%d", slot+1)
}

func truncateRunes(s string, max int) (string, bool) {
	if max <= 0 || utf8.RuneCountInString(s) <= max {
		return s, false
	}
	r := []rune(s)
	return string(r[:max]), true
}

// ParseCallArchive extracts time/summary/transcript from ai_call archive body.
func ParseCallArchive(body string) (timeStr, summary, transcript, peer, local, policy string) {
	lines := strings.Split(body, "\n")
	var section string
	var sumB, trB strings.Builder
	for _, line := range lines {
		trim := strings.TrimSpace(line)
		switch {
		case strings.HasPrefix(trim, "时间:") || strings.HasPrefix(trim, "时间："):
			timeStr = strings.TrimSpace(strings.TrimPrefix(strings.TrimPrefix(trim, "时间:"), "时间："))
		case strings.HasPrefix(trim, "主叫:") || strings.HasPrefix(trim, "主叫："):
			peer = strings.TrimSpace(strings.TrimPrefix(strings.TrimPrefix(trim, "主叫:"), "主叫："))
		case strings.HasPrefix(trim, "本机:") || strings.HasPrefix(trim, "本机："):
			local = strings.TrimSpace(strings.TrimPrefix(strings.TrimPrefix(trim, "本机:"), "本机："))
		case strings.HasPrefix(trim, "策略:") || strings.HasPrefix(trim, "策略："):
			policy = strings.TrimSpace(strings.TrimPrefix(strings.TrimPrefix(trim, "策略:"), "策略："))
		case trim == "## 摘要":
			section = "summary"
			continue
		case trim == "## 对话":
			section = "transcript"
			continue
		case strings.HasPrefix(trim, "#"):
			section = ""
			continue
		}
		switch section {
		case "summary":
			if sumB.Len() > 0 {
				sumB.WriteByte('\n')
			}
			sumB.WriteString(line)
		case "transcript":
			if trB.Len() > 0 {
				trB.WriteByte('\n')
			}
			trB.WriteString(line)
		}
	}
	summary = strings.TrimSpace(sumB.String())
	transcript = strings.TrimSpace(trB.String())
	return
}
