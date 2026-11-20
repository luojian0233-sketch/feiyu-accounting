"""把「好好吃」像素表情包裁成「鲸鱼饲料」分类图标。

素材：tools/whale_src/whale_feed.gif（24 帧，320×320，底部有「好好吃」三字）
输出：app/src/main/res/drawable-nodpi/category_whale_feed.png

做四件事：
1. 只取一帧（默认第 0 帧），转成 RGBA；
2. 找到角色与底部文字的空白行，把文字整段切掉；
3. 按 alpha 收紧包围盒，居中放到正方形画布上（留 6% 边距，避免贴边）；
4. 整数倍 NEAREST 放大，保住像素画的硬边。

用法：
    python tools/gen_whale_feed_icon.py            # 生成正式图标
    python tools/gen_whale_feed_icon.py --frame 12 # 换一帧
    python tools/gen_whale_feed_icon.py --preview  # 顺带出多尺寸预览图（gitignore）
"""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageSequence

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "tools" / "whale_src" / "whale_feed.gif"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "category_whale_feed.png"
PREVIEW = ROOT / "tools" / "whale_previews" / "whale-feed-sizes.png"

ALPHA_THRESHOLD = 16
MARGIN_RATIO = 0.06
UPSCALE = 2

# 预览图里模拟分类格子的底色（浅色主题的 surfaceVariant）
TILE_COLOR = (222, 227, 234, 255)


def load_frame(index: int) -> Image.Image:
    gif = Image.open(SRC)
    for i, frame in enumerate(ImageSequence.Iterator(gif)):
        if i == index:
            return frame.convert("RGBA")
    raise SystemExit(f"帧号 {index} 超出范围（{SRC.name}）")


def find_text_gap(image: Image.Image) -> int:
    """返回角色与底部文字之间那条空白带的首行，没有则返回画布高度。"""
    alpha = image.getchannel("A")
    width, height = image.size
    pixels = alpha.load()
    empty = []
    for y in range(height):
        filled = any(pixels[x, y] > ALPHA_THRESHOLD for x in range(width))
        empty.append(not filled)

    # 只在下方 1/3 找，避免把角色头顶的留白当成间隙。
    # 取「第一条」够宽的空白带：底部留白也比字符间隙长，按最长会切到底垫而不是文字。
    min_gap = 4
    y = int(height * 0.66)
    while y < height:
        if empty[y]:
            start = y
            while y < height and empty[y]:
                y += 1
            if y - start >= min_gap:
                return start
        else:
            y += 1
    return height


def build(frame: Image.Image) -> Image.Image:
    cut = find_text_gap(frame)
    body = frame.crop((0, 0, frame.width, cut))
    bbox = body.getchannel("A").point(lambda v: 255 if v > ALPHA_THRESHOLD else 0).getbbox()
    if bbox is None:
        raise SystemExit("裁掉文字后没有剩下像素，检查素材")
    body = body.crop(bbox)

    side = max(body.size)
    margin = max(2, round(side * MARGIN_RATIO))
    canvas_side = side + margin * 2
    canvas = Image.new("RGBA", (canvas_side, canvas_side), (0, 0, 0, 0))
    canvas.paste(
        body,
        ((canvas_side - body.width) // 2, (canvas_side - body.height) // 2),
        body,
    )
    return canvas.resize((canvas_side * UPSCALE, canvas_side * UPSCALE), Image.NEAREST)


def write_preview(icon: Image.Image) -> None:
    """把图标在 16/24/32/48dp 下贴到分类格子底色上，检查缩到最小还认不认得出。"""
    from PIL import ImageDraw

    scale = 3  # 以 xxhdpi 的 3x 折算
    sizes = [16, 24, 32, 48]
    gap = 18
    pad = 16
    width = pad * 2 + sum(s * scale for s in sizes) + gap * (len(sizes) - 1)
    height = 48 * scale + pad * 2
    sheet = Image.new("RGBA", (width, height), (255, 255, 255, 255))
    draw = ImageDraw.Draw(sheet)
    x = pad
    for dp in sizes:
        px = dp * scale
        cell = Image.new("RGBA", (px + 16, px + 16), TILE_COLOR)
        thumb = icon.resize((px, px), Image.LANCZOS)
        cell.alpha_composite(thumb, (8, 8))
        sheet.alpha_composite(cell, (x, pad + (48 * scale - cell.height) // 2))
        draw.text((x + 2, pad - 14), f"{dp}dp", fill=(90, 100, 115, 255))
        x += cell.width + gap
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet.convert("RGB").save(PREVIEW)
    print(f"预览 → {PREVIEW}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--frame", type=int, default=0)
    parser.add_argument("--preview", action="store_true")
    args = parser.parse_args()

    icon = build(load_frame(args.frame))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    icon.save(OUT)
    print(f"图标 → {OUT}  {icon.width}×{icon.height}  {OUT.stat().st_size / 1024:.1f} KB")
    if args.preview:
        write_preview(icon)


if __name__ == "__main__":
    main()
