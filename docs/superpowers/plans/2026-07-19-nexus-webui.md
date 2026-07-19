# Nexus WebUI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a localhost-only `nexus_webui` in `nexus_runtime` that edits `/data/adb/nexus/config.json` (including API key), shows process status/logs, and restarts the call stack on save.

**Architecture:** Shared Go package `daemon/nexuscfg` owns schema load/save/migrate/redact. Separate binary `daemon/nexus_webui` serves static UI + REST on `127.0.0.1:8787`. Shell `restart_callstack.sh` starts/stops `nexus_engine`+`ai_call` without touching webui. `ai_call` and `service.sh` prefer `config.json` over legacy env/key file.

**Tech Stack:** Go 1.22 (`CGO_ENABLED=0`, `linux/arm64`), stdlib `net/http` + `embed`, Magisk `service.sh`, static HTML/CSS/JS (no npm).

**Spec:** [`docs/superpowers/specs/2026-07-19-nexus-webui-design.md`](../specs/2026-07-19-nexus-webui-design.md)

## Global Constraints

- Bind **only** `127.0.0.1` (default port **8787**)
- Config path **`/data/adb/nexus/config.json`**, mode **0600**, API key stored in JSON
- `GET /api/config` must **never** return full `api_key` (use `api_key_set` + `api_key_hint`)
- Restarting call stack must **not** `pkill nexus_webui`
- Do not commit arm64 binaries or model weights (extend `.gitignore` for `magisk_modules/**/bin/nexus_webui`)
- Prefer TDD for `nexuscfg` and handler merge logic; device smoke test at end

---

## File map

| Path | Responsibility |
|------|----------------|
| `daemon/nexuscfg/` | Shared config types, Default(), Load, SaveAtomic, Migrate, Redact, ApplyPUT |
| `daemon/nexus_webui/` | HTTP server, status/logs/restart, embed `web/` |
| `daemon/nexus_webui/web/` | Single-page settings UI |
| `magisk_modules/nexus_runtime/scripts/restart_callstack.sh` | Start/stop engine+ai_call from config.json |
| `magisk_modules/nexus_runtime/service.sh` | Boot: callstack then webui |
| `magisk_modules/nexus_runtime/config/config.default.json` | Schema-aligned defaults |
| `daemon/ai_call/main.go` (+ small helper) | Prefer config.json when present |
| Docs: `magisk_modules/README.md`, `daemon/ai_call/README.md`, `doc/dev_journal.md` | Operator notes |

---

### Task 1: Shared `nexuscfg` package

**Files:**
- Create: `daemon/nexuscfg/go.mod`
- Create: `daemon/nexuscfg/config.go`
- Create: `daemon/nexuscfg/config_test.go`
- Create: `daemon/nexuscfg/migrate.go`
- Create: `daemon/nexuscfg/migrate_test.go`

**Interfaces:**
- Produces:
  - `type Config struct` with nested `WebUI`, `LLM`, `STT`, `TTS`, `Paths` matching the spec schema
  - `func Default() Config`
  - `func Load(path string) (Config, error)` — missing file → `Default()` + `os.ErrNotExist` wrapped or separate `LoadOrDefault`
  - `func SaveAtomic(path string, cfg Config) error` — write temp + rename, `chmod 0600`
  - `func Redact(cfg Config) map[string]any` — JSON-ready view without full key
  - `func ApplyPUT(current Config, body []byte) (Config, bool, error)` — merge PUT; empty api_key keeps old unless `clear_api_key`; returns `(next, engineRestart, err)`
  - `func NeedsEngineRestart(before, after Config) bool`
  - `func Migrate(nexusDir string, cfg Config) (Config, bool, error)` — fill empty key from `secrets/deepseek.key`

- [ ] **Step 1: Init module and failing tests**

```bash
mkdir daemon/nexuscfg
cd daemon/nexuscfg
go mod init nexus.nexuscfg
```

Create `config_test.go`:

