package main

import (
	"encoding/binary"
	"testing"
)

func toneFrame(rmsApprox int, samples int) []byte {
	b := make([]byte, samples*2)
	for i := 0; i < samples; i++ {
		binary.LittleEndian.PutUint16(b[i*2:], uint16(int16(rmsApprox)))
	}
	return b
}

func TestEnergyVADSpeechThenSilence(t *testing.T) {
	v := NewEnergyVAD(VADConfig{
		FrameMs:      20,
		SpeechRMS:    100,
		SilenceRMS:   50,
		MinSpeechMs:  40,
		SilenceEndMs: 40,
		MaxSpeechMs:  5000,
		PreRollMs:    20,
	})
	// 3 speech frames + 3 silence frames
	speech := toneFrame(500, 320) // 20ms @16k
	silence := toneFrame(0, 320)
	var got []Utterance
	for i := 0; i < 3; i++ {
		got = append(got, v.Push(speech)...)
	}
	for i := 0; i < 3; i++ {
		got = append(got, v.Push(silence)...)
	}
	if len(got) != 1 {
		t.Fatalf("want 1 utterance, got %d", len(got))
	}
	if len(got[0].PCM16k) < 320*2 {
		t.Fatalf("utt too short: %d bytes", len(got[0].PCM16k))
	}
}

func TestStereoToMono16k(t *testing.T) {
	// 3 stereo frames @48k -> 1 mono @16k
	in := make([]byte, 3*4)
	for i := 0; i < 3; i++ {
		binary.LittleEndian.PutUint16(in[i*4:], 300)
		binary.LittleEndian.PutUint16(in[i*4+2:], 300)
	}
	out := stereoS16ToMono16k(in, 2, 48000)
	if len(out) != 2 {
		t.Fatalf("want 2 bytes, got %d", len(out))
	}
}
