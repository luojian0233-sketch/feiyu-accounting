"""四套皮肤的「待核对横幅 + 统计饼图」风格预览。

只生成对比图，不改 App 代码。运行：
    python tools/gen_skin_banner_chart_preview.py

产出 tools/skin_previews/：
    banner_chart_all.png      当前实现 vs 四套皮肤，并排对比
    banner_chart_<skin>.png   单套皮肤放大图
    banner_strip_all.png      只截横幅区域，方便看细节
"""

from __future__ import annotations

import math
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "skin_previews")
FONT_REG = "C:/Windows/Fonts/msyh.ttc"
FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"

COL_W, COL_H = 400, 780
GAP = 14
MARGIN = 20


# ---------------------------------------------------------------- 基础绘制

def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT_BOLD if bold else FONT_REG, size)


def rrect(draw, box, radius, fill=None, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def _grad_tile(size, c1, c2, horizontal=False):
    w, h = size
    tile = Image.new("RGBA", (w, h))
    px = tile.load()
    if horizontal:
        for x in range(w):
            t = x / max(1, w - 1)
            for y in range(h):
                px[x, y] = mix(c1, c2, t)
    else:
        for y in range(h):
            c = mix(c1, c2, y / max(1, h - 1))
            for x in range(w):
                px[x, y] = c
    return tile


def mix(c1, c2, t):
    return tuple(round(c1[i] + (c2[i] - c1[i]) * t) for i in range(3)) + (255,)


def grad_round(img, box, radius, c1, c2, horizontal=False):
    x0, y0, x1, y1 = box
    tile = _grad_tile((x1 - x0, y1 - y0), c1, c2, horizontal)
    mask = Image.new("L", tile.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, tile.width - 1, tile.height - 1), radius=radius, fill=255)
    img.paste(tile, (x0, y0), mask)


def soft_shadow(img, box, radius, blur=10, alpha=26, offset=(0, 4)):
    x0, y0, x1, y1 = box
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle(
        (x0 + offset[0], y0 + offset[1], x1 + offset[0], y1 + offset[1]),
        radius=radius, fill=(15, 35, 28, alpha),
    )
    layer = layer.filter(ImageFilter.GaussianBlur(blur))
    img.alpha_composite(layer)


def text(d, xy, s, f, fill, anchor="la"):
    d.text(xy, s, font=f, fill=fill, anchor=anchor)


def dashed_rrect(d, box, radius, color, width=1, dash=6, gap=4):
    """圆角矩形的虚线描边（简化：只画四条边，拐角按直角处理）。"""
    x0, y0, x1, y1 = box
    for x in range(x0, x1, dash + gap):
        xe = min(x + dash, x1)
        d.line((x, y0, xe, y0), fill=color, width=width)
        d.line((x, y1, xe, y1), fill=color, width=width)
    for y in range(y0, y1, dash + gap):
        ye = min(y + dash, y1)
        d.line((x0, y, x0, ye), fill=color, width=width)
        d.line((x1, y, x1, ye), fill=color, width=width)


def leaf(d, cx, cy, h, color):
    """一片带中脉的叶子：窄叶形 + 叶柄，小尺寸下才不会读成菱形。"""
    w = h * 0.52
    pts = []
    steps = 24
    # 顺时针半叶
    for i in range(steps + 1):
        t = i / steps
        yy = cy + h / 2 - h * t
        half = (w / 2) * (1 - abs(2 * t - 1)) ** 0.58
        pts.append((cx - half, yy))
    for i in range(steps, -1, -1):
        t = i / steps
        yy = cy + h / 2 - h * t
        half = (w / 2) * (1 - abs(2 * t - 1)) ** 0.58
        pts.append((cx + half, yy))
    d.polygon(pts, fill=color)
    d.line((cx, cy + h / 2, cx, cy - h / 2), fill=mix(color, (255, 255, 255), 0.45), width=1)
    d.line((cx, cy + h / 2, cx, cy + h / 2 + 3), fill=color, width=1)


