package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"nexus.nexuscfg"
)

const wecomAPI = "https://qyapi.weixin.qq.com"

// WeComClient sends text to an external contact (or member touser fallback).
type WeComClient struct {
	HTTP *http.Client

	mu      sync.Mutex
	token   string
	expires time.Time
}

func (c *WeComClient) httpClient() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	c.HTTP = newAndroidHTTPClient()
	return c.HTTP
}

type tokenResp struct {
	ErrCode     int    `json:"errcode"`
	ErrMsg      string `json:"errmsg"`
	AccessToken string `json:"access_token"`
	ExpiresIn   int    `json:"expires_in"`
}

func (c *WeComClient) AccessToken(ctx context.Context, corpID, secret string) (string, error) {
	corpID = strings.TrimSpace(corpID)
	secret = strings.TrimSpace(secret)
	if corpID == "" || secret == "" {
		return "", fmt.Errorf("wecom: missing corp_id/secret")
	}
	c.mu.Lock()
	if c.token != "" && time.Now().Before(c.expires) {
		tok := c.token
		c.mu.Unlock()
		return tok, nil
	}
	c.mu.Unlock()

	u := fmt.Sprintf("%s/cgi-bin/gettoken?corpid=%s&corpsecret=%s", wecomAPI, corpID, secret)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return "", err
	}
	res, err := c.httpClient().Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	var tr tokenResp
	if err := json.Unmarshal(body, &tr); err != nil {
		return "", fmt.Errorf("wecom token json: %w body=%s", err, truncateASCII(string(body), 200))
	}
	if tr.ErrCode != 0 || tr.AccessToken == "" {
		return "", fmt.Errorf("wecom token: %d %s", tr.ErrCode, tr.ErrMsg)
	}
	exp := tr.ExpiresIn
	if exp <= 0 {
		exp = 7200
	}
	c.mu.Lock()
	c.token = tr.AccessToken
	c.expires = time.Now().Add(time.Duration(exp-120) * time.Second)
	tok := c.token
	c.mu.Unlock()
	return tok, nil
}

type apiResp struct {
	ErrCode int    `json:"errcode"`
	ErrMsg  string `json:"errmsg"`
}

// SendText delivers content via configured channel.
func (c *WeComClient) SendText(ctx context.Context, cfg nexuscfg.NotifyWeCom, channel, content string) error {
	content = strings.TrimSpace(content)
	if content == "" {
		return fmt.Errorf("wecom: empty content")
	}
	switch strings.ToLower(strings.TrimSpace(channel)) {
	case "wecom_webhook":
		return c.sendWebhookText(ctx, cfg.WebhookURL, content)
	default:
		// Prefer app message to member if touser set (reliable for personal delivery via work app).
		if strings.TrimSpace(cfg.ToUser) != "" && cfg.AgentID > 0 {
			if err := c.sendAppText(ctx, cfg, content); err == nil {
				return nil
			} else if strings.TrimSpace(cfg.ExternalUserID) == "" {
				return err
			}
			// fall through to external if both configured
		}
		if strings.TrimSpace(cfg.ExternalUserID) != "" {
			return c.sendExternalText(ctx, cfg, content)
		}
		if strings.TrimSpace(cfg.ToUser) != "" && cfg.AgentID > 0 {
			return c.sendAppText(ctx, cfg, content)
		}
		return fmt.Errorf("wecom: need external_userid (+sender) or touser+agent_id")
	}
}

