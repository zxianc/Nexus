package nexuscfg

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
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

func TestApplyPUTNotifyWebhook(t *testing.T) {
	cur := Default()
	next, _, err := ApplyPUT(cur, []byte(`{
	  "notify":{
	    "enabled":true,
	    "channel":"wecom_webhook",
	    "wecom":{"webhook_url":"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc123456789"},
	    "sms":{"enabled":true,"poll_ms":5000},
	    "call":{"enabled":true,"max_transcript_chars":2000}
	  }
	}`))
	if err != nil {
		t.Fatal(err)
	}
	if !next.Notify.Enabled || next.Notify.Channel != "wecom_webhook" {
		t.Fatalf("%+v", next.Notify)
	}
	if !strings.Contains(next.Notify.WeCom.WebhookURL, "key=abc") {
		t.Fatal(next.Notify.WeCom.WebhookURL)
	}
	if next.Notify.SMS.PollMs != 5000 || next.Notify.Call.MaxTranscriptChars != 2000 {
		t.Fatalf("%+v", next.Notify)
	}
	m := Redact(next)["notify"].(map[string]any)
	w := m["wecom"].(map[string]any)
	if w["webhook_url_set"] != true || w["webhook_url_hint"] == "" {
		t.Fatalf("%v", w)
	}
	cleared, _, err := ApplyPUT(next, []byte(`{"clear_webhook_url":true}`))
	if err != nil || cleared.Notify.WeCom.WebhookURL != "" {
		t.Fatalf("clear: %v %+v", err, cleared.Notify.WeCom)
	}
}

func TestNeedsCallstackRestart(t *testing.T) {
	a, b := Default(), Default()
	if NeedsCallstackRestart(a, b) {
		t.Fatal("same")
	}
	b.Sims[0].Policy = PolicyAI
	if NeedsCallstackRestart(a, b) {
		t.Fatal("sims-only should not restart callstack")
	}
	b.Notify.Enabled = true
	if NeedsCallstackRestart(a, b) {
		t.Fatal("notify-only should not restart callstack")
	}
	b.LLM.BargeIn = true
	if !NeedsCallstackRestart(a, b) {
		t.Fatal("llm change should restart")
	}
}
