package stt

import "context"

// Backend turns 16 kHz mono s16le PCM into text.
type Backend interface {
	Name() string
	Transcribe(ctx context.Context, pcm16kMono []byte) (string, error)
}
