package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"nexus.nexuscfg"
)

func TestWebhookURLReject(t *testing.T) {
	c := &WeComClient{}
	if err := c.sendWebhookText(t.Context(), "", "x"); err == nil {
		t.Fatal("empty")
	}
	if err := c.sendWebhookText(t.Context(), "https://evil.example/hook", "x"); err == nil || !strings.Contains(err.Error(), "qyapi") {
		t.Fatalf("got %v", err)
	}
}

func TestTruncateWebhookText(t *testing.T) {
	long := strings.Repeat("a", 3000)
	out := truncateWebhookText(long, 2000)
	if len(out) > 2010 || !strings.Contains(out, "截断") {
		t.Fatalf("len=%d tail=%q", len(out), out[len(out)-20:])
	}
	if truncateWebhookText("短", 2000) != "短" {
		t.Fatal("short")
	}
}

func TestSendWebhookTextOK(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(b), `"msgtype":"text"`) || !strings.Contains(string(b), "hello") {
			t.Errorf("body=%s", b)
		}
		_, _ = w.Write([]byte(`{"errcode":0,"errmsg":"ok"}`))
	}))
	defer srv.Close()

	// Rewrite qyapi host to httptest for unit test.
	c := &WeComClient{HTTP: &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		req.URL.Scheme = "http"
		req.URL.Host = strings.TrimPrefix(srv.URL, "http://")
		return http.DefaultTransport.RoundTrip(req)
	})}}
	err := c.sendWebhookText(t.Context(), "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test", "hello")
	if err != nil {
		t.Fatal(err)
	}
}

func TestSendTextWebhookChannel(t *testing.T) {
	c := &WeComClient{}
	err := c.SendText(t.Context(), nexuscfg.NotifyWeCom{WebhookURL: "https://evil.example"}, "wecom_webhook", "hi")
	if err == nil || !strings.Contains(err.Error(), "qyapi") {
		t.Fatalf("%v", err)
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
