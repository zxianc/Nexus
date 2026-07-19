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
	poll := flag.Duration("poll", 800*time.Millisecond, "RINGING poll interval")
	flag.Parse()

	if tz := strings.TrimSpace(os.Getenv("TZ")); tz == "" {
		// best-effort; service.sh usually sets TZ
		_ = os.Setenv("TZ", "Asia/Shanghai")
	}

	log.SetFlags(log.LstdFlags | log.Lmsgprefix)
	log.SetPrefix("callpolicy ")

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	ad := &ShellAdapter{}
	eng := &Engine{
		ConfigPath: *cfgPath,
		Interval:   *poll,
		Watch:      &TelecomWatcher{Interval: *poll},
		Adapter:    ad,
	}
	log.Printf("start config=%s poll=%s", *cfgPath, *poll)
	ensureAnswerAppOps()
	if err := eng.Run(ctx); err != nil && ctx.Err() == nil {
		log.Fatalf("run: %v", err)
	}
	log.Printf("exit")
}

func envOr(k, def string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return def
}
