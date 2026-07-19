#!/system/bin/sh
# Inject after boot: prefer HAL (call PCM); keep audioserver as optional probe.

MODDIR=${0%/*}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 1
done
sleep 8

INJECT64="$MODDIR/bin/inject"
INJECT32="$MODDIR/bin/inject32"
PAYLOAD64="/system/lib64/libai_hook.so"
# Prefer Magisk overlay under system/vendor (reliable); fallback /vendor/lib.
PAYLOAD32="/system/vendor/lib/libai_hook.so"
PAYLOAD32_ALT="/vendor/lib/libai_hook.so"
HAL_PROC="android.hardware.audio.service"

chmod 755 "$INJECT64" "$INJECT32" 2>/dev/null

inject_hal() {
    local path="$1"
    [ -f "$INJECT32" ] && [ -f "$path" ] || return 1
    log -t AI_Zygisk "service.sh: inject HAL $path"
    "$INJECT32" "$HAL_PROC" "$path"
    sleep 2
    local hp
    hp=$(pidof "$HAL_PROC")
    [ -n "$hp" ] && grep -q libai_hook "/proc/$hp/maps" 2>/dev/null
}

# Primary: 32-bit HAL — retry (audio service may start late)
ok=0
i=0
while [ $i -lt 12 ]; do
    if inject_hal "$PAYLOAD32" || inject_hal "$PAYLOAD32_ALT"; then
        ok=1
        log -t AI_Zygisk "service.sh: HAL inject OK (try=$i)"
        break
    fi
    i=$((i + 1))
    sleep 3
done
[ "$ok" = "1" ] || log -t AI_Zygisk "service.sh: HAL inject FAILED after retries"

# Optional: 64-bit audioserver probe
if [ -f "$INJECT64" ] && [ -f "$PAYLOAD64" ]; then
    log -t AI_Zygisk "service.sh: inject audioserver $PAYLOAD64"
    "$INJECT64" audioserver "$PAYLOAD64"
fi
