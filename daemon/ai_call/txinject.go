package main

import (
	"fmt"
	"math"
	"os"
	"path/filepath"
)

const defaultTXInject = "/data/vendor/ai_hook/tx_inject.pcm"

// writeTXInject atomically writes raw PCM for HAL incall-music playback.
func writeTXInject(path string, pcm []byte) error {
	if path == "" {
		path = defaultTXInject
	}
	dir := filepath.Dir(path)
	tmp := filepath.Join(dir, fmt.Sprintf(".tx_inject.%d.tmp", os.Getpid()))
	if err := os.WriteFile(tmp, pcm, 0666); err != nil {
		return err
	}
	_ = os.Chmod(tmp, 0666)
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	_ = os.Chmod(path, 0666)
	return nil
}

func clearTXInject(path string) {
	if path == "" {
		path = defaultTXInject
	}
	_ = os.Remove(path)
}

// silenceS16Mono returns ms of zeroed mono s16le PCM (used to cut TX barge-in).
func silenceS16Mono(rate, ms int) []byte {
	if rate <= 0 || ms <= 0 {
		return nil
	}
	n := rate * ms / 1000
	return make([]byte, n*2)
}

// interruptTX replaces the HAL inject queue with a short silence so barge-in cuts speech.
func interruptTX(path string, rate int) {
	if rate <= 0 {
		rate = defaultTXRate
	}
	pcm := silenceS16Mono(rate, 80)
	if len(pcm) == 0 {
		clearTXInject(path)
		return
	}
	_ = writeTXInject(path, pcm)
}

// gainS16Mono scales mono s16le in-place (clipped). gain=1 keeps level.
func gainS16Mono(pcm []byte, gain float64) {
	if gain == 1 || len(pcm) < 2 {
		return
	}
	for i := 0; i+1 < len(pcm); i += 2 {
		v := int16(uint16(pcm[i]) | uint16(pcm[i+1])<<8)
		x := float64(v) * gain
		if x > 32767 {
			x = 32767
		} else if x < -32768 {
			x = -32768
		}
		o := int16(x)
		pcm[i] = byte(o)
		pcm[i+1] = byte(o >> 8)
	}
}

// tonePrefixS16Mono returns ms of 880Hz mono at rate (full-scale diagnostic beep).
func tonePrefixS16Mono(rate, ms int) []byte {
	if rate <= 0 || ms <= 0 {
		return nil
	}
	n := rate * ms / 1000
	out := make([]byte, n*2)
	const amp = 22000.0
	for i := 0; i < n; i++ {
		s := int16(amp * math.Sin(2*math.Pi*880*float64(i)/float64(rate)))
		out[i*2] = byte(s)
		out[i*2+1] = byte(s >> 8)
	}
	return out
}