```go
package nexuscfg

import (
	"os"
	"path/filepath"
	"testing"
)

func TestSaveLoadRoundTrip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	cfg := Default()
	cfg.LLM.APIKey = "sk-test-key"
	cfg.LLM.BargeIn = true
	if err := SaveAtomic(path, cfg); err != nil {
		t.Fatal(err)
	}
	got, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if got.LLM.APIKey != "sk-test-key" || !got.LLM.BargeIn {
		t.Fatalf("got %+v", got.LLM)
	}
	fi, _ := os.Stat(path)
	if fi.Mode().Perm()&0077 != 0 {
		t.Fatalf("expected no group/other bits, mode=%v", fi.Mode())
	}
}

func TestRedactHidesAPIKey(t *testing.T) {
	cfg := Default()
	cfg.LLM.APIKey = "sk-abcdefgh"
	m := Redact(cfg)
	llm := m["llm"].(map[string]any)
	if _, ok := llm["api_key"]; ok {
		t.Fatal("api_key must not appear")
	}
	if llm["api_key_set"] != true {
		t.Fatal("api_key_set")
	}
	if llm["api_key_hint"] != "efgh" {
		t.Fatalf("hint=%v", llm["api_key_hint"])
	}
}

func TestApplyPUTKeepKeyWhenEmpty(t *testing.T) {
	cur := Default()
	cur.LLM.APIKey = "sk-keep"
	next, eng, err := ApplyPUT(cur, []byte(`{"llm":{"model":"x","api_key":""}}`))
	if err != nil {
		t.Fatal(err)
	}
	if next.LLM.APIKey != "sk-keep" || next.LLM.Model != "x" {
		t.Fatalf("%+v", next.LLM)
	}
	_ = eng
}

func TestApplyPUTClearKey(t *testing.T) {
	cur := Default()
	cur.LLM.APIKey = "sk-gone"
	next, _, err := ApplyPUT(cur, []byte(`{"llm":{"api_key":""},"clear_api_key":true}`))
	if err != nil {
		t.Fatal(err)
	}
	if next.LLM.APIKey != "" {
		t.Fatal(next.LLM.APIKey)
	}
}

func TestNeedsEngineRestart(t *testing.T) {
	a, b := Default(), Default()
	if NeedsEngineRestart(a, b) {
		t.Fatal("same")
	}
	b.Paths.STTModel = "/other"
	if !NeedsEngineRestart(a, b) {
		t.Fatal("stt path")
	}
}
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
cd daemon/nexuscfg
go test ./...
```

Expected: compile errors / undefined symbols.

- [ ] **Step 3: Implement `config.go` + `migrate.go`**

`config.go` (essential shapes — keep field names JSON-tagged as in spec):

```go
package nexuscfg

type Config struct {
	Schema int   `json:"schema"`
	WebUI  WebUI `json:"webui"`
	LLM    LLM   `json:"llm"`
	STT    STT   `json:"stt"`
	TTS    TTS   `json:"tts"`
	Paths  Paths `json:"paths"`
}

type WebUI struct {
	Port int `json:"port"`
}

type LLM struct {
	Enabled      bool   `json:"enabled"`
	Model        string `json:"model"`
	APIKey       string `json:"api_key"`
	BargeIn      bool   `json:"barge_in"`
	MaxMsgs      int    `json:"max_msgs"`
	SystemPrompt string `json:"system_prompt"`
}

type STT struct {
	Backend string `json:"backend"`
	Lang    string `json:"lang"`
}

type TTS struct {
	BeepPrefix bool `json:"beep_prefix"`
	Sid        int  `json:"sid"`
}

type Paths struct {
	EngineSock string `json:"engine_sock"`
	ArchiveDir string `json:"archive_dir"`
	STTModel   string `json:"stt_model"`
	TTSModel   string `json:"tts_model"`
}

const DefaultPath = "/data/adb/nexus/config.json"

func Default() Config { /* port 8787, model deepseek-v4-flash, paths as in spec */ }

func Load(path string) (Config, error) { /* json decode; empty file → error */ }

func SaveAtomic(path string, cfg Config) error { /* mkdir, write .tmp, chmod 0600, rename */ }

func Redact(cfg Config) map[string]any { /* marshal via map rebuild */ }

func ApplyPUT(current Config, body []byte) (Config, bool, error) {
	// Unmarshal into a patch struct with pointers/optional clear_api_key bool
	// Merge non-zero / present fields; handle api_key empty vs clear_api_key
	// return NeedsEngineRestart(current, next) as second value
}

func NeedsEngineRestart(before, after Config) bool {
	return before.Paths.STTModel != after.Paths.STTModel ||
		before.Paths.TTSModel != after.Paths.TTSModel ||
		before.Paths.EngineSock != after.Paths.EngineSock
}
```

