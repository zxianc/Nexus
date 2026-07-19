package llm

import (
	"strings"
	"unicode"
)

// SentenceBuf accumulates streamed LLM text and emits complete sentences
// when punctuation or newline is seen. Remaining text is emitted on Flush.
type SentenceBuf struct {
	buf strings.Builder
	on  func(string)
}

func NewSentenceBuf(onSentence func(string)) *SentenceBuf {
	return &SentenceBuf{on: onSentence}
}

func (b *SentenceBuf) Push(delta string) {
	for _, r := range delta {
		b.buf.WriteRune(r)
		if isSentenceEnd(r) {
			b.emit()
		}
	}
}

func (b *SentenceBuf) Flush() {
	b.emit()
}

func (b *SentenceBuf) emit() {
	s := strings.TrimSpace(b.buf.String())
	b.buf.Reset()
	if !hasSpeechRune(s) {
		return
	}
	if b.on != nil {
		b.on(s)
	}
}

func isSentenceEnd(r rune) bool {
	switch r {
	case '。', '！', '？', '；', '.', '!', '?', ';', '\n':
		return true
	default:
		return false
	}
}

func hasSpeechRune(s string) bool {
	for _, r := range s {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			return true
		}
	}
	return false
}