def receipt_glyph(d, x, y, w, h, color):
    """小票图标：矩形 + 锯齿底边 + 一条横线。"""
    body = (x, y, x + w, y + h * 0.82)
    d.rectangle(body, outline=color, width=2)
    step = w / 4
    pts = [(x, y + h * 0.82)]
    for i in range(4):
        pts.append((x + step * (i + 0.5), y + h))
        pts.append((x + step * (i + 1), y + h * 0.82))
    d.line(pts, fill=color, width=2)
    d.line((x + w * 0.22, y + h * 0.30, x + w * 0.78, y + h * 0.30), fill=color, width=2)


def sparkle(d, cx, cy, r, color):
    """四角星：尖角 + 内凹腰身，小尺寸也比十字更像星星。"""
    k = r * 0.24
    pts = [(cx, cy - r), (cx + k, cy - k), (cx + r, cy), (cx + k, cy + k),
           (cx, cy + r), (cx - k, cy + k), (cx - r, cy), (cx - k, cy - k)]
    d.polygon(pts, fill=color)


def wave(d, x0, x1, y, amp, color, width=2):
    pts = []
    for x in range(int(x0), int(x1), 3):
        t = (x - x0) / max(1.0, x1 - x0)
        pts.append((x, y + math.sin(t * math.pi * 2.2) * amp))
    if len(pts) > 1:
        d.line(pts, fill=color, width=width, joint="curve")


# ---------------------------------------------------------------- 皮肤定义

SKINS = {
    "mint": {
        "label": "清透薄荷 CLEAR_MINT",
        "bg": (0xF7, 0xFB, 0xF9),
        "text": (0x17, 0x21, 0x1E),
        "muted": (0x5E, 0x74, 0x6C),
        "card": (255, 255, 255),
        "card_r": 18,
        "shadow": True,
        "palette": [
            (0x1F, 0x7A, 0x5E), (0x6F, 0xBF, 0x9E), (0xA9, 0xD9, 0xC5),
            (0xE8, 0xB8, 0x6A), (0xE0, 0x78, 0x5F), (0x7C, 0x9F, 0xD4), (0xB4, 0x9B, 0xD1),
        ],
        "slice_gap": 0.0,
        "rim": None,
    },
    "receipt": {
        "label": "小票账本 RECEIPT_LEDGER",
        "bg": (0xF1, 0xF0, 0xEA),
        "text": (0x25, 0x27, 0x24),
        "muted": (0x6E, 0x7C, 0x6F),
        "card": (0xFF, 0xFE, 0xFA),
        "card_r": 2,
        "shadow": False,
        "palette": [
            (0x4E, 0x6B, 0x58), (0x8C, 0x6A, 0x4A), (0xB5, 0x57, 0x3F),
            (0xC9, 0xBF, 0xA5), (0x2F, 0x3A, 0x33), (0x4F, 0x6B, 0x79), (0x9A, 0x8F, 0x78),
        ],
        "slice_gap": 0.7,
        "rim": (0xDA, 0xD5, 0xC6),
    },
    "paper": {
        "label": "纸感极简 PAPER_MINIMAL",
        "bg": (0xF3, 0xEF, 0xE4),
        "text": (0x26, 0x30, 0x29),
        "muted": (0x6B, 0x7A, 0x6E),
        "card": (0xF8, 0xF4, 0xE9),
        "card_r": 8,
        "shadow": False,
        "palette": [
            (0x5F, 0x72, 0x64), (0x93, 0xA4, 0x94), (0xC3, 0xB9, 0xA0),
            (0xA6, 0x66, 0x3F), (0x2B, 0x3A, 0x31), (0x6B, 0x7F, 0x8C), (0x9C, 0x7E, 0x9E),
        ],
        "slice_gap": 1.2,
        "rim": (0xB9, 0xC2, 0xB5),
    },
    "fish": {
        "label": "肥鱼记账 FATTY_FISH",
        "bg": (0xF6, 0xFA, 0xFF),
        "text": (0x0E, 0x1F, 0x33),
        "muted": (0x40, 0x56, 0x6F),
        "card": (255, 255, 255),
        "card_r": 22,
        "shadow": True,
        "palette": [
            (0x2E, 0x5F, 0xAE), (0x4E, 0x8F, 0xD6), (0x7F, 0xB6, 0xE8),
            (0x58, 0xC3, 0xC9), (0xE8, 0xA9, 0x3C), (0xF2, 0xC9, 0x6B), (0x8E, 0x7B, 0xD6),
        ],
        "slice_gap": 1.6,
        "rim": None,
    },
}

