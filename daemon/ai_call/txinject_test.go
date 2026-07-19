package main

import "testing"

func TestSilenceS16Mono_Length(t *testing.T) {
	pcm := silenceS16Mono(48000, 50)
	want := 48000 * 50 / 1000 * 2
	if len(pcm) != want {
		t.Fatalf("len=%d want=%d", len(pcm), want)
	}
	for _, b := range pcm {
		if b != 0 {
			t.Fatal("expected silence")
		}
	}
}

func TestSilenceS16Mono_BadArgs(t *testing.T) {
	if silenceS16Mono(0, 50) != nil || silenceS16Mono(48000, 0) != nil {
		t.Fatal("want nil")
	}
}
