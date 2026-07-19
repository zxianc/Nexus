#!/system/bin/sh
# Prepare writable dirs early (before late_start service).
MODDIR=${0%/*}
NEXUS=/data/adb/nexus
mkdir -p "$NEXUS/secrets" "$NEXUS/run" "$NEXUS/calls" "$NEXUS/tmp"
chmod 700 "$NEXUS/secrets" 2>/dev/null
chmod 755 "$NEXUS" "$NEXUS/run" "$NEXUS/calls" "$NEXUS/tmp" 2>/dev/null

# Ensure vendor hook dir exists (owned by audio_hook module usually).
mkdir -p /data/vendor/ai_hook/calls 2>/dev/null
chmod 777 /data/vendor/ai_hook 2>/dev/null
chmod 777 /data/vendor/ai_hook/calls 2>/dev/null
