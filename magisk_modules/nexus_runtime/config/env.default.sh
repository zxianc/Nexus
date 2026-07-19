# Default env for /data/adb/nexus/env.sh (copied on first install).
# Edit on device; do not commit secrets.

# export DEEPSEEK_API_KEY=sk-...   # optional; else use secrets/deepseek.key
export DEEPSEEK_KEY_FILE=/data/adb/nexus/secrets/deepseek.key
export DEEPSEEK_MODEL=deepseek-v4-flash
export STT_LANG=auto
export LLM=1
export LLM_BARGE_IN=0
export ECHO_TTS=0
export TX_BEEP_PREFIX=0
export CALL_ARCHIVE_DIR=/data/vendor/ai_hook/calls

# Optional overrides:
# export STT_MODEL_DIR=/data/adb/modules/nexus_models/models/sense-voice
# export TTS_MODEL_DIR=/data/adb/modules/nexus_models/models/vits-zh-ll
# export ENGINE_SOCK=/data/adb/nexus/run/engine.sock
