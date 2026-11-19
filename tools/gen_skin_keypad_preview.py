"""四套皮肤的「记一笔」局内数字键盘风格预览。

只生成对比图，不改 App 代码。运行：
    python tools/gen_skin_keypad_preview.py

产出 tools/skin_previews/：
    keypad_all.png          四套皮肤整屏并排（金额框聚焦 + 键盘展开）
    keypad_detail_all.png   键盘区域放大 2x2，常态
    keypad_pressed_all.png  同上，按住数字键 7 的状态
    keypad_<skin>.png       单套皮肤整屏
"""

from __future__ import annotations

import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from gen_skin_banner_chart_preview import (  # noqa: E402
    SKINS,
    dashed_rrect,
    font,
    grad_round,
    mix,
    rrect,
    soft_shadow,
    sparkle,
    text,
)

OUT = os.path.join(HERE, "skin_previews")

COL_W, COL_H = 420, 900
GAP = 16
MARGIN = 22

FONT_MONO = "C:/Windows/Fonts/consola.ttf"
FONT_MONO_B = "C:/Windows/Fonts/consolab.ttf"
FONT_SONG = "C:/Windows/Fonts/simsun.ttc"

PANEL_TOP = 598

KEYS = [
    ["1", "2", "3", "back"],
    ["4", "5", "6", "done"],
    ["7", "8", "9", None],
    [".", "0", "00", "clear"],
]
DONE_SPAN = (1, 2)  # 完成键竖跨第 2、3 行

AMOUNT = "128.50"


def mono(size: int, bold: bool = False):
    return ImageFont.truetype(FONT_MONO_B if bold else FONT_MONO, size)


def song(size: int):
    return ImageFont.truetype(FONT_SONG, size)


# ---------------------------------------------------------------- 基础件

def overlay(img, fn):
    """在独立透明层上画完再合成，半透明填充才不会被直接覆写。"""
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    fn(ImageDraw.Draw(layer))
    img.alpha_composite(layer)
    return ImageDraw.Draw(img)


def backspace_icon(d, cx, cy, s, color, width=2):
    """退格键：五边形 + 里面一个叉，比字体里的 ⌫ 更可控。"""
    half_w, half_h = s * 0.5, s * 0.33
    tip = cx - half_w
    body = tip + half_h * 1.15
    right = cx + half_w
    pts = [
        (tip, cy),
        (body, cy - half_h),
        (right, cy - half_h),
        (right, cy + half_h),
        (body, cy + half_h),
    ]
    d.line(pts + [pts[0]], fill=color, width=width, joint="curve")
    ax, bx = body + half_h * 0.4, right - half_h * 0.45
    ay, by = cy - half_h * 0.42, cy + half_h * 0.42
    d.line((ax, ay, bx, by), fill=color, width=width)
    d.line((ax, by, bx, ay), fill=color, width=width)


def caret(d, x, top, h, color):
    d.rounded_rectangle((x, top, x + 2, top + h), radius=1, fill=color)


