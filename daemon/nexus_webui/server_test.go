package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"nexus.nexuscfg"
)

func TestGetConfigRedacted(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	cfg := nexuscfg.Default()
	cfg.LLM.APIKey = "sk-secret"
	_ = nexuscfg.SaveAtomic(path, cfg)
	h := NewServer(path, dir, "")
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/api/config", nil))
	if rr.Code != 200 {
		t.Fatal(rr.Body.String())
	}
	if strings.Contains(rr.Body.String(), "sk-secret") {
		t.Fatal("leaked key")
	}
	var m map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &m); err != nil {
		t.Fatal(err)
	}
	if m["ok"] != true {
		t.Fatal(m)
	}
}

func TestPutConfigPersists(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	_ = nexuscfg.SaveAtomic(path, nexuscfg.Default())
	s := &Server{ConfigPath: path, LogDir: dir, RestartScript: "dummy", RunRestart: func() error { return nil }}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/config", s.handleConfig)
	body := `{"llm":{"barge_in":true,"api_key":"sk-new"}}`
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPut, "/api/config", strings.NewReader(body))
	mux.ServeHTTP(rr, req)
	if rr.Code != 200 {
		t.Fatal(rr.Body.String())
	}
	got, err := nexuscfg.Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if !got.LLM.BargeIn || got.LLM.APIKey != "sk-new" {
		t.Fatalf("%+v", got.LLM)
	}
}

func TestLogsWhitelist(t *testing.T) {
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "ai_call.log"), []byte("line1\nline2\n"), 0644)
	h := NewServer(filepath.Join(dir, "config.json"), dir, "")
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/api/logs?name=ai_call&lines=10", nil))
	if rr.Code != 200 {
		t.Fatal(rr.Body.String())
	}
	if !strings.Contains(rr.Body.String(), "line2") {
		t.Fatal(rr.Body.String())
	}
	rr2 := httptest.NewRecorder()
	h.ServeHTTP(rr2, httptest.NewRequest(http.MethodGet, "/api/logs?name=evil", nil))
	if rr2.Code != 400 {
		t.Fatal(rr2.Code)
	}
}
