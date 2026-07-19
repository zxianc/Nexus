package main

import (
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	_ "time/tzdata"

	"nexus.nexuscfg"
)

func main() {
	cfgPath := flag.String("config", envOr("NEXUS_CONFIG", nexuscfg.DefaultPath), "config.json path")
	archiveDir := flag.String("archive-dir", "", "call archive dir (default from config)")
	cursorPath := flag.String("sms-cursor", "/data/adb/nexus/run/notify_sms_cursor", "SMS last-_id cursor")
	flag.Parse()

	if tz := strings.TrimSpace(os.Getenv("TZ")); tz == "" {
		_ = os.Setenv("TZ", "Asia/Shanghai")
	}

	log.SetFlags(log.LstdFlags | log.Lmsgprefix)
	log.SetPrefix("notify ")

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	load := func() (nexuscfg.Config, error) {
		return nexuscfg.Load(*cfgPath)
	}

	cfg, err := load()
	if err != nil {
		log.Printf("config load (will retry): %v", err)
	} else {
		log.Printf("start enabled=%v channel=%s call=%v sms=%v",
			cfg.Notify.Enabled, cfg.Notify.Channel, cfg.Notify.Call.Enabled, cfg.Notify.SMS.Enabled)
	}

	client := &WeComClient{HTTP: newAndroidHTTPClient()}
	dir := *archiveDir
	if dir == "" && err == nil {
		dir = cfg.Paths.ArchiveDir
	}
	if dir == "" {
		dir = "/data/vendor/ai_hook/calls"
	}

	go (&CallWatcher{
		ArchiveDir: dir,
		Interval:   2 * time.Second,
		Load:       load,
		Client:     client,
	}).Run(ctx)

	smsInterval := 3 * time.Second
	if err == nil && cfg.Notify.SMS.PollMs > 0 {
		smsInterval = time.Duration(cfg.Notify.SMS.PollMs) * time.Millisecond
	}
	go (&SMSPoller{
		CursorPath: *cursorPath,
		Interval:   smsInterval,
		Load:       load,
		Client:     client,
	}).Run(ctx)

	<-ctx.Done()
	log.Printf("exit")
}

func envOr(k, def string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return def
}
