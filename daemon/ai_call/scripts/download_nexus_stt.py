# -*- coding: utf-8 -*-
"""Download SenseVoice model + sherpa arm64 packages for Nexus STT."""
from __future__ import annotations

import tarfile
import urllib.request
from pathlib import Path

# Doc: doc/05_sherpa_android_build.md
_REPO = Path(__file__).resolve().parents[3]
ROOT = _REPO / "tmp" / "nexus_stt"
ROOT.mkdir(parents=True, exist_ok=True)

ASSETS = [
    (
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        "sense-voice-int8.tar.bz2",
    ),
    (
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-v1.13.4-linux-aarch64-shared-cpu.tar.bz2",
        "linux-aarch64-shared-cpu.tar.bz2",
    ),
    (
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-v1.13.4-android-static-link-onnxruntime.tar.bz2",
        "android-static.tar.bz2",
    ),
]


def download(url: str, dest: Path) -> None:
    if dest.exists() and dest.stat().st_size > 1_000_000:
        print(f"skip existing {dest.name} ({dest.stat().st_size/1e6:.1f}MB)")
        return
    print(f"downloading {dest.name} ...")
    tmp = dest.with_suffix(dest.suffix + ".part")

    def hook(block, block_size, total):
        if total <= 0:
            return
        done = block * block_size
        pct = min(100, done * 100 // total)
        if block % 200 == 0 or done >= total:
            print(f"\r  {pct}% {done/1e6:.1f}/{total/1e6:.1f}MB", end="", flush=True)

    urllib.request.urlretrieve(url, tmp, reporthook=hook)
    print()
    tmp.replace(dest)
    print(f"  saved {dest} ({dest.stat().st_size/1e6:.1f}MB)")


def extract(archive: Path, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    print(f"extract {archive.name} -> {out}")
    with tarfile.open(archive, "r:bz2") as tf:
        tf.extractall(out)


def main() -> None:
    for url, name in ASSETS:
        download(url, ROOT / name)

    extract(ROOT / "sense-voice-int8.tar.bz2", ROOT / "extract_model")
    extract(ROOT / "linux-aarch64-shared-cpu.tar.bz2", ROOT / "extract_linux")
    extract(ROOT / "android-static.tar.bz2", ROOT / "extract_android")

    print("\n=== model files ===")
    for p in (ROOT / "extract_model").rglob("*"):
        if p.is_file() and p.suffix in {".onnx", ".txt"}:
            print(p.relative_to(ROOT), f"{p.stat().st_size/1e6:.1f}MB")

    print("\n=== linux bin/ ===")
    for p in (ROOT / "extract_linux").rglob("*"):
        if p.is_file() and ("bin" in p.parts or p.name.startswith("sherpa-onnx")):
            if p.suffix in {"", ".so"} or "sherpa" in p.name:
                print(p.relative_to(ROOT), oct(p.stat().st_mode)[-3:], f"{p.stat().st_size/1e6:.2f}MB")

    print("\n=== android top ===")
    for p in sorted((ROOT / "extract_android").rglob("*"))[:40]:
        if p.is_file():
            print(p.relative_to(ROOT), f"{p.stat().st_size/1e6:.2f}MB")


if __name__ == "__main__":
    main()
