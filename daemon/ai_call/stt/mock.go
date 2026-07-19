package stt

import (
	"context"
	"fmt"
)

// Mock logs utterance size/energy proxy without real ASR.
type Mock struct{}

func (Mock) Name() string { return "mock" }

func (Mock) Transcribe(_ context.Context, pcm16kMono []byte) (string, error) {
	n := len(pcm16kMono) / 2
	ms := n * 1000 / 16000
	return fmt.Sprintf("[mock] samples=%d dur_ms=%d", n, ms), nil
}
