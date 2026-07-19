import os
import zipfile

root = os.path.dirname(os.path.abspath(__file__))
out_zip = os.path.join(root, "nexus_models.zip")
skip_dirs = {"__pycache__"}

with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as zf:
    for dp, dns, files in os.walk(root):
        dns[:] = [d for d in dns if d not in skip_dirs and not d.startswith(".")]
        for name in files:
            if name in ("pack_zip.py", "build.bat", "nexus_models.zip"):
                continue
            if name == "README.md" and os.path.basename(dp) in ("sense-voice", "vits-zh-ll"):
                # keep README only if no real weights yet — still useful
                pass
            full = os.path.join(dp, name)
            arc = os.path.relpath(full, root).replace("\\", "/")
            zf.write(full, arcname=arc)
            print("add", arc)

print("wrote", out_zip)
