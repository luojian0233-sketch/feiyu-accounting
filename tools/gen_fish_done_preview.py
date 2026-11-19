"""肥鱼皮肤「完成」键的配色备选方案。

复用 gen_skin_keypad_preview 的键盘几何与底板，只换完成键的画法。
运行：python tools/gen_fish_done_preview.py
产出 tools/skin_previews/fish_done_options.png
"""

from __future__ import annotations

import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import gen_skin_keypad_preview as K  # noqa: E402

OUT = os.path.join(HERE, "skin_previews")
AMBER = (0xE8, 0xA9, 0x3C)


def grad_fill(img, box, rad, c1, c2):
    w, h = int(box[2] - box[0]), int(box[3] - box[1])
    layer = Image.new("RGBA", (w, h))
    px = layer.load()
    for y in range(h):
        for x in range(w):
            px[x, y] = K.mix(c1, c2, y / max(1, h - 1))
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius=rad, fill=255)
    img.paste(layer, (int(box[0]), int(box[1])), mask)
    return ImageDraw.Draw(img)


def draw_white(img, box, pressed=False):
    rad = 20
    K.soft_shadow(img, box, rad, blur=8, alpha=40, offset=(0, 3))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(box, radius=rad, fill=(255, 255, 255) if not pressed else (0xEC, 0xF4, 0xFF))
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    K.text(d, (cx, cy), "完成", K.font(17, True), (0x12, 0x30, 0x5E), "mm")


def draw_teal(img, box, pressed=False):
    rad = 20
    c1, c2 = ((0x3F, 0xAC, 0xB4), (0x6E, 0xCE, 0xD3)) if not pressed else ((0x33, 0x99, 0xA2), (0x5A, 0xBB, 0xC1))
    K.soft_shadow(img, box, rad, blur=8, alpha=34, offset=(0, 3))
    d = grad_fill(img, box, rad, c1, c2)
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    K.text(d, (cx, cy), "完成", K.font(17, True), (0x06, 0x2F, 0x36), "mm")


def draw_navy_gold(img, box, pressed=False):
    """同色系海军蓝，只留一圈金色描边当呼应。"""
    rad = 20
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(box, radius=rad, fill=(0x1B, 0x44, 0x78) if not pressed else (0x25, 0x55, 0x90),
                        outline=AMBER, width=2)
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    K.text(d, (cx, cy), "完成", K.font(17, True), (255, 255, 255), "mm")


def draw_glass(img, box, pressed=False):
    """跟其它键同一块磨砂玻璃，只把描边加亮。"""
    rad = 20
    K.overlay(img, lambda od: od.rounded_rectangle(
        box, radius=rad, fill=(255, 255, 255, 92 if pressed else 34),
        outline=(255, 255, 255, 210), width=2))
    d = ImageDraw.Draw(img)
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    K.text(d, (cx, cy), "完成", K.font(17, True), (255, 255, 255), "mm")


def draw_pearl(img, box, pressed=False):
    """珍珠白：白到浅蓝，和 App 里的白卡片同源，避免大块死白。"""
    rad = 20
    c1, c2 = ((255, 255, 255), (0xE3, 0xEE, 0xFB)) if not pressed else ((0xEE, 0xF5, 0xFE), (0xD3, 0xE3, 0xF7))
    K.soft_shadow(img, box, rad, blur=8, alpha=40, offset=(0, 3))
    d = grad_fill(img, box, rad, c1, c2)
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    K.text(d, (cx, cy), "完成", K.font(17, True), (0x12, 0x30, 0x5E), "mm")


VARIANTS = [
    ("A  白键 · 深蓝字", "solid white  #FFFFFF / #12305E", draw_white),
    ("B  珍珠白渐层", "pearl  #FFFFFF → #E3EEFB", draw_pearl),
    ("C  湖蓝渐变", "teal gradient  #3FACB4 → #6ECED3", draw_teal),
    ("D  海军蓝 + 金边", "navy + amber rim  #1B4478 / #E8A93C", draw_navy_gold),
]


def done_box():
    geo = K.gap_geo("fish")
    j = 3
    left = round(geo["x0"] + j * (geo["kw"] + geo["gap"]))
    return (left, round(geo["rows"][K.DONE_SPAN[0]][0]), round(left + geo["kw"]),
            round(geo["rows"][K.DONE_SPAN[1]][1]))


def main():
    os.makedirs(OUT, exist_ok=True)
    # 先出一张「完成键留白」的底图，再逐个方案画上去
    original = K.draw_done
    K.draw_done = lambda *a, **k: None
    base = K.column("fish")
    K.draw_done = original

    box = done_box()
    cells = []
    for name, spec, fn in VARIANTS:
        img = base.copy()
        fn(img, box)
        crop = img.crop((0, K.PANEL_TOP - 10, K.COL_W, K.COL_H))
        scale = 1.5
        big = crop.resize((int(crop.width * scale), int(crop.height * scale)), Image.LANCZOS)
        cells.append((name, spec, big, img))

    cw, ch = cells[0][2].size
    cols, rows = 2, 2
    sheet = Image.new("RGBA", (K.MARGIN * 3 + cw * 2, K.MARGIN * 2 + (ch + 46) * rows), (250, 250, 248, 255))
    d = ImageDraw.Draw(sheet)
    for idx, (name, spec, big, _) in enumerate(cells):
        cx = K.MARGIN + (idx % cols) * (cw + K.MARGIN)
        cy = K.MARGIN + (idx // cols) * (ch + 46)
        sheet.alpha_composite(big, (int(cx), int(cy)))
        d.text((cx + 2, cy + ch + 10), name, font=K.font(13, True), fill=(40, 40, 38), anchor="la")
        d.text((cx + 2, cy + ch + 28), spec, font=K.font(10), fill=(120, 120, 115), anchor="la")
    sheet.convert("RGB").save(os.path.join(OUT, "fish_done_options.png"))

    # 整屏条：色块在真实场景里的分量
    thumb_w = int(K.COL_W * 0.42)
    thumb_h = int(K.COL_H * 0.42)
    strip = Image.new("RGBA", (K.MARGIN * 2 + (thumb_w + 12) * 4, thumb_h + 44), (250, 250, 248, 255))
    ds = ImageDraw.Draw(strip)
    x = K.MARGIN
    for name, _, _, full in cells:
        strip.alpha_composite(full.resize((thumb_w, thumb_h), Image.LANCZOS), (x, 8))
        ds.text((x + thumb_w / 2, thumb_h + 22), name.split("  ")[0], font=K.font(11, True),
                fill=(60, 60, 58), anchor="mm")
        x += thumb_w + 12
    strip.convert("RGB").save(os.path.join(OUT, "fish_done_strip.png"))

    print("written to", OUT)


if __name__ == "__main__":
    main()
