# WeCom Notify (`nexus_notify`) Implementation Plan

> **For agentic workers:** implement task-by-task; mark checkboxes done.

**Goal:** Hangup call archive + dual-SIM SMS → WeCom external contact (config-driven).  
**Spec:** `docs/superpowers/specs/2026-07-19-wecom-notify-design.md`

## Files

| Path | Role |
|------|------|
| `daemon/nexuscfg` | `Notify` config + Redact |
| `daemon/nexus_notify/` | main, wecom client, sms poll, call queue |
| `daemon/ai_call/main.go` | write `.notify` sidecar after archive |
| `magisk_modules/nexus_runtime/service.sh` | start notify |
| `restart_callstack.sh` | do not kill notify |
| `config.default.json` | notify defaults |

## Tasks

- [x] T1 nexuscfg Notify types + Default + Redact + ApplyPUT (enabled only)
- [x] T2 nexus_notify: wecom token + send text (+ format helpers + tests)
- [x] T3 call archive watcher (`.notify` queue)
- [x] T4 SMS inbox poll + cursor + dual-SIM mapping
- [x] T5 wire ai_call sidecar + service.sh + gitignore + docs
- [x] T6 build arm64, unit tests green
