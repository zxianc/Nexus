package main

import (
	"os"
	"path/filepath"
	"testing"

	"nexus.nexuscfg"
)

func TestLoadNexusFileOpts(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	cfg := nexuscfg.Default()
	cfg.LLM.APIKey = "sk-from-json"
	cfg.LLM.BargeIn = true
	cfg.LLM.Model = "deepseek-v4-flash"
	if err := nexuscfg.SaveAtomic(path, cfg); err != nil {
		t.Fatal(err)
	}
	got, ok := loadNexusFileOpts(path)
	if !ok {
		t.Fatal("expected ok")
	}
	if got.APIKey != "sk-from-json" || !got.BargeIn {
		t.Fatalf("%+v", got)
	}
}

func TestLoadNexusFileOptsMissing(t *testing.T) {
	_, ok := loadNexusFileOpts(filepath.Join(t.TempDir(), "nope.json"))
	if ok {
		t.Fatal("expected missing")
	}
	_ = os.ErrNotExist
}
