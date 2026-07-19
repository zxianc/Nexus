package tts

import "context"

// Backend synthesizes text to PCM (s16le mono at SampleRate).
type Backend interface {
	Name() string
	SampleRate() int
	Synthesize(ctx context.Context, text string) (pcm16Mono []byte, err error)
}
