package stt

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"nexus.ai_call/engine"
)

// Engine uses resident nexus_engine over UDS.
type Engine struct {
	Client *engine.Client
	TmpDir string
}

func (e Engine) Name() string { return "engine" }

func (e Engine) Transcribe(ctx context.Context, pcm16kMono []byte) (string, error) {
	if e.Client == nil {
		return "", fmt.Errorf("engine stt: nil client")
	}
	tmpDir := e.TmpDir
	if tmpDir == "" {
		tmpDir = os.TempDir()
	}
	if err := os.MkdirAll(tmpDir, 0755); err != nil {
		return "", err
	}
	wavPath := filepath.Join(tmpDir, fmt.Sprintf("utt_%d.wav", time.Now().UnixNano()))
	if err := writeWav16kMono(wavPath, pcm16kMono); err != nil {
		return "", err
	}
	defer os.Remove(wavPath)
	text, err := e.Client.STT(ctx, wavPath)
	if err != nil {
		return "", err
	}
	if text == "" {
		return "", fmt.Errorf("engine stt: empty text")
	}
	return text, nil
}
