#!/system/bin/sh
MODDIR=${0%/*}

# sepolicy.rule is applied by Magisk; live fallbacks + dump dir for Round-8/1.D.
magiskpolicy --live "allow audioserver audioserver process execmem" 2>/dev/null
magiskpolicy --live "allow hal_audio_default hal_audio_default process execmem" 2>/dev/null
magiskpolicy --live "allow hal_audio_default system_file file { read open getattr map execute execute_no_trans }" 2>/dev/null
magiskpolicy --live "allow hal_audio_default system_lib_file file { read open getattr map execute execute_no_trans }" 2>/dev/null
magiskpolicy --live "allow hal_audio_default vendor_file file { read open getattr map execute execute_no_trans }" 2>/dev/null
magiskpolicy --live "allow hal_audio_default vendor_data_file dir { search write add_name create getattr open read remove_name }" 2>/dev/null
magiskpolicy --live "allow hal_audio_default vendor_data_file file { create open write getattr setattr append read unlink }" 2>/dev/null
magiskpolicy --live "allow hal_audio_default vendor_data_file sock_file { create open write getattr setattr unlink bind }" 2>/dev/null
magiskpolicy --live "allow hal_audio_default hal_audio_default unix_stream_socket { create bind listen accept write read getopt setattr getattr }" 2>/dev/null
magiskpolicy --live "allow hal_audio_default magisk unix_stream_socket { accept listen write read getopt getattr }" 2>/dev/null
magiskpolicy --live "allow magisk hal_audio_default unix_stream_socket { connect write read getopt getattr }" 2>/dev/null
magiskpolicy --live "allow magisk vendor_data_file sock_file { write connect getattr }" 2>/dev/null
magiskpolicy --live "allow hal_audio_default shell unix_stream_socket { accept listen write read getopt getattr }" 2>/dev/null
magiskpolicy --live "allow shell hal_audio_default unix_stream_socket { connect write read getopt getattr }" 2>/dev/null
magiskpolicy --live "allow shell vendor_data_file sock_file { write connect getattr }" 2>/dev/null

chcon u:object_r:vendor_file:s0 "$MODDIR/system/vendor/lib/libai_hook.so" 2>/dev/null
chcon u:object_r:vendor_file:s0 "$MODDIR/vendor/lib/libai_hook.so" 2>/dev/null
chcon u:object_r:system_lib_file:s0 "$MODDIR/system/lib64/libai_hook.so" 2>/dev/null

# sepolicy / dump dir for Round-8/1.D/1.D'
mkdir -p /data/vendor/ai_hook
chmod 777 /data/vendor/ai_hook
chcon u:object_r:vendor_data_file:s0 /data/vendor/ai_hook
touch /data/vendor/ai_hook/ai_incall.pcm /data/vendor/ai_hook/ai_dl.pcm
chmod 666 /data/vendor/ai_hook/ai_incall.pcm /data/vendor/ai_hook/ai_dl.pcm
chcon u:object_r:vendor_data_file:s0 /data/vendor/ai_hook/ai_incall.pcm
chcon u:object_r:vendor_data_file:s0 /data/vendor/ai_hook/ai_dl.pcm
