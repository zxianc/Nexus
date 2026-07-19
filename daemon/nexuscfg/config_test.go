package nexuscfg

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestSaveLoadRoundTrip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	cfg := Default()
	cfg.LLM.APIKey = "sk-test-key"
	cfg.LLM.BargeIn = true
	if err := SaveAtomic(path, cfg); err != nil {
		t.Fatal(err)
	}
	got, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if got.LLM.APIKey != "sk-test-key" || !got.LLM.BargeIn {
		t.Fatalf("got %+v", got.LLM)
	}
	fi, _ := os.Stat(path)
	if runtime.GOOS != "windows" && fi.Mode().Perm()&0077 != 0 {
		t.Fatalf("expected no group/other bits, mode=%v", fi.Mode())
	}
}

func TestRedactHidesAPIKey(t *testing.T) {
	cfg := Default()
	cfg.LLM.APIKey = "sk-abcdefgh"
	m := Redact(cfg)
	llm := m["llm"].(map[string]any)
	if _, ok := llm["api_key"]; ok {
		t.Fatal("api_key must not appear")
	}
	if llm["api_key_set"] != true {
		t.Fatal("api_key_set")
	}
	if llm["api_key_hint"] != "efgh" {
		t.Fatalf("hint=%v", llm["api_key_hint"])
	}
}

func TestApplyPUTKeepKeyWhenEmpty(t *testing.T) {
	cur := Default()
	cur.LLM.APIKey = "sk-keep"
	next, eng, err := ApplyPUT(cur, []byte(`{"llm":{"model":"x","api_key":""}}`))
	if err != nil {
		t.Fatal(err)
	}
	if next.LLM.APIKey != "sk-keep" || next.LLM.Model != "x" {
		t.Fatalf("%+v", next.LLM)
	}
	_ = eng
}

func TestApplyPUTClearKey(t *testing.T) {
	cur := Default()
	cur.LLM.APIKey = "sk-gone"
	next, _, err := ApplyPUT(cur, []byte(`{"llm":{"api_key":""},"clear_api_key":true}`))
	if err != nil {
		t.Fatal(err)
	}
	if next.LLM.APIKey != "" {
		t.Fatal(next.LLM.APIKey)
	}
}

func TestNeedsEngineRestart(t *testing.T) {
	a, b := Default(), Default()
	if NeedsEngineRestart(a, b) {
		t.Fatal("same")
	}
	b.Paths.STTModel = "/other"
	if !NeedsEngineRestart(a, b) {
		t.Fatal("stt path")
	}
}

func TestRedactHidesNotifySecret(t *testing.T) {
	cfg := Default()
	cfg.Notify.Enabled = true
	cfg.Notify.WeCom.CorpID = "wwcorp"
	cfg.Notify.WeCom.Secret = "sec-abcdefgh"
	cfg.Notify.WeCom.ExternalUserID = "woXXX"
	m := Redact(cfg)
	n := m["notify"].(map[string]any)
	w := n["wecom"].(map[string]any)
	if _, ok := w["secret"]; ok {
		t.Fatal("secret must not appear")
	}
	if w["secret_set"] != true || w["secret_hint"] != "efgh" {
		t.Fatalf("wecom redact=%v", w)
	}
}

func TestApplyPUTNotifyEnabled(t *testing.T) {
	cur := Default()
	next, _, err := ApplyPUT(cur, []byte(`{"notify":{"enabled":true,"sms":{"enabled":false}}}`))
	if err != nil {
		t.Fatal(err)
	}
	if !next.Notify.Enabled || next.Notify.SMS.Enabled {
		t.Fatalf("%+v", next.Notify)
	}
}
