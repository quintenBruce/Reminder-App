"""Generate Remind's PNG app and notification icons from its simple mic mark.

Requires Pillow. The coordinates match web/public/icon.svg's 192-pixel canvas.
"""

from pathlib import Path
from PIL import Image, ImageDraw

PUBLIC = Path(__file__).resolve().parents[1] / "web" / "public"
PURPLE = (76, 92, 197, 255)
WHITE = (255, 255, 255, 255)


def render(size: int, badge: bool = False) -> Image.Image:
    scale = max(4, (size * 4 + 191) // 192)
    canvas = 192 * scale
    image = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0) if badge else PURPLE)
    draw = ImageDraw.Draw(image)

    def box(coords):
        return tuple(round(value * scale) for value in coords)

    # Capsule, receiver arc, stem, and base: a readable silhouette at small sizes.
    draw.rounded_rectangle(box((83, 43, 109, 111)), radius=13 * scale, fill=WHITE)
    draw.arc(box((57, 59, 135, 137)), start=0, end=180, fill=WHITE, width=14 * scale)
    draw.ellipse(box((50, 91, 64, 105)), fill=WHITE)
    draw.ellipse(box((128, 91, 142, 105)), fill=WHITE)
    draw.rectangle(box((89, 136, 103, 164)), fill=WHITE)
    draw.rounded_rectangle(box((64, 159, 128, 173)), radius=7 * scale, fill=WHITE)
    return image.resize((size, size), Image.Resampling.LANCZOS)


for pixels in (48, 192, 512):
    render(pixels).save(PUBLIC / f"icon-{pixels}.png", optimize=True)
render(96, badge=True).save(PUBLIC / "badge-96.png", optimize=True)
