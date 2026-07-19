package tts

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"nexus.ai_call/engine"
)

// Engine uses resident nexus_engine over UDS.
type Engine struct {
	Client *engine.Client
	Sid    int
	TmpDir string
}

func (e Engine) Name() string { return "engine-tts" }

func (e Engine) SampleRate() int { return 16000 }

func (e Engine) Synthesize(ctx context.Context, text string) ([]byte, error) {
	pcm, _, err := e.SynthesizeEx(ctx, text)
	return pcm, err
}

func (e Engine) SynthesizeEx(ctx context.Context, text string) ([]byte, int, error) {
	text = strings.TrimSpace(text)
	if text == "" {
		return nil, 0, fmt.Errorf("engine tts: empty text")
	}
	if e.Client == nil {
		return nil, 0, fmt.Errorf("engine tts: nil client")
	}
	tmpDir := e.TmpDir
	if tmpDir == "" {
		tmpDir = os.TempDir()
	}
	if err := os.MkdirAll(tmpDir, 0755); err != nil {
		return nil, 0, err
	}
	wavPath := filepath.Join(tmpDir, fmt.Sprintf("tts_%d.wav", time.Now().UnixNano()))
	defer os.Remove(wavPath)
	rate, err := e.Client.TTS(ctx, text, wavPath, e.Sid)
	if err != nil {
		return nil, 0, err
	}
	pcm, wr, ch, err := readWavPCM(wavPath)
	if err != nil {
		return nil, 0, err
	}
	if rate <= 0 {
		rate = wr
	}
	if ch != 1 {
		pcm = stereoToMono(pcm, ch)
	}
	return pcm, rate, nil
}
