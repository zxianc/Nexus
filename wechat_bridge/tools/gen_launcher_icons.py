from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
src = Image.open(ROOT / "orange.png").convert("RGBA")
res = ROOT / "app" / "src" / "main" / "res"

legacy = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
adaptive = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}


def lift_dark_green_rim(im: Image.Image) -> Image.Image:
    """Boost near-black green stroke so it stays visible on dark launcher masks."""
    out = im.copy()
    px = out.load()
    w, h = out.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a < 200:
                continue
            # original rim ~ (36, 86, 57)
            if g > r + 20 and g > b and r + g + b < 220:
                px[x, y] = (
                    min(255, int(r * 1.35 + 18)),
                    min(255, int(g * 1.45 + 28)),
                    min(255, int(b * 1.35 + 18)),
                    a,
                )
    return out


def fit_square(im: Image.Image, size: int, pad_ratio: float = 0.06) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(size * (1 - pad_ratio * 2))
    scaled = im.resize((inner, inner), Image.Resampling.LANCZOS)
    off = (size - inner) // 2
    canvas.paste(scaled, (off, off), scaled)
    return canvas


prepared = lift_dark_green_rim(src)

for folder, size in legacy.items():
    d = res / folder
    d.mkdir(parents=True, exist_ok=True)
    out = prepared.resize((size, size), Image.Resampling.LANCZOS)
    out.save(d / "ic_launcher.png", optimize=True)
    out.save(d / "ic_launcher_round.png", optimize=True)
    print("legacy", folder, size)

for folder, size in adaptive.items():
    d = res / folder
    d.mkdir(parents=True, exist_ok=True)
    fit_square(prepared, size).save(d / "ic_launcher_foreground.png", optimize=True)
    print("fg", folder, size)

print("done")
