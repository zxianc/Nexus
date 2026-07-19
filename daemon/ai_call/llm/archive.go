package llm

import (
	"fmt"
	"strings"
	"time"
)

// CallArchiveMeta is optional identity written into the archive header.
type CallArchiveMeta struct {
	Peer   string
	Local  string
	Policy string
}

// FormatTranscript renders history as human-readable Chinese dialogue lines.
func FormatTranscript(hist []Message) string {
	if len(hist) == 0 {
		return ""
	}
	var b strings.Builder
	for _, m := range hist {
		label := m.Role
		switch m.Role {
		case "user":
			label = "对方"
		case "assistant":
			label = "助理"
		case "system":
			continue
		}
		b.WriteString(label)
		b.WriteString(": ")
		b.WriteString(strings.TrimSpace(m.Content))
		b.WriteByte('\n')
	}
	return b.String()
}

// BuildCallArchive builds a single markdown-ish file body for disk.
func BuildCallArchive(started, summary, transcript string, meta CallArchiveMeta) string {
	var b strings.Builder
	b.WriteString("# 通话记录\n")
	b.WriteString("时间: ")
	b.WriteString(started)
	b.WriteByte('\n')
	peer := strings.TrimSpace(meta.Peer)
	if peer == "" {
		peer = "未知"
	}
	b.WriteString("主叫: ")
	b.WriteString(peer)
	b.WriteByte('\n')
	local := strings.TrimSpace(meta.Local)
	if local == "" {
		local = "未知"
	}
	b.WriteString("本机: ")
	b.WriteString(local)
	b.WriteByte('\n')
	if pol := strings.TrimSpace(meta.Policy); pol != "" {
		b.WriteString("策略: ")
		b.WriteString(pol)
		b.WriteByte('\n')
	}
	b.WriteString("\n## 摘要\n")
	b.WriteString(strings.TrimSpace(summary))
	b.WriteString("\n\n## 对话\n")
	b.WriteString(transcript)
	if !strings.HasSuffix(transcript, "\n") && transcript != "" {
		b.WriteByte('\n')
	}
	return b.String()
}

// SummaryPrompt asks the model for a short call summary (non-TTS).
const SummaryPrompt = `根据以下电话对话，用简体中文写一段简短摘要（3～6句）：对方意图、关键信息、约定事项。不要用 Markdown 列表，不要复述全文。`

// SnapshotAndReset copies history then clears the session (bumps generation).
func (s *CallSession) SnapshotAndReset() []Message {
	if s == nil {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	hist := append([]Message(nil), s.hist...)
	s.hist = nil
	s.gen++
	return hist
}

// CallArchiveName returns a filesystem-safe name for one call archive.
func CallArchiveName(t time.Time) string {
	return fmt.Sprintf("call_%s.txt", t.Format("20060102_150405"))
}
