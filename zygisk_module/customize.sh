#!/system/bin/sh
# Magisk already extracted the zip into $MODPATH
chmod 755 "$MODPATH/bin/inject" 2>/dev/null
chmod 755 "$MODPATH/service.sh" 2>/dev/null
ui_print "- AI Audio Hook (Zygisk) ready"
ui_print "- Reboot with Zygisk enabled"
