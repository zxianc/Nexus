#!/system/bin/sh
# Backup injector after boot (Zygisk companion may race too early).

MODDIR=${0%/*}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 1
done
sleep 5

INJECT="$MODDIR/bin/inject"
PAYLOAD="/system/lib64/libai_hook.so"

chmod 755 "$INJECT" 2>/dev/null

if [ ! -f "$PAYLOAD" ]; then
    log -t AI_Zygisk "service.sh: missing $PAYLOAD (overlay failed?)"
    exit 0
fi

if [ -f "$INJECT" ]; then
    log -t AI_Zygisk "service.sh: inject $PAYLOAD"
    "$INJECT" audioserver "$PAYLOAD"
else
    log -t AI_Zygisk "service.sh: inject binary missing"
fi
