import os
import zipfile

root = "out"
out_zip = "ai_audio_hook_zygisk.zip"

with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as zf:
    for dp, _, files in os.walk(root):
        for name in files:
            full = os.path.join(dp, name)
            arc = os.path.relpath(full, root).replace("\\", "/")
            zf.write(full, arcname=arc)
            print("add", arc)

print("wrote", out_zip)