def rounded_grad(img, box, radius, c1, c2, horizontal=False, corners="all"):
    """圆角渐变块；corners="top" 时只把上方两角削圆（底部贴边）。"""
    x0, y0, x1, y1 = [int(v) for v in box]
    w, h = x1 - x0, y1 - y0
    tile = Image.new("RGBA", (w, h))
    px = tile.load()
    for y in range(h):
        for x in range(w):
            t = (x / max(1, w - 1)) if horizontal else (y / max(1, h - 1))
            px[x, y] = mix(c1, c2, t)
    mask = Image.new("L", (w, h), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle((0, 0, w - 1, h - 1), radius=radius, fill=255)
    if corners == "top":
        md.rectangle((0, radius, w, h), fill=255)
    img.paste(tile, (x0, y0), mask)


def punch_holes(img, y, r, spacing, color):
    d = ImageDraw.Draw(img)
    for x in range(int(r), COL_W, int(spacing)):
        d.ellipse((x - r, y - r, x + r, y + r), fill=color)


# ---------------------------------------------------------------- 皮肤色谱

FORM = {
    "mint": dict(
        title=(0x17, 0x21, 0x1E), muted=(0x5E, 0x74, 0x6C), ink=(0x17, 0x21, 0x1E),
        primary=(0x23, 0x86, 0x6B), pin=(0x8F, 0xD3, 0xB8), dark=(0x0B, 0x49, 0x3A),
        field=(255, 255, 255), field_rim=(0xD8, 0xE7, 0xDF), radius=14,
        chip_sel=(0xDD, 0xF3, 0xEA), chip_sel_txt=(0x0B, 0x49, 0x3A), chip_rim=(0xD8, 0xE7, 0xDF),
    ),
    "receipt": dict(
        title=(0x25, 0x27, 0x24), muted=(0x6E, 0x7C, 0x6F), ink=(0x2F, 0x3A, 0x33),
        primary=(0x35, 0x6A, 0x58), pin=(0xB8, 0xB6, 0xAE), dark=(0x24, 0x3B, 0x32),
        field=(0xFF, 0xFE, 0xFA), field_rim=(0xB8, 0xB6, 0xAE), radius=2,
        chip_sel=(0xE3, 0xEC, 0xE6), chip_sel_txt=(0x24, 0x3B, 0x32), chip_rim=(0xB8, 0xB6, 0xAE),
    ),
    "paper": dict(
        title=(0x26, 0x30, 0x29), muted=(0x6B, 0x7A, 0x6E), ink=(0x2B, 0x3A, 0x31),
        primary=(0x23, 0x4C, 0x3C), pin=(0xB9, 0xC2, 0xB5), dark=(0x18, 0x37, 0x2B),
        field=(0xF8, 0xF4, 0xE9), field_rim=(0xB9, 0xC2, 0xB5), radius=6,
        chip_sel=(0xDD, 0xE5, 0xD8), chip_sel_txt=(0x18, 0x37, 0x2B), chip_rim=(0xB9, 0xC2, 0xB5),
    ),
    "fish": dict(
        title=(0x0E, 0x1F, 0x33), muted=(0x40, 0x56, 0x6F), ink=(0x0E, 0x1F, 0x33),
        primary=(0x2E, 0x5F, 0xAE), pin=(0x6E, 0xA3, 0xE0), dark=(0x12, 0x30, 0x5E),
        field=(255, 255, 255), field_rim=(0x6E, 0xA3, 0xE0), radius=18,
        chip_sel=(0xDC, 0xE8, 0xFB), chip_sel_txt=(0x12, 0x30, 0x5E), chip_rim=(0xC3, 0xD6, 0xEC),
    ),
}

KEY_STYLE = {
    "mint": dict(rad=16, fill=(255, 255, 255, 255), rim=(0xE1, 0xEF, 0xE8), shadow=True,
                 press_fill=(0xD6, 0xEE, 0xE2, 255), press_rim=(0x9A, 0xD3, 0xBC), digit=(0x17, 0x21, 0x1E)),
    "receipt": dict(rad=2, fill=(0xFB, 0xF8, 0xEE, 255), rim=(0xCF, 0xC9, 0xB8), shadow=False,
                    press_fill=(0xE7, 0xE1, 0xCE, 255), press_rim=(0x8C, 0x88, 0x78), digit=(0x2F, 0x3A, 0x33)),
    "paper": dict(rad=6, fill=(0xFA, 0xF6, 0xEC, 255), rim=(0xDC, 0xD3, 0xBD), shadow=False,
                  press_fill=(0xE4, 0xDC, 0xC8, 255), press_rim=(0x9A, 0xA8, 0x9D), digit=(0x2B, 0x3A, 0x31)),
    "fish": dict(rad=20, fill=(255, 255, 255, 30), rim=(255, 255, 255, 62), shadow=False,
                 press_fill=(255, 255, 255, 92), press_rim=(255, 255, 255, 160), digit=(0xF2, 0xF8, 0xFF)),
}


# ---------------------------------------------------------------- 表单上半屏

def chip(d, box, label, sel, f, radius):
    x0, y0, x1, y1 = box
    if sel:
        rrect(d, box, radius, fill=f["chip_sel"], outline=f["chip_rim"])
        c = f["chip_sel_txt"]
    else:
        rrect(d, box, radius, fill=f["field"], outline=f["chip_rim"])
        c = f["muted"]
    text(d, ((x0 + x1) / 2, (y0 + y1) / 2), label, font(13), c, "mm")


def field_box(d, box, skin, f, focused=False):
    if skin == "receipt":
        rrect(d, box, f["radius"], fill=f["field"])
        dashed_rrect(d, (box[0] + 1, box[1] + 1, box[2] - 1, box[3] - 1), f["radius"], f["field_rim"], dash=7, gap=5)
    elif skin == "paper":
        rrect(d, box, f["radius"], fill=f["field"], outline=f["field_rim"])
    else:
        rrect(d, box, f["radius"], fill=f["field"], outline=f["pin"] if focused else f["field_rim"],
              width=2 if focused else 1)


def entry_form(img, d, skin):
    f = FORM[skin]
    is_rect = skin == "receipt"
    chip_r = 2 if is_rect else (18 if skin == "fish" else 12)

    text(d, (18, 28), "‹ 返回", font(13), f["muted"], "lm")
    text(d, (COL_W / 2, 28), "记一笔", font(19, True), f["title"], "mm")

    chip(d, (16, 52, 78, 84), "支出", True, f, chip_r)
    chip(d, (86, 52, 148, 84), "收入", False, f, chip_r)

    # 金额框（聚焦态，等键盘输入）
    box = (16, 96, COL_W - 16, 172)
    field_box(d, box, skin, f, focused=True)
    text(d, (32, 112), "金额（元）", font(11), f["primary"], "lm")
    if skin == "receipt":
        text(d, (34, 143), "¥", mono(17), f["muted"], "lm")
        text(d, (52, 143), AMOUNT, mono(26, True), f["ink"], "lm")
        caret(d, 158, 130, 26, f["primary"])
    elif skin == "paper":
        text(d, (34, 143), "¥", song(15), f["muted"], "lm")
        text(d, (50, 142), AMOUNT, song(27), f["ink"], "lm")
        caret(d, 146, 130, 25, f["primary"])
    elif skin == "fish":
        text(d, (36, 142), "¥", font(17), f["muted"], "lm")
        text(d, (58, 141), AMOUNT, font(27, True), f["ink"], "lm")
        caret(d, 154, 129, 26, f["primary"])
    else:
        text(d, (34, 142), "¥", font(16), f["muted"], "lm")
        text(d, (54, 141), AMOUNT, font(27, True), f["ink"], "lm")
        caret(d, 150, 129, 26, f["primary"])

    box = (16, 184, COL_W - 16, 232)
    field_box(d, box, skin, f)
    text(d, (32, 208), "分类", font(14), f["ink"], "lm")
    text(d, (COL_W - 34, 208), "餐饮  ›", font(14), f["ink"], "rm")

    box = (16, 244, COL_W - 16, 292)
    field_box(d, box, skin, f)
    text(d, (32, 268), "商户", font(14), f["muted"], "lm")

    text(d, (16, 316), "账户", font(12), f["ink"], "lm")
    chip(d, (16, 328, 92, 360), "未选择", False, f, chip_r)

    chip(d, (16, 372, 150, 404), "11月19日", False, f, chip_r)
    chip(d, (158, 372, 226, 404), "19:48", False, f, chip_r)

    box = (16, 416, COL_W - 16, 476)
    field_box(d, box, skin, f)
    text(d, (32, 446), "备注", font(14), f["muted"], "lm")

    text(d, (16, 502), "内部转账", font(14), f["ink"], "lm")
    text(d, (16, 522), "保存记录，但不计入收支统计", font(11), f["muted"], "lm")
    tw, th = 44, 24
    tx, ty = COL_W - 16 - tw, 500
    rrect(d, (tx, ty, tx + tw, ty + th), 12, fill=f["primary"] if skin == "fish" else (0xD2, 0xDD, 0xD6))
    d.ellipse((tx + tw - 21, ty + 3, tx + tw - 3, ty + 21), fill=(255, 255, 255))



# ---------------------------------------------------------------- 键盘底板

PAD_TOP = {"mint": 38, "receipt": 50, "paper": 38, "fish": 38}


def panel_background(img, d, skin):
    f = FORM[skin]
    y0 = PANEL_TOP
    if skin == "mint":
        rounded_grad(img, (0, y0, COL_W, COL_H), 22, (0xFD, 0xFE, 0xFD), (0xE7, 0xF4, 0xED), corners="top")
        d = ImageDraw.Draw(img)
        rrect(d, (COL_W / 2 - 18, y0 + 9, COL_W / 2 + 18, y0 + 13), 2, fill=(0xBD, 0xE3, 0xD2))
        overlay(img, lambda od: (
            [od.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(255, 255, 255, a))
             for cx, cy, r, a in [(30, y0 + 20, 3, 80), (44, y0 + 32, 2, 55)]]))
        d = ImageDraw.Draw(img)
        text(d, (20, y0 + 30), "金额", font(9), (0x76, 0x95, 0x88), "lm")
    elif skin == "receipt":
        d.rectangle((0, y0, COL_W, COL_H), fill=(0xFF, 0xFE, 0xFA))
        punch_holes(img, y0, 5, 13, SKINS[skin]["bg"] + (255,))
        d = ImageDraw.Draw(img)
        dashed_rrect(d, (0, y0 + 12, COL_W - 1, COL_H - 1), 2, (0xD5, 0xD0, 0xC1), dash=8, gap=6)
        text(d, (COL_W / 2, y0 + 24), "—  小 计 录 入  —", font(9), (0x9A, 0x8F, 0x78), "mm")
        text(d, (COL_W - 20, y0 + 24), "NO.0001", mono(8), (0xB5, 0xA9, 0x8F), "rm")
    elif skin == "paper":
        d.rectangle((0, y0, COL_W, COL_H), fill=(0xF7, 0xF1, 0xE3))
        d.line((0, y0, COL_W, y0), fill=(0xC9, 0xC2, 0xAC), width=1)
        d.line((0, y0 + 1, COL_W, y0 + 1), fill=(0xE6, 0xDE, 0xCB), width=1)
        d.line((7, y0 + 12, 7, COL_H - 12), fill=(0x8F, 0xA5, 0x94), width=2)
        for x in range(20, COL_W - 16, 8):
            d.rectangle((x, y0 + 12, x + 4, y0 + 13), fill=(0xC4, 0xBC, 0xA6))
        text(d, (20, y0 + 30), "金额", font(9), (0x8B, 0x99, 0x8C), "lm")
    else:
        rounded_grad(img, (0, y0, COL_W, COL_H), 24, (0x15, 0x31, 0x57), (0x33, 0x69, 0xBC), corners="top")
        d = ImageDraw.Draw(img)
        rrect(d, (COL_W / 2 - 18, y0 + 10, COL_W / 2 + 18, y0 + 14), 2, fill=(255, 255, 255, 120))
        overlay(img, lambda od: (
            [od.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(255, 255, 255, a))
             for cx, cy, r, a in [(COL_W - 48, y0 + 24, 7, 26), (COL_W - 74, y0 + 32, 4, 20),
                                  (COL_W - 100, y0 + 22, 5, 16)]]))
        d = ImageDraw.Draw(img)
        sparkle(d, COL_W - 116, y0 + 24, 6, (0xF2, 0xC9, 0x6B))
        text(d, (20, y0 + 30), "AMOUNT", font(8), (0x9F, 0xC4, 0xEC), "lm")


