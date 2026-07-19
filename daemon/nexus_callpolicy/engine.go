package main

import (
	"context"
	"log"
	"sync"
	"time"

	"nexus.nexuscfg"
)

// Incoming is one ringing call observation.
type Incoming struct {
	Key    string // dedupe key
	Slot   int
	Peer   string
	Local  string
	RawHint string
}

type Watcher interface {
	Poll(ctx context.Context) ([]Incoming, error)
}

type CallAdapter interface {
	Answer(ctx context.Context, slot int) error
	Reject(ctx context.Context, slot int) error
}

type Engine struct {
	ConfigPath string
	Interval   time.Duration
	Watch      Watcher
	Adapter    CallAdapter

	mu      sync.Mutex
	handled map[string]time.Time
}

func (e *Engine) Run(ctx context.Context) error {
	e.handled = map[string]time.Time{}
	iv := e.Interval
	if iv <= 0 {
		iv = 800 * time.Millisecond
	}
	t := time.NewTicker(iv)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-t.C:
			e.tick(ctx)
		}
	}
}

func (e *Engine) tick(ctx context.Context) {
	cfg, err := nexuscfg.Load(e.ConfigPath)
	if err != nil {
		cfg = nexuscfg.Default()
	}
	list, err := e.Watch.Poll(ctx)
	if err != nil {
		log.Printf("watch: %v", err)
		return
	}
	now := time.Now()
	e.mu.Lock()
	if e.handled == nil {
		e.handled = map[string]time.Time{}
	}
	for k, ts := range e.handled {
		if now.Sub(ts) > 2*time.Minute {
			delete(e.handled, k)
		}
	}
	e.mu.Unlock()

	for _, in := range list {
		key := in.Key
		if key == "" {
			key = in.Peer + "|" + itoa(in.Slot)
		}
		e.mu.Lock()
		if _, ok := e.handled[key]; ok {
			e.mu.Unlock()
			continue
		}
		e.handled[key] = now
		e.mu.Unlock()

		pol := nexuscfg.PolicyForSlot(cfg, in.Slot)
		log.Printf("ringing slot=%d peer=%q local=%q policy=%s hint=%q",
			in.Slot, in.Peer, in.Local, pol, in.RawHint)
		switch pol {
		case nexuscfg.PolicyHuman:
			// no-op
		case nexuscfg.PolicyReject:
			if err := e.Adapter.Reject(ctx, in.Slot); err != nil {
				log.Printf("reject: %v", err)
			} else {
				log.Printf("reject ok slot=%d", in.Slot)
			}
		case nexuscfg.PolicyAI:
			if err := e.Adapter.Answer(ctx, in.Slot); err != nil {
				log.Printf("answer: %v", err)
			} else {
				log.Printf("answer ok (ai) slot=%d", in.Slot)
			}
		}
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [16]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}
