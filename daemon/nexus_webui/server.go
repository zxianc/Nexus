package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"

	"nexus.nexuscfg"
)

type Server struct {
	ConfigPath    string
	LogDir        string
	RestartScript string
	LookupPID     func(name string) (int, bool)
	RunRestart    func() error
	mu            sync.Mutex
}

func NewServer(cfgPath, logDir, restartScript string) http.Handler {
	s := &Server{
		ConfigPath:    cfgPath,
		LogDir:        logDir,
		RestartScript: restartScript,
		LookupPID:     lookupPID,
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/config", s.handleConfig)
	mux.HandleFunc("/api/status", s.handleStatus)
	mux.HandleFunc("/api/logs", s.handleLogs)
	mux.HandleFunc("/api/restart", s.handleRestart)
	return mux
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func (s *Server) loadConfig() (nexuscfg.Config, error) {
	cfg, err := nexuscfg.Load(s.ConfigPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nexuscfg.Default(), nil
		}
		return nexuscfg.Config{}, err
	}
	return cfg, nil
}

func (s *Server) handleConfig(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		cfg, err := s.loadConfig()
		if err != nil {
			writeJSON(w, 500, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		writeJSON(w, 200, map[string]any{"ok": true, "config": nexuscfg.Redact(cfg)})
	case http.MethodPut:
		s.handlePutConfig(w, r)
	default:
		writeJSON(w, 405, map[string]any{"ok": false, "error": "method not allowed"})
	}
}

func (s *Server) handlePutConfig(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	body, err := readBody(r, 1<<20)
	if err != nil {
		writeJSON(w, 400, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	cur, err := s.loadConfig()
	if err != nil {
		writeJSON(w, 500, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	next, engRestart, err := nexuscfg.ApplyPUT(cur, body)
	if err != nil {
		writeJSON(w, 400, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	if err := nexuscfg.SaveAtomic(s.ConfigPath, next); err != nil {
		writeJSON(w, 500, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	restarted := []string{}
	if s.RestartScript != "" || s.RunRestart != nil {
		if err := s.writeCallstackEnv(next, engRestart); err != nil {
			writeJSON(w, 500, map[string]any{"ok": false, "error": "env: " + err.Error()})
			return
		}
		if err := s.runRestart(); err != nil {
			writeJSON(w, 500, map[string]any{"ok": false, "error": "restart: " + err.Error()})
			return
		}
		if engRestart {
			restarted = []string{"nexus_engine", "ai_call"}
		} else {
			restarted = []string{"ai_call"}
		}
	}
	portChanged := cur.WebUI.Port != next.WebUI.Port
	writeJSON(w, 200, map[string]any{
		"ok":                     true,
		"restarted":              restarted,
		"webui_restart_required": portChanged,
		"status":                 s.statusMap(),
		"config":                 nexuscfg.Redact(next),
	})
}

func (s *Server) handleRestart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"ok": false, "error": "method not allowed"})
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	cfg, err := s.loadConfig()
	if err != nil {
		writeJSON(w, 500, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	if s.RestartScript == "" {
		writeJSON(w, 500, map[string]any{"ok": false, "error": "restart script not configured"})
		return
	}
	if err := s.writeCallstackEnv(cfg, true); err != nil {
		writeJSON(w, 500, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	if err := s.runRestart(); err != nil {
		writeJSON(w, 500, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{
		"ok":        true,
		"restarted": []string{"nexus_engine", "ai_call"},
		"status":    s.statusMap(),
	})
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, map[string]any{"ok": false, "error": "method not allowed"})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "processes": s.statusMap()})
}

func (s *Server) statusMap() map[string]any {
	names := []string{"ai_call", "nexus_engine", "nexus_webui"}
	out := map[string]any{}
	look := s.LookupPID
	if look == nil {
		look = lookupPID
	}
	for _, n := range names {
		pid, ok := look(n)
		entry := map[string]any{"running": ok}
		if ok {
			entry["pid"] = pid
		}
		out[n] = entry
	}
	return out
}

var logFiles = map[string]string{
	"ai_call":       "ai_call.log",
	"nexus_engine":  "nexus_engine.log",
	"nexus_runtime": "nexus_runtime.log",
	"nexus_webui":   "nexus_webui.log",
}

func (s *Server) handleLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, map[string]any{"ok": false, "error": "method not allowed"})
		return
	}
	name := r.URL.Query().Get("name")
	file, ok := logFiles[name]
	if !ok {
		writeJSON(w, 400, map[string]any{"ok": false, "error": "unknown log name"})
		return
	}
	lines := 80
	if v := r.URL.Query().Get("lines"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 {
			writeJSON(w, 400, map[string]any{"ok": false, "error": "bad lines"})
			return
		}
		if n > 500 {
			n = 500
		}
		lines = n
	}
	path := filepath.Join(s.LogDir, file)
	text, err := tailFile(path, lines)
	if err != nil {
		if os.IsNotExist(err) {
			writeJSON(w, 200, map[string]any{"ok": true, "name": name, "lines": ""})
			return
		}
		writeJSON(w, 500, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "name": name, "lines": text})
}

func tailFile(path string, n int) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	var ring []string
	sc := bufio.NewScanner(f)
	buf := make([]byte, 0, 64*1024)
	sc.Buffer(buf, 1024*1024)
	for sc.Scan() {
		ring = append(ring, sc.Text())
		if len(ring) > n {
			ring = ring[len(ring)-n:]
		}
	}
	if err := sc.Err(); err != nil {
		return "", err
	}
	return strings.Join(ring, "\n"), nil
}

func lookupPID(name string) (int, bool) {
	out, err := exec.Command("pidof", name).Output()
	if err != nil {
		return 0, false
	}
	fields := strings.Fields(string(out))
	if len(fields) == 0 {
		return 0, false
	}
	pid, err := strconv.Atoi(fields[0])
	if err != nil {
		return 0, false
	}
	return pid, true
}

func readBody(r *http.Request, max int64) ([]byte, error) {
	defer r.Body.Close()
	b, err := io.ReadAll(io.LimitReader(r.Body, max+1))
	if err != nil {
		return nil, err
	}
	if int64(len(b)) > max {
		return nil, fmt.Errorf("body too large")
	}
	return b, nil
}

func (s *Server) writeCallstackEnv(cfg nexuscfg.Config, engineRestart bool) error {
	dir := filepath.Join(filepath.Dir(s.ConfigPath), "run")
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	llm := "0"
	if cfg.LLM.Enabled {
		llm = "1"
	}
	barge := "0"
	if cfg.LLM.BargeIn {
		barge = "1"
	}
	beep := "0"
	if cfg.TTS.BeepPrefix {
		beep = "1"
	}
	eng := "0"
	if engineRestart {
		eng = "1"
	}
	content := strings.Join([]string{
		"STT_LANG=" + shellQuote(cfg.STT.Lang),
		"LLM=" + llm,
		"LLM_BARGE_IN=" + barge,
		"DEEPSEEK_API_KEY=" + shellQuote(cfg.LLM.APIKey),
		"DEEPSEEK_MODEL=" + shellQuote(cfg.LLM.Model),
		"ENGINE_SOCK=" + shellQuote(cfg.Paths.EngineSock),
		"STT_MODEL_DIR=" + shellQuote(cfg.Paths.STTModel),
		"TTS_MODEL_DIR=" + shellQuote(cfg.Paths.TTSModel),
		"CALL_ARCHIVE_DIR=" + shellQuote(cfg.Paths.ArchiveDir),
		"TX_BEEP_PREFIX=" + beep,
		"ENGINE_RESTART=" + eng,
		"",
	}, "\n")
	path := filepath.Join(dir, "callstack.env")
	return os.WriteFile(path, []byte(content), 0600)
}

func shellQuote(s string) string {
	// Values written for `source`; avoid spaces/newlines breaking.
	s = strings.ReplaceAll(s, "\n", "")
	s = strings.ReplaceAll(s, "\r", "")
	if strings.ContainsAny(s, " \t\"'\\$`") {
		return "'" + strings.ReplaceAll(s, "'", "'\\''") + "'"
	}
	return s
}

func (s *Server) runRestart() error {
	if s.RunRestart != nil {
		return s.RunRestart()
	}
	if s.RestartScript == "" {
		return nil
	}
	cmd := exec.Command("sh", s.RestartScript)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}