`migrate.go`:

```go
func Migrate(nexusDir string, cfg Config) (Config, bool, error) {
	// if cfg.LLM.APIKey == "" && file secrets/deepseek.key exists, read trim, set key, return changed=true
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd daemon/nexuscfg
go test ./...
```

Expected: `ok nexus.nexuscfg`

- [ ] **Step 5: Commit**

```bash
git add daemon/nexuscfg
git commit -m "Add nexuscfg shared config load/save/redact helpers."
```

---

### Task 2: `nexus_webui` skeleton — listen, status, logs

**Files:**
- Create: `daemon/nexus_webui/go.mod`
- Create: `daemon/nexus_webui/main.go`
- Create: `daemon/nexus_webui/server.go`
- Create: `daemon/nexus_webui/status.go`
- Create: `daemon/nexus_webui/logs.go`
- Create: `daemon/nexus_webui/server_test.go`
- Modify: `daemon/nexus_webui/go.mod` — `require nexus.nexuscfg` + `replace nexus.nexuscfg => ../nexuscfg`

**Interfaces:**
- Consumes: `nexuscfg.Load`, `Redact`, `DefaultPath`
- Produces: HTTP handlers on mux; `func NewServer(cfgPath, logDir, restartScript string) http.Handler`

- [ ] **Step 1: Module + failing handler test**

```bash
mkdir daemon/nexus_webui
cd daemon/nexus_webui
go mod init nexus.nexus_webui
```

`go.mod` replace:

```
require nexus.nexuscfg v0.0.0
replace nexus.nexuscfg => ../nexuscfg
```

`server_test.go`:

```go
package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"nexus.nexuscfg"
)

func TestGetConfigRedacted(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	cfg := nexuscfg.Default()
	cfg.LLM.APIKey = "sk-secret"
	_ = nexuscfg.SaveAtomic(path, cfg)
	h := NewServer(path, dir, "")
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/api/config", nil))
	if rr.Code != 200 {
		t.Fatal(rr.Body.String())
	}
	if strings.Contains(rr.Body.String(), "sk-secret") {
		t.Fatal("leaked key")
	}
}
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
cd daemon/nexus_webui
go test ./...
```

- [ ] **Step 3: Implement server**

`main.go`: flags `-config` (default `nexuscfg.DefaultPath`), `-addr` default empty → read port from config after load/migrate, bind `127.0.0.1:<port>`, `-log-dir` default `/data/vendor/ai_hook`, `-restart-script` default `/data/adb/modules/nexus_runtime/scripts/restart_callstack.sh`.

`status.go`: check process by reading `/proc` or `pidof` via `exec.Command("pidof", "ai_call")` — on Windows tests stub with injectable `lookupPID func(name string) (int, bool)`.

`logs.go`: whitelist map:

```go
var logFiles = map[string]string{
	"ai_call": "ai_call.log",
	"nexus_engine": "nexus_engine.log",
	"nexus_runtime": "nexus_runtime.log",
	"nexus_webui": "nexus_webui.log",
}
```

Tail last N lines (default 80, max 500).

`GET /api/status` → `{ok:true, processes:{ai_call:{running,pid}, ...}}`  
`GET /api/logs?name=ai_call&lines=80`  
`GET /api/config` → `{ok:true, config: Redact(...)}`

- [ ] **Step 4: Tests PASS**

```bash
cd daemon/nexus_webui
go test ./...
```

- [ ] **Step 5: Commit**

```bash
git add daemon/nexus_webui
git commit -m "Add nexus_webui HTTP skeleton with config/status/logs APIs."
```

---

### Task 3: PUT config + restart call stack script

