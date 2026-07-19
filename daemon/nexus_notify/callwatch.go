package main

import (
	"context"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"nexus.nexuscfg"
)

// CallWatcher consumes `*.txt.notify` sidecars next to call archives.
type CallWatcher struct {
	ArchiveDir string
	Interval   time.Duration
	Load       func() (nexuscfg.Config, error)
	Client     *WeComClient
}

func (w *CallWatcher) Run(ctx context.Context) {
	if w.Interval <= 0 {
		w.Interval = 2 * time.Second
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
		return
	}
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		if !strings.HasSuffix(name, ".txt.notify") {
			continue
		}
		marker := filepath.Join(dir, name)
		archive := strings.TrimSuffix(marker, ".notify")
		if err := w.process(ctx, cfg, archive, marker); err != nil {
			log.Printf("call notify %s: %v", filepath.Base(archive), err)
		}
	}
}

func (w *CallWatcher) process(ctx context.Context, cfg nexuscfg.Config, archive, marker string) error {
	body, err := os.ReadFile(archive)
	if err != nil {
		// Archive missing: drop stale marker
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
	cctx, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	if err := w.Client.SendWithRetry(cctx, cfg.Notify.WeCom, cfg.Notify.Channel, msg, 3); err != nil {
		return err
	}
	if err := os.Remove(marker); err != nil {
		log.Printf("call notify: remove marker %s: %v", marker, err)
	} else {
		log.Printf("call notify ok archive=%s", filepath.Base(archive))
	}
	return nil
}
