package tts

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Sherpa invokes sherpa-onnx-offline-tts (VITS).
type Sherpa struct {
	Bin      string // sherpa-onnx-offline-tts
	ModelDir string // dir with model.onnx, tokens.txt, lexicon.txt
	Sid      int
	Speed    float64
	Threads  int
	TmpDir   string
}

func (s Sherpa) Name() string { return "sherpa-tts" }

// SampleRate is the nominal rate for zh-ll; prefer rate from SynthesizeEx.
func (s Sherpa) SampleRate() int { return 16000 }

func (s Sherpa) Synthesize(ctx context.Context, text string) ([]byte, error) {
	pcm, _, err := s.SynthesizeEx(ctx, text)
	return pcm, err
}

// SynthesizeEx returns PCM s16le mono and sample rate from the produced wav.
func (s Sherpa) SynthesizeEx(ctx context.Context, text string) ([]byte, int, error) {
	text = strings.TrimSpace(text)
	if text == "" {
		return nil, 0, fmt.Errorf("tts: empty text")
	}
	if s.Bin == "" {
		return nil, 0, fmt.Errorf("tts: empty Bin")
	}
	model := filepath.Join(s.ModelDir, "model.onnx")
	tokens := filepath.Join(s.ModelDir, "tokens.txt")
	lexicon := filepath.Join(s.ModelDir, "lexicon.txt")
	for _, p := range []string{model, tokens, lexicon} {
		if _, err := os.Stat(p); err != nil {
			return nil, 0, fmt.Errorf("tts asset: %w", err)
		}
	}
	tmpDir := s.TmpDir
	if tmpDir == "" {
		tmpDir = os.TempDir()
	}
	if err := os.MkdirAll(tmpDir, 0755); err != nil {
		return nil, 0, err
	}
	wavPath := filepath.Join(tmpDir, fmt.Sprintf("tts_%d.wav", time.Now().UnixNano()))
	defer os.Remove(wavPath)

	threads := s.Threads
	if threads <= 0 {
		threads = 2
	}
	speed := s.Speed
	if speed <= 0 {
		speed = 1.0
	}
	args := []string{
		fmt.Sprintf("--vits-model=%s", model),
		fmt.Sprintf("--vits-tokens=%s", tokens),
		fmt.Sprintf("--vits-lexicon=%s", lexicon),
		fmt.Sprintf("--num-threads=%d", threads),
		fmt.Sprintf("--sid=%d", s.Sid),
		fmt.Sprintf("--speed=%s", strconv.FormatFloat(speed, 'f', -1, 64)),
		fmt.Sprintf("--output-filename=%s", wavPath),
	}
	var fsts []string
	for _, name := range []string{"phone.fst", "date.fst", "number.fst"} {
		p := filepath.Join(s.ModelDir, name)
		if _, err := os.Stat(p); err == nil {
			fsts = append(fsts, p)
		}
	}
	if len(fsts) > 0 {
		args = append(args, fmt.Sprintf("--tts-rule-fsts=%s", strings.Join(fsts, ",")))
	}
	args = append(args, text)

	cmd := exec.CommandContext(ctx, s.Bin, args...)
	cmd.Dir = s.ModelDir
	libDir := filepath.Dir(s.Bin)
	ld := libDir
	if prev := os.Getenv("LD_LIBRARY_PATH"); prev != "" {
		ld = libDir + ":" + prev
	}
	cmd.Env = append(os.Environ(), "LD_LIBRARY_PATH="+ld)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return nil, 0, fmt.Errorf("tts run: %w stderr=%s", err, truncate(stderr.String(), 400))
	}
	pcm, rate, ch, err := readWavPCM(wavPath)
	if err != nil {
		return nil, 0, err
	}
	if ch != 1 {
		pcm = stereoToMono(pcm, ch)
	}
	return pcm, rate, nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}

func stereoToMono(in []byte, ch int) []byte {
	if ch <= 1 {
		return in
	}
	frames := len(in) / (2 * ch)
	out := make([]byte, frames*2)
	for i := 0; i < frames; i++ {
		out[i*2] = in[i*ch*2]
		out[i*2+1] = in[i*ch*2+1]
	}
	return out
}