# ---------------------------------------------------------------- 键

def gap_geo(skin):
    pad_x = 24 if skin == "paper" else 16
    pad_top = PAD_TOP[skin]
    pad_bottom = 16
    gap = 9
    kw = (COL_W - 2 * pad_x - 3 * gap) / 4
    total = COL_H - PANEL_TOP - pad_top - pad_bottom
    kh = (total - 3 * gap) / 4
    rows = []
    for i in range(4):
        top = PANEL_TOP + pad_top + i * (kh + gap)
        rows.append((top, top + kh))
    return dict(x0=pad_x, kw=kw, rows=rows, gap=gap)


def key_rect(i, j, geo):
    left = round(geo["x0"] + j * (geo["kw"] + geo["gap"]))
    return (left, round(geo["rows"][i][0]), round(left + geo["kw"]), round(geo["rows"][i][1]))


def plate(img, box, skin, filled=False):
    st = KEY_STYLE[skin]
    fill = (st["press_fill"] if filled else st["fill"])[:3]
    rim = st["press_rim"] if filled else st["rim"]
    fill_a = st["press_fill"][3] if filled else st["fill"][3]
    if st["shadow"] and not filled:
        soft_shadow(img, box, st["rad"], blur=5, alpha=16, offset=(0, 2))
    if fill_a < 255:
        overlay(img, lambda od: od.rounded_rectangle(box, radius=st["rad"], fill=fill + (fill_a,),
                                                     outline=rim + (255,) if len(rim) == 3 else rim, width=1))
    else:
        d = ImageDraw.Draw(img)
        d.rounded_rectangle(box, radius=st["rad"], fill=fill, outline=rim[0:3] if len(rim) > 3 else rim, width=1)
    return ImageDraw.Draw(img)


