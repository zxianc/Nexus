#!/system/bin/sh
# Restart nexus_engine + ai_call without touching nexus_webui.
# Expects /data/adb/nexus/run/callstack.env (written by nexus_webui).

MODDIR=/data/adb/modules/nexus_runtime
NEXUS=/data/adb/nexus
MODELS=/data/adb/modules/nexus_models
LOG=/data/vendor/ai_hook/nexus_runtime.log
ELOG=/data/vendor/ai_hook/nexus_engine.log

# shellcheck disable=SC1090
[ -f "$NEXUS/env.sh" ] && . "$NEXUS/env.sh"
[ -f "$NEXUS/run/callstack.env" ] && . "$NEXUS/run/callstack.env"

# Go CGO=0 has no system zoneinfo; pair with import _ "time/tzdata".
export TZ="${TZ:-$(getprop persist.sys.timezone 2>/dev/null)}"
[ -n "$TZ" ] || export TZ=Asia/Shanghai

BIN="$MODDIR/bin"
LIB="$MODDIR/lib"
AI_CALL="${AI_CALL_BIN:-$BIN/ai_call}"
ENGINE="${ENGINE_BIN:-$BIN/nexus_engine}"
ORT_DIR="${ORT_LIB_DIR:-$BIN}"
[ -f "$ORT_DIR/libonnxruntime.so" ] || ORT_DIR="$LIB"

STT_MODEL="${STT_MODEL_DIR:-$MODELS/models/sense-voice}"
TTS_MODEL="${TTS_MODEL_DIR:-$MODELS/models/vits-zh-ll}"
ENGINE_SOCK="${ENGINE_SOCK:-$NEXUS/run/engine.sock}"
ARCHIVE_DIR="${CALL_ARCHIVE_DIR:-/data/vendor/ai_hook/calls}"
STT_LANG="${STT_LANG:-auto}"
LLM="${LLM:-1}"
LLM_BARGE_IN="${LLM_BARGE_IN:-0}"
DEEPSEEK_MODEL="${DEEPSEEK_MODEL:-deepseek-v4-flash}"
TX_BEEP_PREFIX="${TX_BEEP_PREFIX:-0}"
ENGINE_RESTART="${ENGINE_RESTART:-1}"

logmsg() {
  /system/bin/log -t NexusRuntime "$*" 2>/dev/null || true
  echo "$(date '+%F %T') restart_callstack: $*" >>"$LOG"
}

mkdir -p "$(dirname "$LOG")" "$NEXUS/run" "$ARCHIVE_DIR" 2>/dev/null

export LD_LIBRARY_PATH="$BIN:$LIB:${LD_LIBRARY_PATH}"
export STT_BACKEND=engine
export STT_LANG
export LLM
export LLM_BARGE_IN
export ENGINE_BIN="$ENGINE"
export ENGINE_SOCK
export STT_MODEL_DIR="$STT_MODEL"
export TTS_MODEL_DIR="$TTS_MODEL"
export DEEPSEEK_MODEL
export CALL_ARCHIVE_DIR="$ARCHIVE_DIR"
export TX_BEEP_PREFIX
export NEXUS_CONFIG="${NEXUS_CONFIG:-$NEXUS/config.json}"
if [ -n "${DEEPSEEK_API_KEY:-}" ]; then
  export DEEPSEEK_API_KEY
fi

# Never kill nexus_webui
pkill -9 ai_call 2>/dev/null || true
if [ "$ENGINE_RESTART" = "1" ]; then
  pkill -9 nexus_engine 2>/dev/null || true
  rm -f "$ENGINE_SOCK"
  : >"$ELOG"
  logmsg "starting nexus_engine"
  nohup "$ENGINE" \
    --sock="$ENGINE_SOCK" \
    --stt-model-dir="$STT_MODEL" \
    --tts-model-dir="$TTS_MODEL" \
    --lang="${STT_LANG}" \
    --threads=2 \
    >>"$ELOG" 2>&1 &
  i=0
  while [ $i -lt 90 ]; do
    if [ -S "$ENGINE_SOCK" ]; then
      break
    fi
    i=$((i + 1))
    sleep 1
  done
  if [ ! -S "$ENGINE_SOCK" ]; then
    logmsg "engine sock missing"
    exit 1
  fi
fi

LLM_FLAGS=""
if [ "$LLM" = "1" ]; then
  LLM_FLAGS="-llm"
fi
BARGE_FLAGS=""
if [ "$LLM_BARGE_IN" = "1" ]; then
  BARGE_FLAGS="-llm-barge-in"
fi

logmsg "starting ai_call llm=$LLM barge=$LLM_BARGE_IN"
# shellcheck disable=SC2086
nohup "$AI_CALL" -backend engine $LLM_FLAGS $BARGE_FLAGS -lang "${STT_LANG}" \
  -engine-bin "$ENGINE" -engine-sock "$ENGINE_SOCK" \
  -model-dir "$STT_MODEL" -tts-model "$TTS_MODEL" \
  -archive-dir "$ARCHIVE_DIR" \
  >>/data/vendor/ai_hook/ai_call.log 2>&1 &

sleep 1
logmsg "done"
exit 0
