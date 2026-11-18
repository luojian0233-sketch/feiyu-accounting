"""生成头卡右上角的「标注徽章」：三套非肥鱼皮肤 × 四个页面，各一套默认/触碰帧。

每套皮肤的徽章沿用该皮肤自己的装饰语言：
  - 清透薄荷：露珠玻璃圆章 + 嫩芽系图标，待机呼吸、点一下弹缩并荡开水波。
  - 小票账本：带齿孔的小纸签 + 墨绿印章图标，待机轻摆，点一下印章砸下来压出印子。
  - 纸感极简：不画底，只用墨线图标 + 裁切角标 + 一道自己画出来的下划线。

统一规格：168px（56dp @3x）正方形、透明底、4 倍超采样后 LANCZOS 缩回、
64 色 FASTOCTREE 量化（和头卡贴纸同一套流水线）。

输出：`app/src/main/assets/header_badge/<skin>_<page>/{idle,tap}/NN.png`

用法（仓库根目录）：
    python tools/gen_header_badges.py            # 只出资源
    python tools/gen_header_badges.py --preview  # 顺带出 GIF 预览与拼图
"""

from __future__ import annotations

import argparse
import math
import os
import shutil

from PIL import Image, ImageDraw, ImageFilter

SIZE = 168
SS = 4
CANVAS = SIZE * SS

IDLE_FRAMES = 16
IDLE_MILLIS = 100
TAP_FRAMES = 16
TAP_MILLIS = [90, 80, 60, 60, 60, 70, 70, 80, 80, 90, 90, 100, 100, 110, 110, 120]

SKINS = ("mint", "receipt", "paper")
PAGES = ("home", "ledger", "statistics", "settings")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSET_ROOT = os.path.join(ROOT, "app/src/main/assets/header_badge")
PREVIEW_ROOT = os.path.join(ROOT, "tools/badge_previews")


# ---------------------------------------------------------------- 画图小工具

def u(x: float) -> float:
    """把 0~1 的相对坐标换成超采样画布坐标。"""
    return x * CANVAS


def layer() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def stroke_line(draw: ImageDraw.ImageDraw, points, width: float, color) -> None:
    """圆头折线：PIL 的 line 没有 round cap，用端点小圆补上。"""
    pts = [(u(x), u(y)) for x, y in points]
    draw.line(pts, fill=color, width=int(width), joint="curve")
    r = width / 2
    for x, y in pts:
        draw.ellipse((x - r, y - r, x + r, y + r), fill=color)


