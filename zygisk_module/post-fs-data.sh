#!/system/bin/sh
MODDIR=${0%/*}

# sepolicy.rule is applied by Magisk; these are live fallbacks after boot.
magiskpolicy --live "allow audioserver audioserver process execmem" 2>/dev/null
# shellcheck: keep spaces in the next lines (type: allow <domain> ...)
magiskpolicy --live "allow ""hal_audio_default"" ""hal_audio_default"" process execmem" 2>/dev/null
magiskpolicy --live "allow ""hal_audio_default"" system_file file { read open getattr map execute execute_no_trans }" 2>/dev/null
magiskpolicy --live "allow ""hal_audio_default"" system_lib_file file { read open getattr map execute execute_no_trans }" 2>/dev/null
magiskpolicy --live "allow ""hal_audio_default"" vendor_file file { read open getattr map execute execute_no_trans }" 2>/dev/null

chcon u:object_r:vendor_file:s0 "$MODDIR/system/vendor/lib/libai_hook.so" 2>/dev/null
chcon u:object_r:vendor_file:s0 "$MODDIR/vendor/lib/libai_hook.so" 2>/dev/null
chcon u:object_r:system_lib_file:s0 "$MODDIR/system/lib64/libai_hook.so" 2>/dev/null