// sendWebhookText posts to a group robot webhook (no corp token / IP whitelist).
// WeCom text content limit is 2048 bytes; truncate with a short note if needed.
func (c *WeComClient) sendWebhookText(ctx context.Context, webhookURL, content string) error {
	u := strings.TrimSpace(webhookURL)
	if u == "" {
		return fmt.Errorf("wecom: webhook_url empty")
	}
	if !strings.HasPrefix(u, "https://qyapi.weixin.qq.com/cgi-bin/webhook/send") &&
		!strings.Contains(u, "qyapi.weixin.qq.com") {
		// Allow only WeCom webhook hosts to avoid accidental SSRF from config.
		return fmt.Errorf("wecom: webhook_url must be qyapi.weixin.qq.com")
	}
	content = truncateWebhookText(content, 2000)
	payload := map[string]any{
		"msgtype": "text",
		"text":    map[string]string{"content": content},
	}
	b, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := c.httpClient().Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	var ar apiResp
	if err := json.Unmarshal(body, &ar); err != nil {
		return fmt.Errorf("wecom webhook json: %w body=%s", err, truncateASCII(string(body), 200))
	}
	if ar.ErrCode != 0 {
		return fmt.Errorf("wecom webhook: %d %s", ar.ErrCode, ar.ErrMsg)
	}
	return nil
}

func truncateWebhookText(s string, maxBytes int) string {
	if maxBytes <= 0 || len(s) <= maxBytes {
		return s
	}
	// Keep valid UTF-8 prefix.
	for maxBytes > 0 && !utf8.ValidString(s[:maxBytes]) {
		maxBytes--
	}
	note := "\n…(已截断)"
	if maxBytes > len(note) {
		maxBytes -= len(note)
		for maxBytes > 0 && !utf8.ValidString(s[:maxBytes]) {
			maxBytes--
		}
		return s[:maxBytes] + note
	}
	return s[:maxBytes]
}

func (c *WeComClient) sendAppText(ctx context.Context, cfg nexuscfg.NotifyWeCom, content string) error {
	tok, err := c.AccessToken(ctx, cfg.CorpID, cfg.Secret)
	if err != nil {
		return err
	}
	payload := map[string]any{
		"touser":  strings.TrimSpace(cfg.ToUser),
		"msgtype": "text",
		"agentid": cfg.AgentID,
		"text":    map[string]string{"content": content},
	}
	return c.postJSON(ctx, "/cgi-bin/message/send?access_token="+tok, payload)
}

func (c *WeComClient) sendExternalText(ctx context.Context, cfg nexuscfg.NotifyWeCom, content string) error {
	tok, err := c.AccessToken(ctx, cfg.CorpID, cfg.Secret)
	if err != nil {
		return err
	}
	ext := strings.TrimSpace(cfg.ExternalUserID)
	sender := strings.TrimSpace(cfg.Sender)
	if sender == "" {
		return fmt.Errorf("wecom: external_userid requires sender (member userid)")
	}
	payload := map[string]any{
		"chat_type":       "single",
		"external_userid": []string{ext},
		"sender":          sender,
		"text":            map[string]string{"content": content},
	}
	return c.postJSON(ctx, "/cgi-bin/externalcontact/add_msg_template?access_token="+tok, payload)
}

func (c *WeComClient) postJSON(ctx context.Context, path string, payload any) error {
	b, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, wecomAPI+path, bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := c.httpClient().Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	var ar apiResp
	if err := json.Unmarshal(body, &ar); err != nil {
		return fmt.Errorf("wecom send json: %w body=%s", err, truncateASCII(string(body), 200))
	}
	if ar.ErrCode != 0 {
		c.invalidateTokenOnAuth(ar.ErrCode)
		return fmt.Errorf("wecom send: %d %s", ar.ErrCode, ar.ErrMsg)
	}
	return nil
}

func (c *WeComClient) invalidateTokenOnAuth(code int) {
	// 40014 invalid access_token, 42001 expired
	if code == 40014 || code == 42001 {
		c.mu.Lock()
		c.token = ""
		c.expires = time.Time{}
		c.mu.Unlock()
	}
}

func truncateASCII(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}

// SendWithRetry retries transient WeCom errors with exponential backoff.
func (c *WeComClient) SendWithRetry(ctx context.Context, cfg nexuscfg.NotifyWeCom, channel, content string, attempts int) error {
	if attempts <= 0 {
		attempts = 3
	}
	var last error
	backoff := 500 * time.Millisecond
	for i := 0; i < attempts; i++ {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		last = c.SendText(ctx, cfg, channel, content)
		if last == nil {
			return nil
		}
		if i+1 == attempts {
			break
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(backoff):
		}
		backoff *= 2
	}
	return last
}