def keypad(img, skin, pressed=None):
    d = ImageDraw.Draw(img)
    panel_background(img, d, skin)
    geo = gap_geo(skin)
    st = KEY_STYLE[skin]

    for i, row in enumerate(KEYS):
        for j, label in enumerate(row):
            if label is None or label == "back":
                continue
            if label == "done":
                left = round(geo["x0"] + j * (geo["kw"] + geo["gap"]))
                box = (left, round(geo["rows"][DONE_SPAN[0]][0]),
                       round(left + geo["kw"]), round(geo["rows"][DONE_SPAN[1]][1]))
                draw_done(img, skin, box, pressed=(pressed == "done"))
                continue
            box = key_rect(i, j, geo)
            if label == "clear":
                d = plate(img, box, skin)
                c = {"mint": (0x5E, 0x74, 0x6C), "receipt": (0x6E, 0x7C, 0x6F),
                     "paper": (0x6B, 0x7A, 0x6E), "fish": (0xC7, 0xDC, 0xF5)}[skin]
                text(d, ((box[0] + box[2]) / 2, (box[1] + box[3]) / 2 + 1), "清空", font(14), c, "mm")
                continue
            d = plate(img, box, skin, filled=(pressed == label))
            cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
            if skin == "receipt":
                text(d, (cx, cy + 1), label, mono(23, True), st["digit"], "mm")
            elif skin == "paper":
                text(d, (cx, cy + 1), label, song(23), st["digit"], "mm")
            elif skin == "fish":
                text(d, (cx, cy + 1), label, font(21, True), st["digit"], "mm")
            else:
                text(d, (cx, cy + 1), label, font(21), st["digit"], "mm")

    # 退格：icon 键
    box = key_rect(0, 3, geo)
    d = plate(img, box, skin)
    icon_c = {"mint": (0x2D, 0x7D, 0x62), "receipt": (0x4E, 0x6B, 0x58),
              "paper": (0x5F, 0x72, 0x64), "fish": (0xDB, 0xE9, 0xFA)}[skin]
    backspace_icon(d, (box[0] + box[2]) / 2, (box[1] + box[3]) / 2, 25, icon_c, 2)


