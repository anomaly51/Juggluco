from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageFilter, ImageOps


ROOT = Path(__file__).resolve().parents[1]
ICONS = ROOT / "public" / "icons"
SOURCE = ROOT / "scripts" / "icon-source-1024.png"


def save_resized(source: Image.Image, name: str, size: int) -> None:
    resized = source.resize((size, size), Image.Resampling.LANCZOS)
    resized.save(ICONS / name, format="PNG", optimize=True)


def save_maskable(source: Image.Image) -> None:
    size = 512
    # Maskable launchers may crop to a circle. Keep the complete waveform and
    # droplet inside the central safe region, then blend it into a full-bleed
    # background so no square edge appears after adaptive masking.
    background = ImageOps.fit(source, (size, size), method=Image.Resampling.LANCZOS)
    background = background.filter(ImageFilter.GaussianBlur(radius=28))
    foreground_size = 384
    foreground = source.resize(
        (foreground_size, foreground_size), Image.Resampling.LANCZOS
    )
    feather = Image.new("L", (foreground_size, foreground_size), 0)
    inset = 18
    feather.paste(255, (inset, inset, foreground_size - inset, foreground_size - inset))
    feather = feather.filter(ImageFilter.GaussianBlur(radius=18))
    offset = (size - foreground_size) // 2
    background.paste(foreground, (offset, offset), feather)
    background.save(ICONS / "icon-maskable-512.png", format="PNG", optimize=True)


def main() -> None:
    source = Image.open(SOURCE).convert("RGB")
    if source.size != (1024, 1024):
        raise SystemExit(f"expected a 1024x1024 source icon, got {source.size}")
    save_resized(source, "apple-touch-icon.png", 180)
    save_resized(source, "icon-192.png", 192)
    save_resized(source, "icon-512.png", 512)
    save_maskable(source)


if __name__ == "__main__":
    main()
