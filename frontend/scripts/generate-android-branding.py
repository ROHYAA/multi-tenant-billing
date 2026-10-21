"""Generate ShopLedger launcher icons, splash images, and web marks from the real logo."""
from __future__ import annotations

from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

NAVY = (11, 44, 82, 255)
GOLD = (232, 185, 94, 255)
WHITE = (255, 255, 255, 255)

ROOT = Path(__file__).resolve().parents[1]
ANDROID_RES = ROOT / "android" / "app" / "src" / "main" / "res"
WEB_LOGO = ROOT / "public" / "assets" / "logo"
WEB_ICONS = ROOT / "public" / "icons"
SOURCE_MARK = WEB_LOGO / "shopledger-mark-1024.png"


def load_font(size: int) -> ImageFont.ImageFont:
    for path in (
        r"C:\Windows\Fonts\segoeuib.ttf",
        r"C:\Windows\Fonts\arialbd.ttf",
        r"C:\Windows\Fonts\arial.ttf",
    ):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def knock_out_edge_white(img: Image.Image, threshold: int = 248) -> Image.Image:
    """Make the outer white canvas transparent; keep the white receipt paper."""
    img = img.convert("RGBA")
    width, height = img.size
    pix = img.load()

    def is_bg(x: int, y: int) -> bool:
        r, g, b, a = pix[x, y]
        return a != 0 and r >= threshold and g >= threshold and b >= threshold

    queue: deque[tuple[int, int]] = deque()
    seen = bytearray(width * height)

    def push(x: int, y: int) -> None:
        if 0 <= x < width and 0 <= y < height:
            queue.append((x, y))

    for x in range(width):
        push(x, 0)
        push(x, height - 1)
    for y in range(height):
        push(0, y)
        push(width - 1, y)

    while queue:
        x, y = queue.popleft()
        idx = y * width + x
        if seen[idx]:
            continue
        seen[idx] = 1
        if not is_bg(x, y):
            continue
        pix[x, y] = (0, 0, 0, 0)
        push(x + 1, y)
        push(x - 1, y)
        push(x, y + 1)
        push(x, y - 1)
    return img


def load_logo() -> Image.Image:
    if not SOURCE_MARK.exists():
        raise FileNotFoundError(f"Missing logo source: {SOURCE_MARK}")
    return knock_out_edge_white(Image.open(SOURCE_MARK))


def fit_on_canvas(
    logo: Image.Image,
    size: int,
    *,
    pad_ratio: float,
    background: tuple[int, int, int, int],
) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), background)
    inner = max(1, int(size * (1 - 2 * pad_ratio)))
    fitted = logo.copy()
    fitted.thumbnail((inner, inner), Image.Resampling.LANCZOS)
    x = (size - fitted.width) // 2
    y = (size - fitted.height) // 2
    canvas.alpha_composite(fitted, (x, y))
    return canvas


def circle_mask(img: Image.Image) -> Image.Image:
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def cover_splash(width: int, height: int, logo: Image.Image) -> Image.Image:
    img = Image.new("RGBA", (width, height), NAVY)
    draw = ImageDraw.Draw(img)
    side = min(width, height) // 3
    badge = fit_on_canvas(logo, side, pad_ratio=0.08, background=NAVY)
    x = (width - side) // 2
    y = (height - side) // 2 - height // 18
    img.alpha_composite(badge, (x, y))
    font = load_font(max(28, height // 28))
    title = "ShopLedger"
    bbox = draw.textbbox((0, 0), title, font=font)
    tw = bbox[2] - bbox[0]
    draw.text(((width - tw) / 2, y + side + height // 40), title, font=font, fill=WHITE)
    sub = load_font(max(16, height // 48))
    tag = "Billing for your shop"
    bbox = draw.textbbox((0, 0), tag, font=sub)
    sw = bbox[2] - bbox[0]
    draw.text(((width - sw) / 2, y + side + height // 18), tag, font=sub, fill=GOLD)
    return img


def save_png(img: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG")


def write_favicon(logo: Image.Image) -> None:
    icon = fit_on_canvas(logo, 48, pad_ratio=0.06, background=NAVY).convert("RGBA")
    icon.save(ROOT / "public" / "favicon.ico", format="ICO", sizes=[(16, 16), (32, 32), (48, 48)])


def main() -> None:
    logo = load_logo()

    # In-app / PWA marks keep the original white plate look via the 1024 source.
    source = Image.open(SOURCE_MARK).convert("RGBA")
    for size, name in ((192, "shopledger-mark-192.png"), (512, "shopledger-mark-512.png")):
        resized = source.copy()
        resized.thumbnail((size, size), Image.Resampling.LANCZOS)
        plate = Image.new("RGBA", (size, size), WHITE)
        plate.alpha_composite(resized, ((size - resized.width) // 2, (size - resized.height) // 2))
        save_png(plate, WEB_LOGO / name)

    apple = fit_on_canvas(logo, 180, pad_ratio=0.08, background=WHITE)
    save_png(apple, ROOT / "public" / "apple-touch-icon.png")
    write_favicon(logo)

    for size in (72, 96, 128, 144, 152, 192, 384, 512):
        save_png(
            fit_on_canvas(logo, size, pad_ratio=0.08, background=WHITE),
            WEB_ICONS / f"icon-{size}x{size}.png",
        )

    mipmaps = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, size in mipmaps.items():
        icon = fit_on_canvas(logo, size, pad_ratio=0.10, background=NAVY)
        save_png(icon, ANDROID_RES / folder / "ic_launcher.png")
        save_png(circle_mask(icon), ANDROID_RES / folder / "ic_launcher_round.png")
        # Adaptive foreground is 108dp; only the inner 72dp is guaranteed visible.
        fg_size = int(size * (108 / 48))
        fg = fit_on_canvas(logo, fg_size, pad_ratio=0.22, background=(0, 0, 0, 0))
        save_png(fg, ANDROID_RES / folder / "ic_launcher_foreground.png")

    save_png(
        fit_on_canvas(logo, 288, pad_ratio=0.18, background=(0, 0, 0, 0)),
        ANDROID_RES / "drawable" / "splash_icon.png",
    )

    splashes = {
        "drawable": (1080, 1920),
        "drawable-port-mdpi": (320, 480),
        "drawable-port-hdpi": (480, 800),
        "drawable-port-xhdpi": (720, 1280),
        "drawable-port-xxhdpi": (1080, 1920),
        "drawable-port-xxxhdpi": (1440, 2560),
        "drawable-land-mdpi": (480, 320),
        "drawable-land-hdpi": (800, 480),
        "drawable-land-xhdpi": (1280, 720),
        "drawable-land-xxhdpi": (1920, 1080),
        "drawable-land-xxxhdpi": (2560, 1440),
    }
    for folder, (w, h) in splashes.items():
        save_png(cover_splash(w, h, logo), ANDROID_RES / folder / "splash.png")

    leftover_vectors = [
        ANDROID_RES / "drawable-v24" / "ic_launcher_foreground.xml",
        ANDROID_RES / "drawable" / "ic_launcher_background.xml",
    ]
    for path in leftover_vectors:
        if path.exists():
            path.unlink()

    print("Wrote ShopLedger icons and splash images from shopledger-mark-1024.png")


if __name__ == "__main__":
    main()