def draw_done(img, skin, box, pressed=False):
    x0, y0, x1, y1 = box
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    rad = KEY_STYLE[skin]["rad"]
    if skin == "mint":
        c1, c2 = ((0x27, 0x7C, 0x5F), (0x1B, 0x6B, 0x51)) if pressed else ((0x2E, 0x93, 0x73), (0x1F, 0x7A, 0x5E))
        soft_shadow(img, box, rad, blur=7, alpha=26, offset=(0, 3))
        grad = Image.new("RGBA", (int(x1 - x0), int(y1 - y0)))
        px = grad.load()
        for y in range(grad.height):
            for x in range(grad.width):
                px[x, y] = mix(c1, c2, y / max(1, grad.height - 1))
        mask = Image.new("L", grad.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, grad.width - 1, grad.height - 1), radius=rad, fill=255)
        img.paste(grad, (int(x0), int(y0)), mask)
        d = ImageDraw.Draw(img)
        text(d, (cx, cy), "完成", font(17, True), (255, 255, 255), "mm")
    elif skin == "receipt":
        d = ImageDraw.Draw(img)
        rrect(d, box, rad, fill=(0x2F, 0x3A, 0x33) if not pressed else (0x44, 0x51, 0x49))
        dashed_rrect(d, (x0 + 5, y0 + 5, x1 - 5, y1 - 5), 1, (0xF7, 0xF4, 0xE8), dash=4, gap=3)
        text(d, (cx, cy - 7), "完 成", font(15, True), (0xF7, 0xF4, 0xE8), "mm")
        text(d, (cx, cy + 13), "ENTER", mono(8), (0xC9, 0xC4, 0xB4), "mm")
    elif skin == "paper":
        d = ImageDraw.Draw(img)
        rrect(d, box, rad, fill=(0x23, 0x4C, 0x3C) if not pressed else (0x1A, 0x3B, 0x2E))
        text(d, (cx, cy - 6), "完成", font(15, True), (0xF9, 0xFB, 0xF4), "mm")
        d.line((cx - 17, cy + 11, cx + 17, cy + 11), fill=(0xB9, 0xC2, 0xB5), width=1)
    else:
        # 金色：取自鲸鱼娘立绘的小星星，深海底板上唯一的暖色
        c1 = (0xE8, 0xA9, 0x3C) if not pressed else (0xD5, 0x97, 0x2C)
        c2 = (0xF2, 0xC9, 0x6B) if not pressed else (0xE2, 0xB6, 0x55)
        grad = Image.new("RGBA", (int(x1 - x0), int(y1 - y0)))
        px = grad.load()
        for y in range(grad.height):
            for x in range(grad.width):
                px[x, y] = mix(c1, c2, y / max(1, grad.height - 1))
        mask = Image.new("L", grad.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, grad.width - 1, grad.height - 1), radius=rad, fill=255)
        img.paste(grad, (int(x0), int(y0)), mask)
        d = ImageDraw.Draw(img)
        text(d, (cx, cy), "完成", font(17, True), (0x3A, 0x25, 0x00), "mm")


