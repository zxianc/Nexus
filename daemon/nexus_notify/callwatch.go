package main

import (
	"context"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"nexus.nexuscfg"
)

// CallWatcher consumes `*.txt.notify` sidecars next to call archives.
type CallWatcher struct {
	ArchiveDir string
	Interval   time.Duration
	Load       func() (nexuscfg.Config, error)
	Client     *WeComClient

	mu       sync.Mutex
	backoff  map[string]time.Time // marker path → next attempt
}

func (w *CallWatcher) Run(ctx context.Context) {
	if w.Interval <= 0 {
		w.Interval = 2 * time.Second
	}
	if w.backoff == nil {
		w.backoff = map[string]time.Time{}
	}
	t := time.NewTicker(w.Interval)
	defer t.Stop()
	for {
		w.scanOnce(ctx)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func (w *CallWatcher) scanOnce(ctx context.Context) {
	cfg, err := w.Load()
	if err != nil {
		log.Printf("call watch: config: %v", err)
		return
	}
	if !cfg.Notify.Enabled || !cfg.Notify.Call.Enabled {
		return
	}
	dir := w.ArchiveDir
	if dir == "" {
		dir = cfg.Paths.ArchiveDir
	}
	if dir == "" {
		dir = "/data/vendor/ai_hook/calls"
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		log.Printf("call watch: readdir %s: %v", dir, err)
		return
	}
	var markers []string
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		if !strings.HasSuffix(name, ".txt.notify") {
			continue
		}
		markers = append(markers, filepath.Join(dir, name))
	}
	sort.Strings(markers)
	now := time.Now()
	for _, marker := range markers {
		w.mu.Lock()
		until, delayed := w.backoff[marker]
		w.mu.Unlock()
		if delayed && now.Before(until) {
			continue
		}
		archive := strings.TrimSuffix(marker, ".notify")
		if err := w.process(ctx, cfg, archive, marker); err != nil {
			log.Printf("call notify %s: %v", filepath.Base(archive), err)
			// Back off this marker so one bad/rate-limited file doesn't block others forever.
			w.mu.Lock()
			w.backoff[marker] = time.Now().Add(15 * time.Second)
			w.mu.Unlock()
			continue
		}
		w.mu.Lock()
		delete(w.backoff, marker)
		w.mu.Unlock()
	}
}

func (w *CallWatcher) process(ctx context.Context, cfg nexuscfg.Config, archive, marker string) error {
	body, err := os.ReadFile(archive)
	if err != nil {
		_ = os.Remove(marker)
		return err
	}
	tm, sum, tr, peer, local, pol := ParseCallArchive(string(body))
	if local == "" {
		local = "未知"
	}
	msg := FormatCallMessage(CallNotify{
		Time:       tm,
		Peer:       peer,
		Local:      local,
		Policy:     pol,
		Summary:    sum,
		Transcript: tr,
		Archive:    archive,
		MaxChars:   cfg.Notify.Call.MaxTranscriptChars,
	})
	cctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	if err := w.Client.SendWithRetry(cctx, cfg.Notify.WeCom, cfg.Notify.Channel, msg, 2); err != nil {
		return err
	}
	if err := os.Remove(marker); err != nil {
		log.Printf("call notify: remove marker %s: %v", marker, err)
	} else {
		log.Printf("call notify ok archive=%s", filepath.Base(archive))
	}
	return nil
}
