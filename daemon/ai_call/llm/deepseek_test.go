package llm

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestClient_ChatStream_SSE(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/chat/completions" {
			t.Errorf("path %s", r.URL.Path)
		}
		if got := r.Header.Get("Authorization"); got != "Bearer test-key" {
			t.Errorf("auth %q", got)
		}
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["stream"] != true {
			t.Errorf("stream=%v", body["stream"])
		}
		w.Header().Set("Content-Type", "text/event-stream")
		flusher, _ := w.(http.Flusher)
		chunks := []string{
			`data: {"choices":[{"delta":{"content":"你好"}}]}`,
			`data: {"choices":[{"delta":{"content":"。世界"}}]}`,
			`data: {"choices":[{"delta":{"content":"！"}}]}`,
			`data: [DONE]`,
		}
		for _, c := range chunks {
			_, _ = w.Write([]byte(c + "\n\n"))
			if flusher != nil {
				flusher.Flush()
			}
		}
	}))
	defer srv.Close()

	var sentences []string
	c := Client{
		BaseURL: srv.URL,
		APIKey:  "test-key",
		Model:   "deepseek-chat",
		HTTP:    srv.Client(),
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	full, err := c.ChatStream(ctx, []Message{
		{Role: "system", Content: "sys"},
		{Role: "user", Content: "hi"},
	}, func(s string) {
		sentences = append(sentences, s)
	})
	if err != nil {
		t.Fatal(err)
	}
	if full != "你好。世界！" {
		t.Fatalf("full=%q", full)
	}
	want := []string{"你好。", "世界！"}
	if len(sentences) != len(want) {
		t.Fatalf("sentences=%v want=%v", sentences, want)
	}
	for i := range want {
		if sentences[i] != want[i] {
			t.Fatalf("[%d] %q != %q", i, sentences[i], want[i])
		}
	}
}

func TestClient_ChatStream_HTTPError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, `{"error":{"message":"bad key"}}`, http.StatusUnauthorized)
	}))
	defer srv.Close()
	c := Client{BaseURL: srv.URL, APIKey: "x", Model: "deepseek-chat", HTTP: srv.Client()}
	_, err := c.ChatStream(context.Background(), []Message{{Role: "user", Content: "hi"}}, nil)
	if err == nil || !strings.Contains(err.Error(), "401") {
		t.Fatalf("err=%v", err)
	}
}

func TestLoadAPIKey_FromFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "key.txt")
	if err := os.WriteFile(path, []byte("  sk-abc\n"), 0600); err != nil {
		t.Fatal(err)
	}
	got, err := LoadAPIKey("", path)
	if err != nil {
		t.Fatal(err)
	}
	if got != "sk-abc" {
		t.Fatalf("got %q", got)
	}
}

func TestLoadAPIKey_EnvWins(t *testing.T) {
	got, err := LoadAPIKey("sk-env", "/nonexistent")
	if err != nil {
		t.Fatal(err)
	}
	if got != "sk-env" {
		t.Fatalf("got %q", got)
	}
}