# ---------------------------------------------------------------- 整屏

def column(skin) -> Image.Image:
    img = Image.new("RGBA", (COL_W, COL_H), SKINS[skin]["bg"] + (255,))
    d = ImageDraw.Draw(img)
    entry_form(img, d, skin)
    keypad(img, skin)
    return img


def detail_cell(skin, pressed=None) -> Image.Image:
    img = Image.new("RGBA", (COL_W, COL_H), SKINS[skin]["bg"] + (255,))
    keypad(img, skin, pressed=pressed)
    crop = img.crop((0, PANEL_TOP - 10, COL_W, COL_H))
    scale = 1.55
    return crop.resize((int(crop.width * scale), int(crop.height * scale)), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    skins = list(SKINS)

    cols = [column(k) for k in skins]
    total_w = MARGIN * 2 + COL_W * len(cols) + GAP * (len(cols) - 1)
    sheet = Image.new("RGBA", (total_w, MARGIN * 2 + COL_H + 34), (250, 250, 248, 255))
    d = ImageDraw.Draw(sheet)
    x = MARGIN
    for k, c in zip(skins, cols):
        sheet.alpha_composite(c, (x, MARGIN))
        text(d, (x, MARGIN + COL_H + 12), SKINS[k]["label"], font(12, True), (70, 70, 66), "la")
        x += COL_W + GAP
    sheet.convert("RGB").save(os.path.join(OUT, "keypad_all.png"))

    for k, c in zip(skins, cols):
        c.convert("RGB").save(os.path.join(OUT, "keypad_%s.png" % k))

    cw, ch = detail_cell(skins[0]).size
    for name, pressed, suffix in (("keypad_detail_all.png", None, "常态"),
                                  ("keypad_pressed_all.png", "7", "按住 7")):
        sheet2 = Image.new("RGBA", (MARGIN * 3 + cw * 2, MARGIN * 2 + (ch + 34) * 2), (250, 250, 248, 255))
        d2 = ImageDraw.Draw(sheet2)
        for idx, k in enumerate(skins):
            img = detail_cell(k, pressed=pressed)
            cx = MARGIN + (idx % 2) * (cw + MARGIN)
            cy = MARGIN + (idx // 2) * (ch + 34)
            sheet2.alpha_composite(img, (int(cx), int(cy)))
            text(d2, (cx + 4, cy + ch + 9), "%s  ·  %s" % (SKINS[k]["label"], suffix),
                 font(12, True), (60, 60, 58), "la")
        sheet2.convert("RGB").save(os.path.join(OUT, name))

    print("written to", OUT)


if __name__ == "__main__":
    main()
