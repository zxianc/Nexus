# -*- coding: utf-8 -*-
"""Download sherpa-onnx Chinese VITS TTS model (zh-ll, 16 kHz)."""
from __future__ import annotations

import tarfile
import urllib.request
from pathlib import Path

_REPO = Path(__file__).resolve().parents[3]
ROOT = _REPO / "tmp" / "nexus_stt"
URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-vits-zh-ll.tar.bz2"
ARCHIVE = ROOT / "sherpa-onnx-vits-zh-ll.tar.bz2"
EXTRACT = ROOT / "extract_tts"


def main() -> None:
    ROOT.mkdir(parents=True, exist_ok=True)
    if not ARCHIVE.exists() or ARCHIVE.stat().st_size < 1_000_000:
        print(f"downloading {ARCHIVE.name} ...")
        tmp = ARCHIVE.with_suffix(ARCHIVE.suffix + ".part")

        def hook(block, block_size, total):
            if total <= 0:
                return
            done = block * block_size
            pct = min(100, done * 100 // total)
            if block % 200 == 0 or done >= total:
                print(f"\r  {pct}% {done/1e6:.1f}/{total/1e6:.1f}MB", end="", flush=True)

        urllib.request.urlretrieve(URL, tmp, reporthook=hook)
        print()
        tmp.replace(ARCHIVE)
    else:
        print(f"skip existing {ARCHIVE.name}")

    EXTRACT.mkdir(parents=True, exist_ok=True)
    print(f"extract -> {EXTRACT}")
    with tarfile.open(ARCHIVE, "r:bz2") as tf:
        tf.extractall(EXTRACT)
    for p in sorted(EXTRACT.rglob("*")):
        if p.is_file() and p.suffix in {".onnx", ".txt"} or p.name.endswith(".fst"):
            print(f"  {p.relative_to(EXTRACT)} {p.stat().st_size/1e6:.1f}MB")


if __name__ == "__main__":
    main()
