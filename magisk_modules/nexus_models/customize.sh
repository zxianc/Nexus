#!/system/bin/sh
ui_print "- Nexus Models v0.1"
ui_print "- Expect: models/sense-voice  models/vits-zh-ll"

chmod 755 "$MODPATH/customize.sh" 2>/dev/null

STT="$MODPATH/models/sense-voice"
TTS="$MODPATH/models/vits-zh-ll"

if [ ! -f "$STT/model.int8.onnx" ] && [ ! -f "$STT/model.onnx" ]; then
  ui_print "! WARNING: sense-voice model files missing under models/sense-voice/"
  ui_print "! Pack models into the zip before install (see README)."
fi
if [ ! -d "$TTS" ] || [ -z "$(ls -A "$TTS" 2>/dev/null)" ]; then
  ui_print "! WARNING: vits-zh-ll model dir empty under models/vits-zh-ll/"
fi

# Convenience symlink tree for humans / future UI
NEXUS=/data/adb/nexus
mkdir -p "$NEXUS"
ln -sfn "$STT" "$NEXUS/sense-voice" 2>/dev/null
ln -sfn "$TTS" "$NEXUS/vits-zh-ll" 2>/dev/null

ui_print "- Models live under /data/adb/modules/nexus_models/models/"
ui_print "- Install/update nexus_runtime for daemons"