**Files:**
- Create: `magisk_modules/nexus_runtime/scripts/restart_callstack.sh`
- Modify: `daemon/nexus_webui/server.go` — `PUT /api/config`, `POST /api/restart`
- Create: `daemon/nexus_webui/apply_test.go`
- Modify: `.gitignore` — add `magisk_modules/**/bin/nexus_webui`

**Interfaces:**
- Consumes: `nexuscfg.ApplyPUT`, `SaveAtomic`, `NeedsEngineRestart`
- Produces: `restart_callstack.sh` args: optional `engine|aicall|all` (default `all`); never kills `nexus_webui`

- [ ] **Step 1: Failing test for PUT merge persistence**

```go
func TestPutConfigPersists(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	_ = nexuscfg.SaveAtomic(path, nexuscfg.Default())
	script := filepath.Join(dir, "restart.sh")
	_ = os.WriteFile(script, []byte("#!/bin/sh\necho restarted > \""+dir+"/r.flag\"\n"), 0755)
	h := NewServer(path, dir, script)
	body := `{"llm":{"barge_in":true,"api_key":"sk-new"}}`
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPut, "/api/config", strings.NewReader(body))
	h.ServeHTTP(rr, req)
	if rr.Code != 200 {
		t.Fatal(rr.Body.String())
	}
	got, _ := nexuscfg.Load(path)
	if !got.LLM.BargeIn || got.LLM.APIKey != "sk-new" {
		t.Fatalf("%+v", got.LLM)
	}
	// On non-unix CI, restart script may be no-op if not executable — assert flag if runtime.GOOS != windows
}
```

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Write `restart_callstack.sh`**

Extract start logic from current `service.sh`. Behavior:

1. Source optional `/data/adb/nexus/env.sh` for overrides only
2. Load values preferring `config.json` via a tiny helper **or** duplicate JSON parse with `toybox`/`jq` if present — **prefer**: webui exports env files before calling script:

Simpler contract for v1 (avoid jq on Android):

- `nexus_webui` before exec writes `/data/adb/nexus/run/callstack.env` from Config (KEY=value lines)
- `restart_callstack.sh` sources that file then starts processes

`callstack.env` example:

```
STT_LANG=auto
LLM=1
LLM_BARGE_IN=0
DEEPSEEK_API_KEY=sk-...
DEEPSEEK_MODEL=deepseek-v4-flash
ENGINE_SOCK=...
STT_MODEL_DIR=...
TTS_MODEL_DIR=...
CALL_ARCHIVE_DIR=...
TX_BEEP_PREFIX=0
ENGINE_RESTART=1
```

Script:

```sh
#!/system/bin/sh
MODDIR=/data/adb/modules/nexus_runtime
NEXUS=/data/adb/nexus
# shellcheck disable=SC1090
[ -f "$NEXUS/run/callstack.env" ] && . "$NEXUS/run/callstack.env"
# pkill ONLY ai_call and (if ENGINE_RESTART=1) nexus_engine
# do NOT pkill nexus_webui
# start engine if needed, wait sock, start ai_call with -llm-key from env, -llm-barge-in, etc.
```

`PUT` handler: ApplyPUT → SaveAtomic → write callstack.env → `exec.Command(script)` → if port changed, schedule self-restart (`time.AfterFunc(500ms, os.Exit(0))` after spawning new process — document: Magisk service will not auto-respawn; for port change v1 return `{ok:true, webui_restart_required:true}` and have `service.sh` only start webui once — **OR** webui runs `nohup $BIN/nexus_webui &` then exits). Prefer spawn+exit for port change.

`POST /api/restart`: run script with `ENGINE_RESTART=1`.

- [ ] **Step 4: Tests PASS + build arm64**

```bash
cd daemon/nexus_webui
go test ./...
set GOOS=linux
set GOARCH=arm64
go build -o nexus_webui_arm64 .
```

- [ ] **Step 5: Commit**

```bash
git add daemon/nexus_webui magisk_modules/nexus_runtime/scripts .gitignore
git commit -m "Wire config PUT to atomic save and callstack restart script."
```

---

### Task 4: Static settings page (embed)

**Files:**
- Create: `daemon/nexus_webui/web/index.html`
- Create: `daemon/nexus_webui/web/app.js`
- Create: `daemon/nexus_webui/web/style.css`
- Modify: `daemon/nexus_webui/server.go` — `//go:embed web/*` → `GET /`

