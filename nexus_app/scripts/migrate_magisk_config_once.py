#!/usr/bin/env python3
"""One-shot: merge Magisk llm/notify into App config.json (run on PC with pulled files)."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def extract(pat: str, s: str, default: str = "") -> str:
    m = re.search(pat, s, re.S)
    return m.group(1) if m else default


def main() -> int:
    magisk_path = Path(sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\Chen\AppData\Local\Temp\nexus_magisk_config.json")
    key_path = Path(sys.argv[2] if len(sys.argv) > 2 else r"C:\Users\Chen\AppData\Local\Temp\deepseek.key")
    app_path = Path(sys.argv[3] if len(sys.argv) > 3 else r"C:\Users\Chen\AppData\Local\Temp\app_config.json")
    out_path = Path(sys.argv[4] if len(sys.argv) > 4 else r"C:\Users\Chen\AppData\Local\Temp\app_config_merged.json")

    raw = magisk_path.read_text(encoding="utf-8-sig", errors="replace")
    sys_prompt = None
    try:
        mag = json.loads(raw)
        llm = mag.get("llm") or {}
        notify = mag.get("notify") or {}
        wecom = notify.get("wecom") or {}
        webhook = (wecom.get("webhook_url") or notify.get("webhook_url") or "").strip()
        if isinstance(notify.get("sms"), dict):
            sms_en = bool(notify["sms"].get("enabled", True))
        else:
            sms_en = bool(notify.get("sms_enabled", True))
        if isinstance(notify.get("call"), dict):
            call_en = bool(notify["call"].get("enabled", True))
        else:
            call_en = bool(notify.get("call_enabled", True))
        notify_en = bool(notify.get("enabled", False))
        model = llm.get("model") or "deepseek-v4-flash"
        api_key = (llm.get("api_key") or "").strip()
        base_url = llm.get("base_url") or "https://api.deepseek.com"
        max_msgs = int(llm.get("max_msgs") or 24)
        sys_prompt = llm.get("system_prompt")
    except json.JSONDecodeError:
        api_key = extract(r'"api_key"\s*:\s*"([^"]*)"', raw)
        model = extract(r'"model"\s*:\s*"([^"]*)"', raw, "deepseek-v4-flash")
        base_url = "https://api.deepseek.com"
        max_msgs = 24
        webhook = extract(r'"webhook_url"\s*:\s*"([^"]*)"', raw)
        notify_en = extract(r'"notify"\s*:\s*\{.*?"enabled"\s*:\s*(true|false)', raw, "false") == "true"
        sms_en, call_en = True, True

    if key_path.is_file():
        key = key_path.read_text(encoding="utf-8", errors="ignore").strip()
        if key:
            api_key = key

    try:
        app = json.loads(app_path.read_text(encoding="utf-8-sig"))
    except Exception:
        app = {}

    app.setdefault(
        "sims",
        [
            {"slot": 0, "label": "卡1", "carrier": "", "number": "", "policy": "ai"},
            {"slot": 1, "label": "卡2", "carrier": "", "number": "", "policy": "human"},
        ],
    )
    llm_out = dict(app.get("llm") or {})
    llm_out.update(
        {
            "enabled": True,
            "model": model,
            "api_key": api_key,
            "base_url": base_url,
            "max_msgs": max_msgs,
        }
    )
    if sys_prompt:
        llm_out["system_prompt"] = sys_prompt
    app["llm"] = llm_out
    app["notify"] = {
        "enabled": notify_en,
        "webhook_url": webhook,
        "sms_enabled": bool(sms_en),
        "call_enabled": bool(call_en),
    }
    app["model_dir"] = None
    if "dialer_takeover" not in app:
        app["dialer_takeover"] = True

    out_path.write_text(json.dumps(app, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "OK key_set=%s webhook_set=%s notify=%s model=%s"
        % (bool(api_key), bool(webhook), notify_en, model)
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
