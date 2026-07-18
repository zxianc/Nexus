#!/system/bin/sh
# Inject after boot: prefer HAL (call PCM); keep audioserver as optional probe.

MODDIR=${0%/*}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 1
done
sleep 5

INJECT64="$MODDIR/bin/inject"
INJECT32="$MODDIR/bin/inject32"
PAYLOAD64="/system/lib64/libai_hook.so"
# HAL linker namespace permits /vendor|/odm|/system/vendor — Magisk reliably
# overlays under system/, so use system/vendor/lib (not bare /vendor/lib).
PAYLOAD32="/system/vendor/lib/libai_hook.so"
HAL_PROC="android.hardware.audio.service"

chmod 755 "$INJECT64" "$INJECT32" 2>/dev/null

# Primary: 32-bit HAL (telephony PCM path on this device)
if [ -f "$INJECT32" ] && [ -f "$PAYLOAD32" ]; then
    log -t AI_Zygisk "service.sh: inject HAL $PAYLOAD32"
    "$INJECT32" "$HAL_PROC" "$PAYLOAD32"
else
    log -t AI_Zygisk "service.sh: HAL inject missing (inject32 or $PAYLOAD32)"
fi

# Optional: keep 64-bit audioserver probe (MonoPipe etc.)
if [ -f "$INJECT64" ] && [ -f "$PAYLOAD64" ]; then
    log -t AI_Zygisk "service.sh: inject audioserver $PAYLOAD64"
    "$INJECT64" audioserver "$PAYLOAD64"
fi