**Interfaces:**
- Consumes: `/api/config`, `/api/status`, `/api/logs`, `PUT /api/config`, `POST /api/restart`

- [ ] **Step 1: Add embed stub page that loads status**

`index.html`: three sections — Status / Settings / Logs. Mobile-first CSS. No frameworks.

`app.js` outline:

```js
async function refreshStatus() {
  const r = await fetch('/api/status');
  const j = await r.json();
  // render process pills
}
async function loadConfig() {
  const j = await (await fetch('/api/config')).json();
  // fill form; api_key input placeholder from api_key_hint
}
async function saveConfig() {
  const body = collectForm(); // omit api_key if input empty
  if (clearKeyChecked) body.clear_api_key = true;
  const r = await fetch('/api/config', { method: 'PUT', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body) });
  // toast ok / error; refreshStatus
}
```

Form fields: llm.enabled, model, api_key, barge_in, max_msgs, system_prompt, stt.lang, tts.beep_prefix, tts.sid, all paths.*, webui.port.

- [ ] **Step 2: Manual check with `go run` on PC**

```bash
cd daemon/nexus_webui
go run . -config /tmp/nexus-config.json -addr 127.0.0.1:8787 -log-dir /tmp -restart-script /bin/true
```

Open browser: status JSON path works; save writes file.

- [ ] **Step 3: Commit**

```bash
git add daemon/nexus_webui/web daemon/nexus_webui
git commit -m "Embed localhost settings UI for nexus_webui."
```

---

### Task 5: Magisk `service.sh` + defaults

**Files:**
- Modify: `magisk_modules/nexus_runtime/service.sh`
- Modify: `magisk_modules/nexus_runtime/config/config.default.json`
- Modify: `magisk_modules/nexus_runtime/customize.sh` — ensure scripts installed executable
- Modify: `magisk_modules/nexus_runtime/bin/README.md` — list `nexus_webui`
- Create: `daemon/nexus_webui/build.bat`

**Interfaces:**
- Boot order: engine → ai_call → nexus_webui
- `pkill` on boot may kill old webui once then restart (full module service restart OK)

- [ ] **Step 1: Update `config.default.json` to schema with empty `api_key`, `webui.port`, `barge_in`, remove `key_file` / `notes`**

- [ ] **Step 2: Change `service.sh`**

After ai_call starts:

```sh
WEBUI="${WEBUI_BIN:-$BIN/nexus_webui}"
WLOG=/data/vendor/ai_hook/nexus_webui.log
if [ -x "$WEBUI" ]; then
  pkill -9 nexus_webui 2>/dev/null || true
  # ensure config exists + migrate key: optional one-liner calling webui -migrate-only OR do in webui main on start
  nohup "$WEBUI" -config "$NEXUS/config.json" \
    -restart-script "$MODDIR/scripts/restart_callstack.sh" \
    >>"$WLOG" 2>&1 &
  logmsg "starting nexus_webui"
fi
```

Refactor boot start of engine/ai_call to call `scripts/restart_callstack.sh` so one code path (recommended). If refactor is large, duplicate minimally but keep script authoritative for webui-triggered restarts.

On start, `nexus_webui` main: `Load` → `Migrate` → if changed `SaveAtomic`.

- [ ] **Step 3: `build.bat` for webui**

```bat
set CGO_ENABLED=0
go test ./...
set GOOS=linux
set GOARCH=arm64
go build -o nexus_webui_arm64 .
```

- [ ] **Step 4: Commit**

```bash
git add magisk_modules/nexus_runtime daemon/nexus_webui/build.bat
git commit -m "Boot nexus_webui from nexus_runtime and align config.default.json."
```

---

### Task 6: `ai_call` reads `config.json`

**Files:**
- Modify: `daemon/ai_call/go.mod` — replace `nexus.nexuscfg => ../nexuscfg`
- Create: `daemon/ai_call/config_load.go`
- Create: `daemon/ai_call/config_load_test.go`
- Modify: `daemon/ai_call/main.go` — after `flag.Parse()`, apply file config as defaults beneath flags/env

