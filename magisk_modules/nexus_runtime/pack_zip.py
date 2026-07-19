import os
import zipfile

root = os.path.dirname(os.path.abspath(__file__))
out_zip = os.path.join(root, "nexus_runtime.zip")
skip_names = {".gitkeep", "README.md"}
skip_dirs = {"__pycache__"}

with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as zf:
    for dp, dns, files in os.walk(root):
        dns[:] = [d for d in dns if d not in skip_dirs and not d.startswith(".")]
        for name in files:
            if name in ("pack_zip.py", "build.bat", "nexus_runtime.zip"):
                continue
            if name in skip_names:
                continue
            full = os.path.join(dp, name)
            arc = os.path.relpath(full, root).replace("\\", "/")
            # Magisk zip root = module contents
            zf.write(full, arcname=arc)
            print("add", arc)

print("wrote", out_zip)
