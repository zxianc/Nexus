package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"nexus.nexuscfg"
)

func main() {
	cfgPath := flag.String("config", envOr("NEXUS_CONFIG", nexuscfg.DefaultPath), "config.json path")
	addr := flag.String("addr", "", "listen addr (default 127.0.0.1:<webui.port>)")
	logDir := flag.String("log-dir", envOr("NEXUS_LOG_DIR", "/data/vendor/ai_hook"), "log directory")
	restartScript := flag.String("restart-script",
		envOr("NEXUS_RESTART_SCRIPT", "/data/adb/modules/nexus_runtime/scripts/restart_callstack.sh"),
		"callstack restart script")
	flag.Parse()

	cfg, err := nexuscfg.Load(*cfgPath)
	if err != nil {
		if os.IsNotExist(err) {
			cfg = nexuscfg.Default()
			if err := nexuscfg.SaveAtomic(*cfgPath, cfg); err != nil {
				log.Printf("create default config: %v", err)
			}
		} else {
			log.Fatalf("config: %v", err)
		}
	}
	nexusDir := filepath.Dir(*cfgPath)
	if migrated, changed, merr := nexuscfg.Migrate(nexusDir, cfg); merr != nil {
		log.Printf("migrate: %v", merr)
	} else if changed {
		cfg = migrated
		if err := nexuscfg.SaveAtomic(*cfgPath, cfg); err != nil {
			log.Printf("migrate save: %v", err)
		} else {
			log.Printf("migrated api_key from secrets/deepseek.key")
		}
	}

	listen := strings.TrimSpace(*addr)
	if listen == "" {
		port := cfg.WebUI.Port
		if port <= 0 {
			port = 8787
		}
		listen = fmt.Sprintf("127.0.0.1:%d", port)
	}
	if !strings.HasPrefix(listen, "127.0.0.1:") && !strings.HasPrefix(listen, "localhost:") {
		log.Fatalf("refusing non-localhost addr %q", listen)
	}

	h := NewServer(*cfgPath, *logDir, *restartScript)
	// Static UI attached in webui.go when present
	h = withStatic(h)

	ln, err := net.Listen("tcp", listen)
	if err != nil {
		log.Fatalf("listen %s: %v", listen, err)
	}
	log.Printf("nexus_webui listen=%s config=%s", listen, *cfgPath)
	log.Fatal(http.Serve(ln, h))
}

func envOr(key, def string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return def
}
