package llm

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	DefaultBaseURL = "https://api.deepseek.com"
	DefaultModel   = "deepseek-v4-flash"
)

// DefaultDNSServers: Go (CGO=0) on Android has no resolv.conf and falls back to
// [::1]:53 which refuses; pin public DNS so HTTPS works under su/root.
var DefaultDNSServers = []string{"223.5.5.5:53", "8.8.8.8:53"}

// androidCADirs hold PEM/DER system trust anchors used by Android.
var androidCADirs = []string{
	"/apex/com.android.conscrypt/cacerts",
	"/system/etc/security/cacerts",
}

// Message is one chat turn (OpenAI-compatible).
type Message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

// Client talks to DeepSeek (OpenAI-compatible) chat completions with SSE streaming.
type Client struct {
	BaseURL    string
	APIKey     string
	Model      string
	HTTP       *http.Client
	DNSServers []string // host:port; empty → DefaultDNSServers
}

// NewHTTPClient returns a client with Android-safe DNS + system CA roots.
func NewHTTPClient(dnsServers []string) *http.Client {
	if len(dnsServers) == 0 {
		dnsServers = DefaultDNSServers
	}
	servers := append([]string(nil), dnsServers...)
	resolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var last error
			d := net.Dialer{Timeout: 3 * time.Second}
			for _, s := range servers {
				conn, err := d.DialContext(ctx, "udp", s)
				if err == nil {
					return conn, nil
				}
				last = err
			}
			return nil, fmt.Errorf("dns dial: %w", last)
		},
	}
	dialer := &net.Dialer{
		Timeout:   20 * time.Second,
		KeepAlive: 30 * time.Second,
		Resolver:  resolver,
	}
	tlsCfg := &tls.Config{
		MinVersion: tls.VersionTLS12,
		RootCAs:    loadSystemRootCAs(),
	}
	return &http.Client{
		Transport: &http.Transport{
			Proxy:                 http.ProxyFromEnvironment,
			DialContext:           dialer.DialContext,
			ForceAttemptHTTP2:     true,
			MaxIdleConns:          4,
			IdleConnTimeout:       90 * time.Second,
			TLSHandshakeTimeout:   15 * time.Second,
			ExpectContinueTimeout: 1 * time.Second,
			TLSClientConfig:       tlsCfg,
		},
	}
}

func loadSystemRootCAs() *x509.CertPool {
	pool, err := x509.SystemCertPool()
	if err != nil || pool == nil {
		pool = x509.NewCertPool()
	}
	loadRootCAsFromInto(pool, androidCADirs)
	return pool
}

func loadRootCAsFrom(dirs []string) *x509.CertPool {
	pool := x509.NewCertPool()
	loadRootCAsFromInto(pool, dirs)
	return pool
}

func loadRootCAsFromInto(pool *x509.CertPool, dirs []string) {
	for _, dir := range dirs {
		ents, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, e := range ents {
			if e.IsDir() {
				continue
			}
			b, err := os.ReadFile(filepath.Join(dir, e.Name()))
			if err != nil || len(b) == 0 {
				continue
			}
			if !pool.AppendCertsFromPEM(b) {
				// Android cacerts are often raw DER.
				if cert, err := x509.ParseCertificate(b); err == nil {
					pool.AddCert(cert)
				}
			}
		}
	}
}

func (c *Client) httpClient() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	c.HTTP = NewHTTPClient(c.DNSServers)
	return c.HTTP
}

func (c *Client) base() string {
	b := strings.TrimRight(strings.TrimSpace(c.BaseURL), "/")
	if b == "" {
		b = DefaultBaseURL
	}
	return b
}

func (c *Client) model() string {
	m := strings.TrimSpace(c.Model)
	if m == "" {
		return DefaultModel
	}
	return m
}

// ChatStream posts a streaming chat request. onSentence receives punctuated
// sentences as they complete (and any remainder at stream end). Returns full text.
func (c *Client) ChatStream(ctx context.Context, msgs []Message, onSentence func(string)) (string, error) {
	if strings.TrimSpace(c.APIKey) == "" {
		return "", fmt.Errorf("deepseek: empty API key")
	}
	body, err := json.Marshal(map[string]any{
		"model":    c.model(),
		"messages": msgs,
		"stream":   true,
		// V4 defaults to thinking; disable for low-latency phone TTS.
		"thinking": map[string]string{"type": "disabled"},
	})
	if err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base()+"/chat/completions", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.APIKey)
	req.Header.Set("Accept", "text/event-stream")

	resp, err := c.httpClient().Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		return "", fmt.Errorf("deepseek: HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}

	var full strings.Builder
	sb := NewSentenceBuf(onSentence)
	sc := bufio.NewScanner(resp.Body)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for sc.Scan() {
		line := sc.Text()
		if !strings.HasPrefix(line, "data:") {
			continue
		}
		payload := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
		if payload == "" || payload == "[DONE]" {
			continue
		}
		var chunk struct {
			Choices []struct {
				Delta struct {
					Content string `json:"content"`
				} `json:"delta"`
			} `json:"choices"`
		}
		if err := json.Unmarshal([]byte(payload), &chunk); err != nil {
			continue
		}
		if len(chunk.Choices) == 0 {
			continue
		}
		delta := chunk.Choices[0].Delta.Content
		if delta == "" {
			continue
		}
		full.WriteString(delta)
		sb.Push(delta)
	}
	if err := sc.Err(); err != nil {
		return full.String(), err
	}
	sb.Flush()
	return full.String(), nil
}

// Chat is a non-streaming completion (used for end-of-call summary).
func (c *Client) Chat(ctx context.Context, msgs []Message) (string, error) {
	if strings.TrimSpace(c.APIKey) == "" {
		return "", fmt.Errorf("deepseek: empty API key")
	}
	body, err := json.Marshal(map[string]any{
		"model":    c.model(),
		"messages": msgs,
		"stream":   false,
		"thinking": map[string]string{"type": "disabled"},
	})
	if err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base()+"/chat/completions", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.APIKey)

	resp, err := c.httpClient().Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("deepseek: HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	var parsed struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return "", fmt.Errorf("deepseek: decode: %w", err)
	}
	if len(parsed.Choices) == 0 {
		return "", fmt.Errorf("deepseek: empty choices")
	}
	return strings.TrimSpace(parsed.Choices[0].Message.Content), nil
}

// LoadAPIKey prefers envKey (already resolved), else reads keyFile (trimmed).
func LoadAPIKey(envKey, keyFile string) (string, error) {
	if k := strings.TrimSpace(envKey); k != "" {
		return k, nil
	}
	path := strings.TrimSpace(keyFile)
	if path == "" {
		return "", fmt.Errorf("deepseek: no API key (set DEEPSEEK_API_KEY or key file)")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("deepseek: read key file: %w", err)
	}
	k := strings.TrimSpace(string(b))
	if k == "" {
		return "", fmt.Errorf("deepseek: key file empty: %s", path)
	}
	return k, nil
}

// DefaultSystemPrompt keeps replies short for phone TTS.
const DefaultSystemPrompt = `你是电话助理。用简体中文简短回答对方，每句尽量短，适合语音播报。不要用 Markdown、列表或表情。结合本通电话已有对话上下文回应，不要复述对方原话。`
