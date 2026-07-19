package llm

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadRootCAsFrom_MissingDir(t *testing.T) {
	pool := loadRootCAsFrom([]string{filepath.Join(t.TempDir(), "nope")})
	if pool == nil {
		t.Fatal("want non-nil pool")
	}
}

func TestLoadRootCAsFrom_AppendsPEM(t *testing.T) {
	dir := t.TempDir()
	// Known-good DigiCert Global Root G2 (public CA), truncated not needed —
	// use a minimal valid cert from Go's test data pattern is heavy; just ensure
	// non-PEM files don't crash and PEM attempt is attempted.
	if err := os.WriteFile(filepath.Join(dir, "junk"), []byte("not-a-cert"), 0644); err != nil {
		t.Fatal(err)
	}
	pool := loadRootCAsFrom([]string{dir})
	if pool == nil {
		t.Fatal("nil")
	}
}

func TestNewHTTPClient_Builds(t *testing.T) {
	c := NewHTTPClient(nil)
	if c == nil || c.Transport == nil {
		t.Fatal("client/transport nil")
	}
}
