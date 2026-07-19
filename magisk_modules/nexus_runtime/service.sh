#!/system/bin/sh
# Boot-start nexus_engine + ai_call + nexus_webui. Requires nexus_models + nexus_audio_hook.

MODDIR=${0%/*}
NEXUS=/data/adb/nexus
MODELS=/data/adb/modules/nexus_models
LOG=/data/vendor/ai_hook/nexus_runtime.log
ELOG=/data/vendor/ai_hook/nexus_engine.log
WLOG=/data/vendor/ai_hook/nexus_webui.log
RESTART="$MODDIR/scripts/restart_callstack.sh"

until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 1
done
sleep 12

# shellcheck disable=SC1090
[ -f "$NEXUS/env.sh" ] && . "$NEXUS/env.sh"

# Go CGO=0 has no system zoneinfo; pair with import _ "time/tzdata".
export TZ="${TZ:-$(getprop persist.sys.timezone 2>/dev/null)}"
[ -n "$TZ" ] || export TZ=Asia/Shanghai

BIN="$MODDIR/bin"
LIB="$MODDIR/lib"
AI_CALL="${AI_CALL_BIN:-$BIN/ai_call}"
ENGINE="${ENGINE_BIN:-$BIN/nexus_engine}"
WEBUI="${WEBUI_BIN:-$BIN/nexus_webui}"
ORT_DIR="${ORT_LIB_DIR:-$BIN}"
[ -f "$ORT_DIR/libonnxruntime.so" ] || ORT_DIR="$LIB"

STT_MODEL="${STT_MODEL_DIR:-$MODELS/models/sense-voice}"
TTS_MODEL="${TTS_MODEL_DIR:-$MODELS/models/vits-zh-ll}"
ENGINE_SOCK="${ENGINE_SOCK:-$NEXUS/run/engine.sock}"
ARCHIVE_DIR="${CALL_ARCHIVE_DIR:-/data/vendor/ai_hook/calls}"
CFG="${NEXUS_CONFIG:-$NEXUS/config.json}"

logmsg() {
  /system/bin/log -t NexusRuntime "$*" 2>/dev/null || true
  echo "$(date '+%F %T') $*" >>"$LOG"
}

mkdir -p "$(dirname "$LOG")" "$NEXUS/run" "$ARCHIVE_DIR" 2>/dev/null
: >"$LOG"

if [ ! -x "$AI_CALL" ]; then
  logmsg "missing ai_call at $AI_CALL — pack binaries into module bin/ before install"
  exit 0
fi
if [ ! -x "$ENGINE" ]; then
  logmsg "missing nexus_engine at $ENGINE"
  exit 0
fi
if [ ! -d "$STT_MODEL" ] || [ ! -d "$TTS_MODEL" ]; then
  logmsg "models missing (need nexus_models): stt=$STT_MODEL tts=$TTS_MODEL"
  exit 0
fi
if [ ! -f "$ORT_DIR/libonnxruntime.so" ]; then
  logmsg "missing libonnxruntime.so under $ORT_DIR (and $LIB)"
  exit 0
fi

# Seed config.json if missing
if [ ! -f "$CFG" ] && [ -f "$MODDIR/config/config.default.json" ]; then
  cp -f "$MODDIR/config/config.default.json" "$CFG"
  chmod 600 "$CFG" 2>/dev/null || true
  logmsg "seeded $CFG"
fi

# Write callstack.env for shared restart script
mkdir -p "$NEXUS/run"
{
  echo "STT_LANG=${STT_LANG:-auto}"
  echo "LLM=${LLM:-1}"
  echo "LLM_BARGE_IN=${LLM_BARGE_IN:-0}"
  echo "DEEPSEEK_MODEL=${DEEPSEEK_MODEL:-deepseek-v4-flash}"
  echo "ENGINE_SOCK=$ENGINE_SOCK"
  echo "STT_MODEL_DIR=$STT_MODEL"
  echo "TTS_MODEL_DIR=$TTS_MODEL"
  echo "CALL_ARCHIVE_DIR=$ARCHIVE_DIR"
  echo "TX_BEEP_PREFIX=${TX_BEEP_PREFIX:-0}"
  echo "ENGINE_RESTART=1"
  echo "NEXUS_CONFIG=$CFG"
} >"$NEXUS/run/callstack.env"

# Full boot: kill old stack + webui then restart
pkill -9 nexus_webui 2>/dev/null || true
chmod 755 "$RESTART" 2>/dev/null || true
if [ -x "$RESTART" ]; then
  logmsg "boot via restart_callstack.sh"
  sh "$RESTART" || logmsg "restart_callstack failed"
else
  logmsg "missing $RESTART"
  exit 0
fi

if [ -x "$WEBUI" ]; then
  : >"$WLOG"
  logmsg "starting nexus_webui"
  nohup "$WEBUI" -config "$CFG" \
    -restart-script "$RESTART" \
    -log-dir /data/vendor/ai_hook \
    >>"$WLOG" 2>&1 &
else
  logmsg "nexus_webui binary missing (optional until packed)"
fi

sleep 1
ps -A 2>/dev/null | grep -E 'ai_call|nexus_engine|nexus_webui' >>"$LOG" || true
logmsg "boot start done"
