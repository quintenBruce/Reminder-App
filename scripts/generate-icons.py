"""Resize the generated app icon and build its monochrome notification badge.

Requires Pillow. Keep assets/remind-icon-source.png as the source for all launcher sizes.
"""

from pathlib import Path
from PIL import Image, ImageDraw

PUBLIC = Path(__file__).resolve().parents[1] / "web" / "public"
SOURCE = PUBLIC.parents[1] / "assets" / "remind-icon-source.png"
WHITE = (255, 255, 255, 255)


with Image.open(SOURCE) as source:
    if source.width != source.height:
        raise ValueError("The app icon source must be square.")
    for pixels in (48, 192, 512):
        source.convert("RGB").resize((pixels, pixels), Image.Resampling.LANCZOS).save(
            PUBLIC / f"icon-{pixels}.png", optimize=True
        )


def render_badge(size: int) -> Image.Image:
    scale = max(4, (size * 4 + 191) // 192)
    canvas = 192 * scale
    image = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    def box(coords):
        return tuple(round(value * scale) for value in coords)

    # Notification badges are monochrome, so keep the microphone silhouette.
    draw.rounded_rectangle(box((83, 43, 109, 111)), radius=13 * scale, fill=WHITE)
    draw.arc(box((57, 59, 135, 137)), start=0, end=180, fill=WHITE, width=14 * scale)
    draw.ellipse(box((50, 91, 64, 105)), fill=WHITE)
    draw.ellipse(box((128, 91, 142, 105)), fill=WHITE)
    draw.rectangle(box((89, 136, 103, 164)), fill=WHITE)
    draw.rounded_rectangle(box((64, 159, 128, 173)), radius=7 * scale, fill=WHITE)
    return image.resize((size, size), Image.Resampling.LANCZOS)


render_badge(96).save(PUBLIC / "badge-96.png", optimize=True)
