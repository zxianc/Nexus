#!/system/bin/sh
# Run during an active call to see which DEC/TX mixers are live.
grep -n 'path name="dmic3"\|path name="handset-mic"\|DEC0 Volume\|TX_AIF1_CAP Mixer DEC0\|TX DEC0' \
  /vendor/etc/mixer_paths.xml | head -50

echo "==== dmic3 block ===="
awk '
  /path name="dmic3"/ {p=1; print; next}
  p && /<\/path>/ {print; p=0; next}
  p {print}
' /vendor/etc/mixer_paths.xml | head -40

echo "==== handset-mic blocks ===="
awk '
  /path name="handset-mic"/ {p=1; print; next}
  p && /<\/path>/ {print; p=0; n++; if(n>=3) exit; next}
  p {print}
' /vendor/etc/mixer_paths.xml | head -80