def bezier(p0, p1, p2, steps: int = 26):
    out = []
    for i in range(steps + 1):
        t = i / steps
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t ** 2 * p2[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t ** 2 * p2[1]
        out.append((x, y))
    return out


def leaf(draw: ImageDraw.ImageDraw, base, tip, belly: float, color, side: int = 1) -> None:
    """梭形叶片：一侧饱鼓、一侧微收，两条控制点分开，否则会退化成一条线。"""
    mx, my = (base[0] + tip[0]) / 2, (base[1] + tip[1]) / 2
    dx, dy = tip[0] - base[0], tip[1] - base[1]
    fat = (mx - dy * belly * side, my + dx * belly * side)
    thin = (mx + dy * belly * 0.30 * side, my - dx * belly * 0.30 * side)
    pts = bezier(base, fat, tip) + bezier(tip, thin, base)
    draw.polygon([(u(x), u(y)) for x, y in pts], fill=color)


def vertical_gradient(top, bottom) -> Image.Image:
    grad = Image.new("RGBA", (1, CANVAS))
    for y in range(CANVAS):
        k = y / (CANVAS - 1)
        grad.putpixel((0, y), tuple(int(top[i] + (bottom[i] - top[i]) * k) for i in range(4)))
    return grad.resize((CANVAS, CANVAS), Image.BILINEAR)


def transform(src: Image.Image, scale: float = 1.0, dy: float = 0.0, rotate: float = 0.0,
              alpha: float = 1.0, center=(0.5, 0.5)) -> Image.Image:
    out = src
    if abs(rotate) > 0.01:
        out = out.rotate(rotate, resample=Image.BICUBIC, center=(u(center[0]), u(center[1])))
    if abs(scale - 1.0) > 0.001:
        w, h = out.size
        out = out.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.BICUBIC)
        canvas = Image.new("RGBA", src.size, (0, 0, 0, 0))
        canvas.paste(out, ((src.size[0] - out.size[0]) // 2, (src.size[1] - out.size[1]) // 2), out)
        out = canvas
    if abs(dy) > 0.0001:
        shifted = Image.new("RGBA", src.size, (0, 0, 0, 0))
        shifted.paste(out, (0, int(u(dy))), out)
        out = shifted
    if alpha < 1.0:
        r, g, b, a = out.split()
        out = Image.merge("RGBA", (r, g, b, a.point(lambda v: int(v * alpha))))
    return out


def over(base: Image.Image, top: Image.Image) -> Image.Image:
    base.alpha_composite(top)
    return base


def gear(draw: ImageDraw.ImageDraw, cx: float, cy: float, r: float, teeth: int, color) -> None:
    """8 齿齿轮：外圈梯形齿 + 实心轮盘 + 挖空轴孔。"""
    pts = []
    for i in range(teeth):
        a0 = math.pi * 2 * i / teeth
        step = math.pi * 2 / teeth
        for frac, radius in ((0.0, r), (0.18, r), (0.26, r * 1.42),
                             (0.46, r * 1.42), (0.54, r), (0.82, r)):
            a = a0 + step * frac
            pts.append((cx + math.cos(a) * radius, cy + math.sin(a) * radius))
    draw.polygon([(u(x), u(y)) for x, y in pts], fill=color)
    draw.ellipse((u(cx - r * 0.30), u(cy - r * 0.30), u(cx + r * 0.30), u(cy + r * 0.30)), fill=(0, 0, 0, 0))


# ---------------------------------------------------------------- 图标（墨稿）

MINT_INK = (37, 107, 84, 255)
MINT_SOFT = (78, 163, 127, 255)
RECEIPT_INK = (53, 96, 79, 255)
RECEIPT_SOFT = (120, 146, 131, 255)
PAPER_INK = (35, 76, 60, 255)
PAPER_SOFT = (35, 76, 60, 120)

STROKE = 0.030

# 纸签内腔比圆章窄，图标要放大一点才在 56dp 下看得清
GLYPH_SCALE = {"mint": 1.06, "receipt": 1.10, "paper": 1.00}

# 纸签内腔比圆章窄，图标要放大一点才在 56dp 下看得清
GLYPH_SCALE = {"mint": 1.06, "receipt": 1.10, "paper": 1.00}


def sun(draw: ImageDraw.ImageDraw, ink, soft, r: float = 0.105, ray: float = 0.062) -> None:
    """今天 = 一枚太阳：中心圆 + 八道短光。四套皮肤共用这个形状，只换笔画。"""
    draw.ellipse((u(0.5 - r), u(0.5 - r), u(0.5 + r), u(0.5 + r)), fill=ink)
    for i in range(8):
        a = math.pi * 2 * i / 8 + math.pi / 8
        x0, y0 = 0.5 + math.cos(a) * (r + 0.030), 0.5 + math.sin(a) * (r + 0.030)
        x1, y1 = 0.5 + math.cos(a) * (r + 0.030 + ray), 0.5 + math.sin(a) * (r + 0.030 + ray)
        stroke_line(draw, [(x0, y0), (x1, y1)], u(0.024), soft)


def sheet(draw: ImageDraw.ImageDraw, ink, soft, w: float = 0.026) -> None:
    """账单 = 一张单据：抬头一横、三条条目、下沿撕口。"""
    left, right, top, bottom = 0.345, 0.655, 0.275, 0.715
    draw.rounded_rectangle((u(left), u(top), u(right), u(bottom)), radius=u(0.014),
                           outline=ink, width=int(u(w)))
    stroke_line(draw, [(0.395, 0.350), (0.605, 0.350)], u(0.022), ink)
    for y, x1 in ((0.440, 0.605), (0.525, 0.560)):
        stroke_line(draw, [(0.395, y), (x1, y)], u(0.018), soft)
    teeth = []
    steps = 3
    span = right - left
    for i in range(steps):
        x0 = left + span / steps * i
        teeth += [(x0, bottom), (x0 + span / steps / 2, bottom - 0.045), (x0 + span / steps, bottom)]
    draw.polygon([(u(x), u(y)) for x, y in teeth], fill=ink)


def bars(draw: ImageDraw.ImageDraw, ink, soft) -> None:
    """统计 = 三根柱子，最后一根用重色点题。"""
    for x, h, color in ((0.335, 0.150, soft), (0.455, 0.260, soft), (0.575, 0.380, ink)):
        draw.rounded_rectangle((u(x), u(0.705 - h), u(x + 0.090), u(0.705)), radius=u(0.022), fill=color)


def gear(draw: ImageDraw.ImageDraw, cx: float, cy: float, r: float, teeth: int, color) -> None:
    """8 齿齿轮：外圈梯形齿 + 实心轮盘 + 挖空轴孔。"""
    pts = []
    for i in range(teeth):
        a0 = math.pi * 2 * i / teeth
        step = math.pi * 2 / teeth
        for frac, radius in ((0.0, r), (0.16, r), (0.24, r * 1.38),
                             (0.48, r * 1.38), (0.56, r), (0.84, r)):
            a = a0 + step * frac
            pts.append((cx + math.cos(a) * radius, cy + math.sin(a) * radius))
    draw.polygon([(u(x), u(y)) for x, y in pts], fill=color)
    draw.ellipse((u(cx - r * 0.32), u(cy - r * 0.32), u(cx + r * 0.32), u(cy + r * 0.32)), fill=(0, 0, 0, 0))


def sliders(draw: ImageDraw.ImageDraw, ink, soft) -> None:
    """设置（极简版）= 两条滑块。"""
    for y, knob, color in ((0.395, 0.585, ink), (0.600, 0.430, ink)):
        stroke_line(draw, [(0.300, y), (0.700, y)], u(0.022), soft)
        draw.ellipse((u(knob - 0.060), u(y - 0.060), u(knob + 0.060), u(y + 0.060)), fill=color)


def glyph_mint(draw: ImageDraw.ImageDraw, page: str) -> None:
    if page == "home":
        sun(draw, MINT_INK, MINT_SOFT, r=0.100, ray=0.070)
    elif page == "ledger":
        sheet(draw, MINT_INK, MINT_SOFT)
    elif page == "statistics":
        bars(draw, MINT_INK, MINT_SOFT)
    else:
        sliders(draw, MINT_INK, MINT_SOFT)


def glyph_receipt(draw: ImageDraw.ImageDraw, page: str) -> None:
    if page == "home":
        sun(draw, RECEIPT_INK, RECEIPT_SOFT, r=0.095, ray=0.062)
    elif page == "ledger":
        sheet(draw, RECEIPT_INK, RECEIPT_SOFT, w=0.023)
    elif page == "statistics":
        bars(draw, RECEIPT_INK, RECEIPT_SOFT)
    else:
        gear(draw, 0.5, 0.5, 0.132, 8, RECEIPT_INK)


def glyph_paper(draw: ImageDraw.ImageDraw, page: str) -> None:
    if page == "home":
        sun(draw, PAPER_INK, PAPER_SOFT, r=0.088, ray=0.060)
    elif page == "ledger":
        for y, x1 in ((0.370, 0.700), (0.505, 0.625), (0.640, 0.545)):
            stroke_line(draw, [(0.300, y), (x1, y)], u(0.032), PAPER_INK)
    elif page == "statistics":
        bars(draw, PAPER_INK, PAPER_SOFT)
    else:
        sliders(draw, PAPER_INK, PAPER_SOFT)


# ---------------------------------------------------------------- 三套底

def mint_base(scale: float = 1.0, glow: float = 0.0) -> Image.Image:
    """露珠圆章：上浅下深的薄荷玻璃 + 内圈细环 + 左上月牙高光。

    高光走"细月牙 + 小圆点"，不做大范围高斯模糊——模糊叠在浅色上会发灰发脏。
    """
    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    cx, cy, r = 0.5, 0.5, 0.420
    body = vertical_gradient((252, 255, 253, 255), (212, 237, 227, 255))
    mask = Image.new("L", (CANVAS, CANVAS), 0)
    ImageDraw.Draw(mask).ellipse((u(cx - r), u(cy - r), u(cx + r), u(cy + r)), fill=255)
    img.paste(body, (0, 0), mask)

    draw = ImageDraw.Draw(img)
    # 左上月牙：只取内圈的一段弧，宽度收在 0.02，像玻璃边缘的反光
    draw.arc((u(cx - r + 0.055), u(cy - r + 0.055), u(cx + r - 0.055), u(cy + r - 0.055)),
             start=185, end=290, fill=(255, 255, 255, 190), width=int(u(0.020)))
    draw.ellipse((u(0.245), u(0.245), u(0.305), u(0.305)), fill=(255, 255, 255, 210))
    # 右下角一点薄荷阴影，让球体有体积
    draw.arc((u(cx - r + 0.030), u(cy - r + 0.030), u(cx + r - 0.030), u(cy + r - 0.030)),
             start=20, end=95, fill=(150, 205, 182, 90), width=int(u(0.016)))

    if glow > 0:
        spark, sdraw = layer()
        sdraw.ellipse((u(0.320), u(0.320), u(0.680), u(0.680)), fill=(255, 255, 255, int(110 * glow)))
        spark = spark.filter(ImageFilter.GaussianBlur(u(0.07)))
        img = over(img, Image.composite(spark, Image.new("RGBA", img.size, (0, 0, 0, 0)), mask))

    draw = ImageDraw.Draw(img)
    draw.ellipse((u(cx - r), u(cy - r), u(cx + r), u(cy + r)),
                 outline=(74, 148, 122, 220), width=int(u(0.024)))
    return transform(img, scale=scale)


def receipt_base(scale: float = 1.0, dy: float = 0.0, rotate: float = 0.0) -> Image.Image:
    """小纸签：上下齿孔 + 发丝细框，不加投影（投影在小尺寸会糊成一圈脏边）。"""
    left, right, top, bottom = 0.285, 0.715, 0.165, 0.835
    mask = Image.new("L", (CANVAS, CANVAS), 0)
    mdraw = ImageDraw.Draw(mask)
    mdraw.rectangle((u(left), u(top), u(right), u(bottom)), fill=255)
    step = (right - left) / 5
    for i in range(1, 5):
        x = left + step * i
        mdraw.ellipse((u(x - 0.024), u(top - 0.024), u(x + 0.024), u(top + 0.024)), fill=0)
        mdraw.ellipse((u(x - 0.024), u(bottom - 0.024), u(x + 0.024), u(bottom + 0.024)), fill=0)

    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    paper = Image.new("RGBA", (CANVAS, CANVAS), (252, 249, 237, 255))
    img.paste(paper, (0, 0), mask)
    draw = ImageDraw.Draw(img)
    draw.rectangle((u(left), u(top), u(right), u(bottom)), outline=(211, 203, 182, 255), width=int(u(0.010)))
    return transform(img, scale=scale, dy=dy, rotate=rotate)


def paper_base(scale: float = 1.0, dy: float = 0.0) -> Image.Image:
    """极简：不画底，只在四角点四道裁切角标（往外挪，避让下划线）。"""
    img, draw = layer()
    tick = 0.050
    color = (150, 160, 145, 150)
    for x, sx in ((0.145, 1), (0.855, -1)):
        for y, sy in ((0.145, 1), (0.855, -1)):
            stroke_line(draw, [(x, y), (x + tick * sx, y)], u(0.013), color)
            stroke_line(draw, [(x, y), (x, y + tick * sy)], u(0.013), color)
    return transform(img, scale=scale, dy=dy)


# ---------------------------------------------------------------- 两个状态

def idle_frame(skin: str, page: str, i: int) -> Image.Image:
    t = i / IDLE_FRAMES
    breath = math.sin(t * math.pi * 2)

    if skin == "mint":
        base = mint_base(scale=1.0 + 0.012 * breath, glow=max(0.0, math.sin(t * math.pi * 2)) * 0.55)
        glyph, gdraw = layer()
        glyph_mint(gdraw, page)
        return over(base, transform(glyph, scale=GLYPH_SCALE["mint"] * (1.0 + 0.018 * breath), dy=-0.004 * breath))

    if skin == "receipt":
        base = receipt_base(rotate=0.8 * breath, dy=0.004 * breath)
        glyph, gdraw = layer()
        glyph_receipt(gdraw, page)
        out = over(base, transform(glyph, scale=GLYPH_SCALE["receipt"] * (1.0 + 0.012 * breath)))
        blink = 0.0 if 0.58 < t < 0.70 else 1.0
        dot, ddraw = layer()
        ddraw.ellipse((u(0.650), u(0.690), u(0.692), u(0.732)), fill=(53, 96, 79, int(170 * blink)))
        return over(out, dot)

    base = paper_base(dy=0.002 * breath)
    glyph, gdraw = layer()
    glyph_paper(gdraw, page)
    out = over(base, transform(glyph, alpha=0.88 + 0.12 * (breath * 0.5 + 0.5)))
    progress = min(1.0, t / 0.42)
    if progress > 0.03:
        line, ldraw = layer()
        stroke_line(ldraw, [(0.270, 0.800), (0.270 + 0.460 * progress, 0.800)], u(0.026), (35, 76, 60, 230))
        out = over(out, line)
    return out


def tap_frame(skin: str, page: str, i: int) -> Image.Image:
    t = i / (TAP_FRAMES - 1)
    if t < 0.18:
        k = t / 0.18
        scale, dy, ring = 1.0 - 0.09 * k, 0.008 * k, -1.0
    elif t < 0.45:
        k = (t - 0.18) / 0.27
        scale, dy, ring = 0.91 + 0.19 * k, 0.008 * (1 - k), k
    elif t < 0.78:
        k = (t - 0.45) / 0.33
        scale, dy, ring = 1.10 - 0.10 * k, -0.005 * (1 - k), 0.55
    else:
        k = (t - 0.78) / 0.22
        scale, dy, ring = 1.0 + 0.010 * math.sin(k * math.pi), 0.0, 0.0

    if skin == "mint":
        out = mint_base(scale=scale, glow=0.9 if 0.25 < t < 0.6 else 0.0)
        if ring >= 0:
            r = 0.42 + 0.28 * ring
            halo, hdraw = layer()
            hdraw.ellipse((u(0.5 - r), u(0.5 - r), u(0.5 + r), u(0.5 + r)),
                          outline=(127, 200, 172, int(180 * (1 - ring))), width=int(u(0.016)))
            out = over(out, halo)
        glyph, gdraw = layer()
        glyph_mint(gdraw, page)
        out = over(out, transform(glyph, scale=scale * 1.02 * GLYPH_SCALE["mint"], dy=dy * 0.5))
        if 0.25 < t < 0.72:
            k = (t - 0.25) / 0.47
            sparks, sdraw = layer()
            for angle in (-2.3, -0.6, 0.7):
                x = 0.5 + math.cos(angle) * (0.40 + 0.14 * k)
                y = 0.5 + math.sin(angle) * (0.40 + 0.14 * k)
                r = 0.022 * (1 - k)
                sdraw.ellipse((u(x - r), u(y - r), u(x + r), u(y + r)),
                              fill=(110, 190, 160, int(210 * (1 - k))))
            out = over(out, sparks)
        return out

    if skin == "receipt":
        out = receipt_base(scale=scale, dy=dy + (0.010 if 0.18 < t < 0.45 else 0.0))
        glyph, gdraw = layer()
        glyph_receipt(gdraw, page)
        out = over(out, transform(glyph, scale=scale * GLYPH_SCALE["receipt"], dy=dy * 0.4))
        if 0.18 < t < 0.90:
            if t < 0.45:
                k = (t - 0.18) / 0.27
                stamp_scale, stamp_alpha = 1.60 - 0.60 * k, k
            else:
                k = (t - 0.45) / 0.45
                stamp_scale, stamp_alpha = 1.0, max(0.0, 1.0 - k)
            r = 0.240
            stamp, sdraw = layer()
            sdraw.ellipse((u(0.5 - r), u(0.5 - r), u(0.5 + r), u(0.5 + r)),
                          outline=(53, 96, 79, 200), width=int(u(0.020)))
            sdraw.ellipse((u(0.5 - r + 0.045), u(0.5 - r + 0.045), u(0.5 + r - 0.045), u(0.5 + r - 0.045)),
                          outline=(53, 96, 79, 110), width=int(u(0.010)))
            out = over(out, transform(stamp, scale=stamp_scale, alpha=stamp_alpha))
        if 0.30 < t < 0.66:
            k = (t - 0.30) / 0.36
            splash, pdraw = layer()
            for angle in (-2.1, -0.9, 0.35, 2.5):
                x = 0.5 + math.cos(angle) * (0.28 + 0.09 * k)
                y = 0.5 + math.sin(angle) * (0.28 + 0.09 * k)
                r = 0.017 * (1 - k)
                pdraw.ellipse((u(x - r), u(y - r), u(x + r), u(y + r)),
                              fill=(53, 96, 79, int(190 * (1 - k))))
            out = over(out, splash)
        return out

    out = paper_base(scale=1.0, dy=dy * 0.5)
    glyph, gdraw = layer()
    glyph_paper(gdraw, page)
    out = over(out, transform(glyph, scale=1.0 + 0.03 * (1 - min(1.0, t / 0.5)), dy=dy * 0.6))
    if t < 0.15:
        progress = 0.55 * (t / 0.15)
    elif t < 0.55:
        progress = 0.55 + 0.55 * ((t - 0.15) / 0.40)
    else:
        progress = 1.10 - 0.14 * min(1.0, (t - 0.55) / 0.45)
    if progress > 0.02:
        line, ldraw = layer()
        stroke_line(ldraw, [(0.270, 0.800), (0.270 + 0.460 * progress, 0.800)], u(0.032), (35, 76, 60, 245))
        out = over(out, line)
    if 0.10 < t < 0.62:
        k = (t - 0.10) / 0.52
        r = 0.032 * (1 - k) + 0.006
        dot, ddraw = layer()
        ddraw.ellipse((u(0.270 - r), u(0.800 - r), u(0.270 + r), u(0.800 + r)),
                      fill=(35, 76, 60, int(235 * (1 - k))))
        out = over(out, dot)
    return out


# ---------------------------------------------------------------- 输出

def downsample(img: Image.Image) -> Image.Image:
    return img.resize((SIZE, SIZE), Image.LANCZOS)


def write_assets() -> None:
    if os.path.isdir(ASSET_ROOT):
        shutil.rmtree(ASSET_ROOT)
    for skin in SKINS:
        for page in PAGES:
            name = f"{skin}_{page}"
            for state, count, make in (
                ("idle", IDLE_FRAMES, idle_frame),
                ("tap", TAP_FRAMES, tap_frame),
            ):
                out_dir = os.path.join(ASSET_ROOT, name, state)
                os.makedirs(out_dir)
                for i in range(count):
                    frame = downsample(make(skin, page, i))
                    frame.quantize(colors=96, method=Image.FASTOCTREE).save(
                        os.path.join(out_dir, f"{i:02d}.png"), optimize=True,
                    )


def write_previews() -> None:
    os.makedirs(PREVIEW_ROOT, exist_ok=True)
    sheet = Image.new("RGBA", (SIZE * 4 + 50, SIZE * 3 + 40), (243, 239, 228, 255))
    # 实际显示尺寸（56dp @3x = 168px 缩到 56px）单独拼一张，检查小尺寸下的可读性
    small = Image.new("RGBA", (56 * 12 + 30, 56 * 3 + 20), (248, 244, 233, 255))
    for row, skin in enumerate(SKINS):
        for col, page in enumerate(PAGES):
            tap = [downsample(tap_frame(skin, page, i)) for i in range(TAP_FRAMES)]
            tap[0].save(os.path.join(PREVIEW_ROOT, f"{skin}_{page}_tap.gif"), save_all=True,
                        append_images=tap[1:], duration=TAP_MILLIS, loop=0, disposal=2)
            idle = [downsample(idle_frame(skin, page, i)) for i in range(IDLE_FRAMES)]
            idle[0].save(os.path.join(PREVIEW_ROOT, f"{skin}_{page}_idle.gif"), save_all=True,
                         append_images=idle[1:], duration=IDLE_MILLIS, loop=0, disposal=2)
            sheet.alpha_composite(tap[-1], (10 + col * (SIZE + 10), 10 + row * (SIZE + 10)))
            thumb = tap[-1].resize((56, 56), Image.LANCZOS)
            small.alpha_composite(thumb, (10 + (row * 4 + col) % 12 * 56 + 4, 10 + row * 56))
    sheet.save(os.path.join(PREVIEW_ROOT, "contact_sheet.png"))
    small.save(os.path.join(PREVIEW_ROOT, "contact_sheet_small.png"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", action="store_true", help="同时输出 GIF 预览与拼图")
    args = parser.parse_args()
    write_assets()
    print(f"frames -> {ASSET_ROOT}")
    if args.preview:
        write_previews()
        print(f"previews -> {PREVIEW_ROOT}")


if __name__ == "__main__":
    main()
