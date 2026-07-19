package main

import "testing"

func TestHasSpeechText(t *testing.T) {
	cases := []struct {
		in   string
		want bool
	}{
		{"喂。", true},
		{"听见吗？", true},
		{"OKOK好的。", true},
		{"hello", true},
		{"123", true},
		{"。", false},
		{"，。！？", false},
		{"...", false},
		{"", false},
		{"   ", false},
		{"「」", false},
	}
	for _, c := range cases {
		if got := hasSpeechText(c.in); got != c.want {
			t.Fatalf("hasSpeechText(%q)=%v want %v", c.in, got, c.want)
		}
	}
}

func TestDefaultVADMinSpeechMs(t *testing.T) {
	cfg := DefaultVADConfig()
	if cfg.MinSpeechMs < 500 {
		t.Fatalf("MinSpeechMs=%d want >=500", cfg.MinSpeechMs)
	}
}
