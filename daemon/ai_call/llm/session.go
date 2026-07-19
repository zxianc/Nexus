package llm

import (
	"strings"
	"sync"
)

// CallSession holds one phone-call's chat history (no system message stored).
// Safe for concurrent Reset from the stream goroutine and Append from sttWorker.
type CallSession struct {
	mu          sync.Mutex
	hist        []Message
	maxMessages int // non-system cap; 0 → default
	gen         uint64
}

const defaultMaxMessages = 24 // ~12 turns

func NewCallSession(maxMessages int) *CallSession {
	if maxMessages <= 0 {
		maxMessages = defaultMaxMessages
	}
	return &CallSession{maxMessages: maxMessages}
}

// Reset clears history and bumps generation so in-flight replies cannot pollute the next call.
func (s *CallSession) Reset() {
	if s == nil {
		return
	}
	s.mu.Lock()
	s.hist = nil
	s.gen++
	s.mu.Unlock()
}

// Generation returns the current call epoch (use with AppendAssistantGen).
func (s *CallSession) Generation() uint64 {
	if s == nil {
		return 0
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.gen
}

func (s *CallSession) AppendUser(text string) {
	s.append("user", text)
}

func (s *CallSession) AppendAssistant(text string) {
	s.append("assistant", text)
}

// AppendUserGen appends a user turn only if gen still matches.
func (s *CallSession) AppendUserGen(gen uint64, text string) bool {
	return s.appendGen(gen, "user", text)
}

// AppendAssistantGen appends only if gen still matches (call not reset).
func (s *CallSession) AppendAssistantGen(gen uint64, text string) bool {
	return s.appendGen(gen, "assistant", text)
}

func (s *CallSession) appendGen(gen uint64, role, text string) bool {
	if s == nil {
		return false
	}
	text = strings.TrimSpace(text)
	if text == "" {
		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if gen != s.gen {
		return false
	}
	s.hist = append(s.hist, Message{Role: role, Content: text})
	s.trimLocked()
	return true
}

func (s *CallSession) append(role, text string) {
	if s == nil {
		return
	}
	text = strings.TrimSpace(text)
	if text == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.hist = append(s.hist, Message{Role: role, Content: text})
	s.trimLocked()
}

func (s *CallSession) trimLocked() {
	if len(s.hist) <= s.maxMessages {
		return
	}
	s.hist = append([]Message(nil), s.hist[len(s.hist)-s.maxMessages:]...)
}

// Messages returns system + history copy for the next API call.
func (s *CallSession) Messages(system string) []Message {
	out := make([]Message, 0, 1+16)
	if sys := strings.TrimSpace(system); sys != "" {
		out = append(out, Message{Role: "system", Content: sys})
	}
	if s == nil {
		return out
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	out = append(out, s.hist...)
	return out
}

// Turns counts completed user messages in history.
func (s *CallSession) Turns() int {
	if s == nil {
		return 0
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	n := 0
	for _, m := range s.hist {
		if m.Role == "user" {
			n++
		}
	}
	return n
}
