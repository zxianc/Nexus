#!/system/bin/sh
# Correct Magisk live syntax (spaces, no colons / no self)
magiskpolicy --live "allow audioserver audioserver process execmem" 2>/dev/null
