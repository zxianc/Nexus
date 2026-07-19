package stt

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// Sherpa invokes sherpa-onnx-offline SenseVoice CLI.
type Sherpa struct {
	Bin      string // path to sherpa-onnx-offline
	ModelDir string // dir with model.int8.onnx + tokens.txt
	Language string // e.g. zh or auto
	Threads  int
	TmpDir   string
	UseITN   bool
}

func (s Sherpa) Name() string { return "sherpa" }

func (s Sherpa) Transcribe(ctx context.Context, pcm16kMono []byte) (string, error) {
	if s.Bin == "" {
		return "", fmt.Errorf("sherpa: empty Bin")
	}
	if s.ModelDir == "" {
		return "", fmt.Errorf("sherpa: empty ModelDir")
	}
	model := filepath.Join(s.ModelDir, "model.int8.onnx")
	tokens := filepath.Join(s.ModelDir, "tokens.txt")
	if _, err := os.Stat(model); err != nil {
		return "", fmt.Errorf("sherpa model: %w", err)
	}
	if _, err := os.Stat(tokens); err != nil {
		return "", fmt.Errorf("sherpa tokens: %w", err)
	}
	tmpDir := s.TmpDir
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

	threads := s.Threads
	if threads <= 0 {
		threads = 2
	}
	lang := s.Language
	if lang == "" {
		lang = "zh"
	}
	args := []string{
		fmt.Sprintf("--tokens=%s", tokens),
		fmt.Sprintf("--sense-voice-model=%s", model),
		fmt.Sprintf("--num-threads=%d", threads),
		fmt.Sprintf("--sense-voice-language=%s", lang),
		"--debug=0",
	}
	if s.UseITN {
		args = append(args, "--sense-voice-use-itn=1")
	}
	args = append(args, wavPath)

	cmd := exec.CommandContext(ctx, s.Bin, args...)
	cmd.Dir = s.ModelDir
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return "", fmt.Errorf("sherpa run: %w stderr=%s", err, truncate(stderr.String(), 400))
	}
	text := parseSherpaText(stdout.String())
	if text == "" {
		return "", fmt.Errorf("sherpa: empty text stdout=%s", truncate(stdout.String(), 400))
	}
	return text, nil
}

type senseJSON struct {
	Text string `json:"text"`
}

func parseSherpaText(out string) string {
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, "{") {
			continue
		}
		var j senseJSON
		if err := json.Unmarshal([]byte(line), &j); err != nil {
			continue
		}
		if t := strings.TrimSpace(j.Text); t != "" {
			return t
		}
	}
	return ""
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}

func writeWav16kMono(path string, pcm []byte) error {
	const rate = 16000
	const ch = 1
	const bits = 16
	dataLen := len(pcm)
	buf := make([]byte, 44+dataLen)
	copy(buf[0:4], "RIFF")
	binary.LittleEndian.PutUint32(buf[4:], uint32(36+dataLen))
	copy(buf[8:12], "WAVE")
	copy(buf[12:16], "fmt ")
	binary.LittleEndian.PutUint32(buf[16:], 16)
	binary.LittleEndian.PutUint16(buf[20:], 1)
	binary.LittleEndian.PutUint16(buf[22:], ch)
	binary.LittleEndian.PutUint32(buf[24:], rate)
	binary.LittleEndian.PutUint32(buf[28:], rate*uint32(ch)*bits/8)
	binary.LittleEndian.PutUint16(buf[32:], uint16(ch*bits/8))
	binary.LittleEndian.PutUint16(buf[34:], bits)
	copy(buf[36:40], "data")
	binary.LittleEndian.PutUint32(buf[40:], uint32(dataLen))
	copy(buf[44:], pcm)
	return os.WriteFile(path, buf, 0644)
}