**Interfaces:**
- Consumes: `nexuscfg.Load`, `Migrate` (optional)
- Rule: **explicit flag / non-empty env wins**; else config.json; else hardcoded defaults

- [ ] **Step 1: Failing test**

```go
func TestApplyFileConfigFillsKeyAndBarge(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	cfg := nexuscfg.Default()
	cfg.LLM.APIKey = "sk-from-json"
	cfg.LLM.BargeIn = true
	cfg.LLM.Model = "deepseek-v4-flash"
	_ = nexuscfg.SaveAtomic(path, cfg)
	got := applyNexusConfig(path, cliOpts{ /* zero = unset */ })
	if got.APIKey != "sk-from-json" || !got.BargeIn {
		t.Fatalf("%+v", got)
	}
}
```

- [ ] **Step 2: Implement `applyNexusConfig`** — map into key, model, barge, lang, archive dir, beep, sid, system prompt, max msgs. If `LLM.Enabled == false`, force llm off unless flag forced.

- [ ] **Step 3: Wire `main.go`**

```go
cfgPath := envOr("NEXUS_CONFIG", nexuscfg.DefaultPath)
fileOpts := applyNexusConfig(cfgPath, ...)
// when building llm client: key = firstNonEmpty(*llmKey, fileOpts.APIKey, loadKeyFile...)
// barge: if user did not set LLM_BARGE_IN env and flag default false, use fileOpts.BargeIn
```

Keep `-llm-key-file` as fallback when json key empty.

- [ ] **Step 4: `go test ./...` in ai_call (clear GOOS/GOARCH on Windows)**

- [ ] **Step 5: Commit**

```bash
git add daemon/ai_call daemon/nexuscfg
git commit -m "Make ai_call prefer /data/adb/nexus/config.json when present."
```

---

### Task 7: Docs + device smoke checklist

**Files:**
- Modify: `magisk_modules/README.md` — WebUI URL, config.json, restart behavior
- Modify: `daemon/ai_call/README.md` — NEXUS_CONFIG / json precedence
- Modify: `doc/dev_journal.md` — short entry
- Modify: `doc/04_architecture_runtime.md` — one row for nexus_webui
- Modify: `docs/superpowers/specs/2026-07-19-nexus-webui-design.md` — status → 实现中/已落地 after done

- [ ] **Step 1: Update docs with:**

  - Open Chrome: `http://127.0.0.1:8787`
  - Pack: copy `nexus_webui_arm64` → `magisk_modules/nexus_runtime/bin/nexus_webui`
  - Key now in config.json; old key file migrated once

- [ ] **Step 2: Device smoke (manual)**

```bash
adb push daemon/nexus_webui/nexus_webui_arm64 /data/local/tmp/nexus_webui
adb push daemon/ai_call/ai_call_arm64 /data/local/tmp/ai_call_new
# install bins into module, push restart_callstack.sh, run service.sh
adb shell "su -c 'tail -n 5 /data/vendor/ai_hook/nexus_webui.log'"
# on phone Chrome: open 127.0.0.1:8787 — toggle barge_in, save, confirm ai_call restart in log
```

- [ ] **Step 3: Commit docs**

```bash
git add magisk_modules/README.md daemon/ai_call/README.md doc/dev_journal.md doc/04_architecture_runtime.md docs/superpowers/specs/2026-07-19-nexus-webui-design.md
git commit -m "Document nexus_webui localhost settings and config.json source of truth."
```

---

## Spec coverage check

| Spec item | Task |
|-----------|------|
| Localhost-only bind | Task 2 main |
| config.json + key in json + 0600 | Task 1 |
| Redacted GET | Task 1–2 |
| PUT + auto restart | Task 3 |
| Status + logs | Task 2, 4 |
| restart without killing webui | Task 3 script |
| service.sh boot webui | Task 5 |
| ai_call reads json | Task 6 |
| Migrate old key file | Task 1 migrate + Task 5 start |
| UI A+B+C | Task 4 |
| Docs | Task 7 |

## Placeholder / consistency review

- No TBD left; port default **8787** throughout
- `ApplyPUT` / `NeedsEngineRestart` names stable across tasks
- Callstack env bridge avoids Android `jq` dependency

---
