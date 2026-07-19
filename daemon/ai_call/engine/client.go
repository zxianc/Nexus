package engine

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"sync/atomic"
	"time"
)

// Client talks to nexus_engine over a Unix domain socket (line JSON).
type Client struct {
	Sock string
	id   atomic.Int64
}

type req struct {
	ID   int64  `json:"id"`
	Op   string `json:"op"`
	Wav  string `json:"wav,omitempty"`
	Text string `json:"text,omitempty"`
	Sid  int    `json:"sid,omitempty"`
}

type resp struct {
	ID   int64  `json:"id"`
	OK   bool   `json:"ok"`
	Err  string `json:"err,omitempty"`
	Text string `json:"text,omitempty"`
	Wav  string `json:"wav,omitempty"`
	Rate int    `json:"rate,omitempty"`
	Ms   int64  `json:"ms,omitempty"`
}

func (c *Client) nextID() int64 { return c.id.Add(1) }

func (c *Client) call(ctx context.Context, r req) (*resp, error) {
	d := net.Dialer{}
	conn, err := d.DialContext(ctx, "unix", c.Sock)
	if err != nil {
		return nil, fmt.Errorf("engine dial: %w", err)
	}
	defer conn.Close()

	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}

	raw, err := json.Marshal(r)
	if err != nil {
		return nil, err
	}
	raw = append(raw, '\n')
	if _, err := conn.Write(raw); err != nil {
		return nil, fmt.Errorf("engine write: %w", err)
	}

	rd := bufio.NewReader(conn)
	line, err := rd.ReadBytes('\n')
	if err != nil {
		return nil, fmt.Errorf("engine read: %w", err)
	}
	var out resp
	if err := json.Unmarshal(line, &out); err != nil {
		return nil, fmt.Errorf("engine json: %w line=%q", err, truncate(string(line), 200))
	}
	if !out.OK {
		errMsg := out.Err
		if errMsg == "" {
			errMsg = "unknown"
		}
		return &out, fmt.Errorf("engine: %s", errMsg)
	}
	return &out, nil
}

func (c *Client) Ping(ctx context.Context) error {
	_, err := c.call(ctx, req{ID: c.nextID(), Op: "ping"})
	return err
}

func (c *Client) STT(ctx context.Context, wavPath string) (string, error) {
	out, err := c.call(ctx, req{ID: c.nextID(), Op: "stt", Wav: wavPath})
	if err != nil {
		return "", err
	}
	return out.Text, nil
}

func (c *Client) TTS(ctx context.Context, text, wavPath string, sid int) (rate int, err error) {
	out, err := c.call(ctx, req{ID: c.nextID(), Op: "tts", Text: text, Wav: wavPath, Sid: sid})
	if err != nil {
		return 0, err
	}
	return out.Rate, nil
}

func WaitReady(ctx context.Context, sock string) error {
	c := &Client{Sock: sock}
	for {
		if err := ctx.Err(); err != nil {
			return fmt.Errorf("engine not ready: %w", err)
		}
		tctx, cancel := context.WithTimeout(ctx, 500*time.Millisecond)
		err := c.Ping(tctx)
		cancel()
		if err == nil {
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("engine not ready: %w", ctx.Err())
		case <-time.After(200 * time.Millisecond):
		}
	}
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}