DATA = [("餐饮", 62.79), ("日用", 12.30), ("交通", 8.60), ("娱乐", 5.40), ("其他", 3.20)]


# ---------------------------------------------------------------- 各皮肤横幅

def banner_current(img, d, box):
    x0, y0, x1, y1 = box
    rrect(d, box, 12, fill=(0xFF, 0xF3, 0xD8))
    text(d, (x0 + 14, (y0 + y1) // 2), "19 笔自动记录待核对 ›", font(15), (0x1B, 0x1B, 0x1B), "lm")


def banner_mint(img, d, box):
    x0, y0, x1, y1 = box
    grad_round(img, box, 18, (0xEF, 0xFB, 0xF5), (0xD2, 0xEE, 0xE2))
    # 右侧几点露水，别用大块白色高光（会读成一个球/按钮）
    for cx, cy, r, a in [(x1 - 30, y0 + 18, 4, 60), (x1 - 48, y0 + 40, 2, 45), (x1 - 18, y0 + 42, 2, 55)]:
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(255, 255, 255, a))
    d.ellipse((x0 + 12, y0 + 13, x0 + 50, y0 + 51), fill=(0xC6, 0xE8, 0xDA))
    leaf(d, x0 + 31, y0 + 30, 22, (0x2D, 0x7D, 0x62))
    text(d, (x0 + 60, (y0 + y1) // 2), "19 笔自动记录待核对", font(15, True), (0x0B, 0x49, 0x3A), "lm")
    text(d, (x1 - 16, (y0 + y1) // 2), "›", font(20, True), (0x2D, 0x7D, 0x62), "rm")


def banner_receipt(img, d, box, bg):
    x0, y0, x1, y1 = box
    rrect(d, box, 2, fill=(0xFF, 0xFE, 0xFA))
    dashed_rrect(d, box, 2, (0xB8, 0xB6, 0xAE), dash=7, gap=5)
    step = (x1 - x0) / 14
    for i in range(1, 14):
        cx = x0 + step * i
        d.ellipse((cx - 4, y0 - 4, cx + 4, y0 + 4), fill=bg)
        d.ellipse((cx - 4, y1 - 4, cx + 4, y1 + 4), fill=bg)
    receipt_glyph(d, x0 + 15, y0 + 13, 20, 26, (0x2F, 0x3A, 0x33))
    text(d, (x0 + 48, (y0 + y1) // 2), "19 笔自动记录待核对", font(15), (0x24, 0x3B, 0x32), "lm")
    text(d, (x1 - 34, (y0 + y1) // 2), "NO.019", font(11), (0x6E, 0x7C, 0x6F), "rm")
    text(d, (x1 - 12, (y0 + y1) // 2), "›", font(18), (0x2F, 0x3A, 0x33), "rm")


def banner_paper(img, d, box):
    x0, y0, x1, y1 = box
    rrect(d, box, 8, fill=(0xF7, 0xF1, 0xE3))
    d.rectangle((x0 + 14, y0 + 18, x0 + 17, y1 - 18), fill=(0x2E, 0x5A, 0x46))
    # 右上角折角
    fold = 18
    d.polygon([(x1 - fold, y0), (x1, y0), (x1, y0 + fold)], fill=(0xE4, 0xDC, 0xC8))
    d.line((x1 - fold, y0, x1, y0 + fold), fill=(0xB9, 0xB2, 0x9C), width=1)
    # 底部点线
    for x in range(x0 + 22, x1 - 30, 7):
        d.rectangle((x, y1 - 6, x + 3, y1 - 5), fill=(0x9A, 0xA8, 0x9D))
    text(d, (x0 + 28, (y0 + y1) // 2 - 1), "19 笔自动记录待核对", font(15, True), (0x18, 0x37, 0x2B), "lm")
    text(d, (x1 - 14, (y0 + y1) // 2), "›", font(19), (0x2E, 0x5A, 0x46), "rm")


def banner_fish(img, d, box):
    x0, y0, x1, y1 = box
    grad_round(img, box, 20, (0x2E, 0x5F, 0xAE), (0x4E, 0x8F, 0xD6), horizontal=True)
    for cx, cy, r, a in [(x1 - 34, y0 + 14, 8, 55), (x1 - 70, y0 + 42, 4, 42), (x1 - 16, y0 + 46, 3, 50)]:
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(255, 255, 255, a))
    sparkle(d, x1 - 52, y0 + 18, 7, (0xFF, 0xE0, 0xA3))
    sparkle(d, x1 - 72, y0 + 32, 4, (0xFF, 0xF0, 0xC8))
    text(d, (x0 + 18, (y0 + y1) // 2), "19 笔自动记录待核对", font(15, True), (255, 255, 255), "lm")
    text(d, (x1 - 14, (y0 + y1) // 2), "›", font(20, True), (0xFF, 0xE0, 0xA3), "rm")


# ---------------------------------------------------------------- 卡片与饼图

def header_mini(img, d, box, skin):
    x0, y0, x1, y1 = box
    if skin == "mint":
        grad_round(img, box, 20, (0xEF, 0xFB, 0xF5), (0xD2, 0xEE, 0xE2))
        wave(d, x0 + 16, x1 - 20, y1 - 16, 4, (0x8F, 0xCB, 0xB2), 2)
        title_c = (0x0B, 0x49, 0x3A)
    elif skin == "receipt":
        rrect(d, box, 3, fill=(0xFF, 0xFE, 0xFA), outline=(0xDA, 0xD5, 0xC6))
        for x in range(x0 + 20, x0 + 210, 10):
            d.rectangle((x, y0 + 8, x + 5, y0 + 9), fill=(0xBF, 0xB9, 0xA8))
        title_c = (0x24, 0x3B, 0x32)
    elif skin == "paper":
        rrect(d, box, 9, fill=(0xF8, 0xF4, 0xE9))
        d.rectangle((x0 + 20, y0 + 30, x0 + 23, y0 + 62), fill=(0x2E, 0x5A, 0x46))
        title_c = (0x18, 0x37, 0x2B)
    else:
        grad_round(img, box, 22, (0x1B, 0x4C, 0x8C), (0x3D, 0x77, 0xC4))
        wave(d, x0 + 14, x1 - 30, y1 - 18, 5, (0x9C, 0xD2, 0xF2), 3)
        title_c = (255, 255, 255)
    text(d, (x0 + 30 if skin != "receipt" else x0 + 24, y0 + 26), "今天", font(20, True), title_c)
    sub_c = (198, 219, 243) if skin == "fish" else mix(title_c, (255, 255, 255), 0.52)
    text(d, (x0 + 30 if skin != "receipt" else x0 + 24, y0 + 55), "吃好每一顿，记好每一笔", font(11), sub_c)
    # 右上角小徽章占位
    bc = (x1 - 62, y0 + 18, x1 - 22, y0 + 58)
    if skin == "fish":
        d.ellipse(bc, fill=(255, 255, 255, 55), outline=(255, 255, 255, 130))
    else:
        d.ellipse(bc, outline=mix(title_c, (255, 255, 255), 0.45), width=2)


def summary_card(img, d, box, skin, s):
    x0, y0, x1, y1 = box
    if s["shadow"]:
        soft_shadow(img, box, s["card_r"], blur=8, alpha=20)
    rrect(d, box, s["card_r"], fill=s["card"], outline=s["rim"] if skin in ("receipt", "paper") else None)
    cols = [("今日支出", "-¥0.00"), ("今日收入", "+¥0.00"), ("今日结余", "+¥0.00")]
    w = (x1 - x0) / 3
    for i, (lab, val) in enumerate(cols):
        cx = x0 + w * i + w / 2
        text(d, (cx, y0 + 22), lab, font(11), s["muted"], "mm")
        text(d, (cx, y0 + 44), val, font(14, True), s["text"], "mm")


def java_hash(text: str) -> int:
    """Kotlin/Java String.hashCode，用来复现 App 当前的分类取色。"""
    h = 0
    for ch in text:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - 2 ** 32 if h >= 2 ** 31 else h


def colors_for(s):
    if s.get("hash_colors"):
        return [s["palette"][java_hash(name) % len(s["palette"])] for name, _ in DATA]
    return [s["palette"][i % len(s["palette"])] for i in range(len(DATA))]


def pie_layer(size, colors, gap_deg, rim):
    """4 倍分辨率画饼再缩小，边缘才不锯齿。"""
    scale = 4
    layer = Image.new("RGBA", (size * scale, size * scale), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    total = sum(v for _, v in DATA)
    start = -90.0
    box = (0, 0, size * scale - 1, size * scale - 1)
    for i, (_, v) in enumerate(DATA):
        sweep = v / total * 360.0
        end = start + sweep
        d.pieslice(box, start, end, fill=colors[i] + (255,))
        if gap_deg > 0 and sweep > gap_deg * 2:
            d.pieslice(box, end - gap_deg, end, fill=(0, 0, 0, 0))
        start = end
    if rim:
        d.ellipse(box, outline=rim + (255,), width=scale)
    return layer.resize((size, size), Image.LANCZOS)


def stats_card(img, d, box, skin, s):
    x0, y0, x1, y1 = box
    if s["shadow"]:
        soft_shadow(img, box, s["card_r"], blur=10, alpha=22)
    rrect(d, box, s["card_r"], fill=s["card"], outline=s["rim"] if skin in ("receipt", "paper") else None)
    cx = (x0 + x1) / 2
    text(d, (cx, y0 + 24), "支出合计", font(11), s["muted"], "mm")
    text(d, (cx, y0 + 50), "¥92.29", font(22, True), s["text"], "mm")

    size = 172
    colors = colors_for(s)
    pie = pie_layer(size, colors, s["slice_gap"], s["rim"] if skin == "paper" else None)
    if skin in ("receipt", "paper"):
        # 小票/纸感：垫一层卡片色，让饼图缺口像是从纸里切开
        d.ellipse((cx - size / 2 - 4, y0 + 68 - 4, cx + size / 2 + 4, y0 + 68 + size + 4),
                  fill=s["card"])
    img.alpha_composite(pie, (int(cx - size / 2), y0 + 68))

    # 图例：只放前三类
    ly = y0 + 68 + size + 24
    total = sum(v for _, v in DATA)
    for i, (name, v) in enumerate(DATA[:3]):
        yy = ly + i * 24
        if skin == "receipt":
            d.rectangle((x0 + 26, yy - 4, x0 + 34, yy + 4), fill=colors[i])
        elif skin == "paper":
            d.rectangle((x0 + 24, yy - 1, x0 + 34, yy + 1), fill=colors[i])
        elif skin == "fish":
            d.rectangle((x0 + 26, yy - 4, x0 + 33, yy + 3), fill=colors[i])
            d.rectangle((x0 + 26, yy - 4, x0 + 29, yy - 1), fill=mix(colors[i], (255, 255, 255), 0.35))
        else:
            d.ellipse((x0 + 26, yy - 4, x0 + 34, yy + 4), fill=colors[i])
        text(d, (x0 + 42, yy), name, font(12), s["text"], "lm")
        text(d, (x1 - 26, yy), "%.1f%%" % (v / total * 100), font(11), s["muted"], "rm")


def column(skin: str) -> Image.Image:
    s = SKINS[skin]
    img = Image.new("RGBA", (COL_W, COL_H), s["bg"] + (255,))
    d = ImageDraw.Draw(img)
    x0, x1 = MARGIN, COL_W - MARGIN

    header_mini(img, d, (x0, 16, x1, 116), skin)
    summary_card(img, d, (x0, 126, x1, 194), skin, s)

    banner_box = (x0, 206, x1, 268)
    {"mint": banner_mint, "receipt": lambda i, dd, b: banner_receipt(i, dd, b, s["bg"]),
     "paper": banner_paper, "fish": banner_fish}[skin](img, d, banner_box)

    stats_card(img, d, (x0, 284, x1, 284 + 430), skin, s)
    text(d, (COL_W / 2, COL_H - 26), s["label"], font(12, True), s["muted"], "mm")
    return img


def current_column() -> Image.Image:
    s = dict(SKINS["mint"])
    s["label"] = "当前实现（通用）"
    s["palette"] = [(0x23, 0x86, 0x6B), (0xFF, 0xA7, 0x6C), (0x5C, 0x8F, 0xC9),
                    (0xE0, 0x6D, 0x83), (0x8B, 0x75, 0xBF), (0xE2, 0xB8, 0x4B), (0x4E, 0x9B, 0x9B)]
    s["slice_gap"] = 0.0
    s["rim"] = None
    s["hash_colors"] = True
    img = Image.new("RGBA", (COL_W, COL_H), s["bg"] + (255,))
    d = ImageDraw.Draw(img)
    x0, x1 = MARGIN, COL_W - MARGIN
    header_mini(img, d, (x0, 16, x1, 116), "mint")
    summary_card(img, d, (x0, 126, x1, 194), "mint", s)
    banner_current(img, d, (x0, 206, x1, 268))
    stats_card(img, d, (x0, 284, x1, 284 + 430), "current", s)
    d.text((COL_W / 2, COL_H - 26), s["label"], font=font(12, True),
           fill=(0x5E, 0x74, 0x6C), anchor="mm")
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    cols = [current_column()] + [column(k) for k in SKINS]
    total_w = MARGIN * 2 + COL_W * len(cols) + GAP * (len(cols) - 1)
    sheet = Image.new("RGBA", (total_w, COL_H + MARGIN * 2), (250, 250, 248, 255))
    x = MARGIN
    for c in cols:
        sheet.alpha_composite(c, (x, MARGIN))
        x += COL_W + GAP
    sheet.convert("RGB").save(os.path.join(OUT, "banner_chart_all.png"), quality=95)

    for k in SKINS:
        column(k).convert("RGB").save(os.path.join(OUT, "banner_chart_%s.png" % k))

    # 横幅单独放大，方便看细节
    strip = Image.new("RGBA", (total_w, 120), (250, 250, 248, 255))
    x = MARGIN
    for k, c in zip(["current"] + list(SKINS), cols):
        crop = c.crop((MARGIN - 4, 200, COL_W - MARGIN + 4, 274)).resize((COL_W - 2 * MARGIN + 8, 148), Image.LANCZOS)
        strip.alpha_composite(crop, (x, 6))
        x += COL_W + GAP
    strip.convert("RGB").save(os.path.join(OUT, "banner_strip_all.png"))
    print("written to", OUT)


if __name__ == "__main__":
    main()
