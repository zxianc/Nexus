package nexuscfg

import (
	"os"
	"path/filepath"
	"testing"
)

func TestMigrateFillsKeyFromSecretsFile(t *testing.T) {
	dir := t.TempDir()
	sec := filepath.Join(dir, "secrets")
	if err := os.MkdirAll(sec, 0700); err != nil {
		t.Fatal(err)
	}
	keyPath := filepath.Join(sec, "deepseek.key")
	if err := os.WriteFile(keyPath, []byte("  sk-from-file\n"), 0600); err != nil {
		t.Fatal(err)
	}
	cfg := Default()
	got, changed, err := Migrate(dir, cfg)
	if err != nil {
		t.Fatal(err)
	}
	if !changed || got.LLM.APIKey != "sk-from-file" {
		t.Fatalf("changed=%v key=%q", changed, got.LLM.APIKey)
	}
}

func TestMigrateNoOpWhenKeyPresent(t *testing.T) {
	dir := t.TempDir()
	cfg := Default()
	cfg.LLM.APIKey = "sk-already"
	got, changed, err := Migrate(dir, cfg)
	if err != nil {
		t.Fatal(err)
	}
	if changed || got.LLM.APIKey != "sk-already" {
		t.Fatalf("changed=%v key=%q", changed, got.LLM.APIKey)
	}
}
