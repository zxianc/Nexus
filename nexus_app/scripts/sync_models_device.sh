#!/system/bin/sh
set -e
PKG=com.nexus.assistant
DEST=/data/data/$PKG/files/models
SRC=/data/adb/modules/nexus_models/models
mkdir -p "$DEST"
cp -a "$SRC/sense-voice" "$DEST/"
cp -a "$SRC/vits-zh-ll" "$DEST/"
OWNER=$(stat -c %u /data/data/$PKG)
GROUP=$(stat -c %g /data/data/$PKG)
chown -R "$OWNER:$GROUP" "$DEST"
chmod -R u+rwX "$DEST"
echo "OK dest=$DEST"
ls -la "$DEST"
ls -la "$DEST/sense-voice"
ls "$DEST/vits-zh-ll"