#!/system/bin/sh
# Magisk extracted zip into $MODPATH
ui_print "- Nexus Runtime v0.1"
ui_print "- Binaries under module bin/ (ai_call, nexus_engine)"
ui_print "- Config: /data/adb/nexus/"
ui_print "- Models: install nexus_models separately"

chmod 755 "$MODPATH/bin/"* 2>/dev/null
chmod 755 "$MODPATH/service.sh" "$MODPATH/post-fs-data.sh" 2>/dev/null

NEXUS=/data/adb/nexus
mkdir -p "$NEXUS/secrets" "$NEXUS/run" "$NEXUS/calls" "$NEXUS/tmp"
chmod 700 "$NEXUS/secrets"
chmod 755 "$NEXUS" "$NEXUS/run" "$NEXUS/calls" "$NEXUS/tmp"

if [ ! -f "$NEXUS/env.sh" ]; then
  cp -f "$MODPATH/config/env.default.sh" "$NEXUS/env.sh"
  chmod 644 "$NEXUS/env.sh"
  ui_print "- Wrote /data/adb/nexus/env.sh (edit API key path / model)"
fi

if [ ! -f "$NEXUS/config.json" ]; then
  cp -f "$MODPATH/config/config.default.json" "$NEXUS/config.json"
  chmod 644 "$NEXUS/config.json"
fi

# Optional: migrate key from debug path
if [ ! -s "$NEXUS/secrets/deepseek.key" ] && [ -s /data/local/tmp/nexus_stt/deepseek.key ]; then
  cp -f /data/local/tmp/nexus_stt/deepseek.key "$NEXUS/secrets/deepseek.key"
  chmod 600 "$NEXUS/secrets/deepseek.key"
  ui_print "- Migrated deepseek.key from /data/local/tmp"
fi

ui_print "- Put DeepSeek key in /data/adb/nexus/secrets/deepseek.key"
ui_print "- Reboot after nexus_models + nexus_audio_hook are installed"
