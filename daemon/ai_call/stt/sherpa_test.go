package stt

import "testing"

func TestParseSherpaText(t *testing.T) {
	out := `Done!
./x.wav
{"lang": "<|zh|>", "text": "你好世界", "tokens":[]}
----
`
	got := parseSherpaText(out)
	if got != "你好世界" {
		t.Fatalf("got %q", got)
	}
}
