#!/system/bin/sh
# Boot-start nexus_engine + ai_call (LLM mode). Requires nexus_models + nexus_audio_hook.

MODDIR=${0%/*}
NEXUS=/data/adb/nexus
MODELS=/data/adb/modules/nexus_models
LOG=/data/vendor/ai_hook/nexus_runtime.log
ELOG=/data/vendor/ai_hook/nexus_engine.log

until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 1
done
sleep 12

# shellcheck disable=SC1090
[ -f "$NEXUS/env.sh" ] && . "$NEXUS/env.sh"

BIN="$MODDIR/bin"
LIB="$MODDIR/lib"
AI_CALL="${AI_CALL_BIN:-$BIN/ai_call}"
ENGINE="${ENGINE_BIN:-$BIN/nexus_engine}"
# Prefer lib next to binary ($ORIGIN / Supervisor LibDir=bin/)
ORT_DIR="${ORT_LIB_DIR:-$BIN}"
[ -f "$ORT_DIR/libonnxruntime.so" ] || ORT_DIR="$LIB"

STT_MODEL="${STT_MODEL_DIR:-$MODELS/models/sense-voice}"
TTS_MODEL="${TTS_MODEL_DIR:-$MODELS/models/vits-zh-ll}"
ENGINE_SOCK="${ENGINE_SOCK:-$NEXUS/run/engine.sock}"
KEY_FILE="${DEEPSEEK_KEY_FILE:-$NEXUS/secrets/deepseek.key}"
ARCHIVE_DIR="${CALL_ARCHIVE_DIR:-/data/vendor/ai_hook/calls}"

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

export LD_LIBRARY_PATH="$BIN:$LIB:${LD_LIBRARY_PATH}"
export STT_BACKEND=engine
export STT_LANG="${STT_LANG:-auto}"
export LLM="${LLM:-1}"
export LLM_BARGE_IN="${LLM_BARGE_IN:-0}"
export ECHO_TTS="${ECHO_TTS:-0}"
export ENGINE_BIN="$ENGINE"
export ENGINE_SOCK
export STT_MODEL_DIR="$STT_MODEL"
export TTS_MODEL_DIR="$TTS_MODEL"
export DEEPSEEK_KEY_FILE="$KEY_FILE"
export DEEPSEEK_MODEL="${DEEPSEEK_MODEL:-deepseek-v4-flash}"
export CALL_ARCHIVE_DIR="$ARCHIVE_DIR"
export TX_BEEP_PREFIX="${TX_BEEP_PREFIX:-0}"

pkill -9 ai_call 2>/dev/null || true
pkill -9 nexus_engine 2>/dev/null || true
rm -f "$ENGINE_SOCK"
: >"$ELOG"

logmsg "starting nexus_engine sock=$ENGINE_SOCK ort=$ORT_DIR"
nohup "$ENGINE" \
  --sock="$ENGINE_SOCK" \
  --stt-model-dir="$STT_MODEL" \
  --tts-model-dir="$TTS_MODEL" \
  --lang="${STT_LANG}" \
  --threads=2 \
  >>"$ELOG" 2>&1 &

i=0
while [ $i -lt 90 ]; do
  # Wait for THIS boot's sock (do not grep stale engine.log)
  if [ -S "$ENGINE_SOCK" ]; then
    logmsg "engine sock ready"
    break
  fi
  i=$((i + 1))
  sleep 1
done

if [ ! -S "$ENGINE_SOCK" ]; then
  logmsg "engine sock missing after wait — see $ELOG"
  exit 0
fi

logmsg "starting ai_call llm=$LLM"
nohup "$AI_CALL" -backend engine -llm -lang "${STT_LANG}" \
  -engine-bin "$ENGINE" -engine-sock "$ENGINE_SOCK" \
  -model-dir "$STT_MODEL" -tts-model "$TTS_MODEL" \
  >>/data/vendor/ai_hook/ai_call.log 2>&1 &

sleep 1
ps -A 2>/dev/null | grep ai_call >>"$LOG" || true
ps -A 2>/dev/null | grep nexus_engine >>"$LOG" || true
logmsg "boot start done"
