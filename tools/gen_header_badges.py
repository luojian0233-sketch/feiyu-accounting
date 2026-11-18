"""生成头卡右上角的「标注徽章」：三套非肥鱼皮肤 × 四个页面，各一套默认/触碰帧。

每个徽章不是一枚图标，而是一个**有材质的小物件**，在点的那一下做出它自己的动作。
用户逐个挑的清单：

  清透薄荷（玻璃为主）  首页 叶尖露珠 / 账单 玻璃零钱罐 / 统计 玻璃量杯 / 设置 薄荷玻璃旋钮
  小票账本（纸、铁皮）  首页 打卡钟   / 账单 小票打印机 / 统计 钢笔折线 / 设置 铁皮拨动开关
  纸感极简（墨线、留白）首页 折角便签 / 账单 线装账本   / 统计 墨线折线 / 设置 线上墨点

统一规格：168px（56dp @3x）正方形、透明底、4 倍超采样后 LANCZOS 缩回、
96 色 FASTOCTREE 量化（和头卡贴纸同一套流水线）。

每个物件只有一个参数 k（0~1）：待机时 k 缓慢呼吸，点一下 k 走一遍物件自己的动作。

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


def smooth(x: float) -> float:
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


def ease_out(x: float) -> float:
    x = max(0.0, min(1.0, x))
    return 1 - (1 - x) ** 3


def stroke_line(draw: ImageDraw.ImageDraw, points, width: float, color) -> None:
    """圆头折线：PIL 的 line 没有 round cap，用端点小圆补上。"""
    pts = [(u(x), u(y)) for x, y in points]
    draw.line(pts, fill=color, width=int(width), joint="curve")
    r = width / 2
    for x, y in pts:
        draw.ellipse((x - r, y - r, x + r, y + r), fill=color)


def bezier(p0, p1, p2, steps: int = 24):
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


def partial_path(points, k: float):
    """折线按长度取前 k 段，末点多出来的部分插值出一段，用来做「一笔画出来」。"""
    if k <= 0:
        return [points[0]]
    if k >= 1:
        return list(points)
    lens = [math.dist(points[i], points[i + 1]) for i in range(len(points) - 1)]
    target = sum(lens) * k
    out = [points[0]]
    acc = 0.0
    for i, seg in enumerate(lens):
        if acc + seg <= target:
            out.append(points[i + 1])
            acc += seg
        else:
            r = (target - acc) / seg
            p0, p1 = points[i], points[i + 1]
            out.append((p0[0] + (p1[0] - p0[0]) * r, p0[1] + (p1[1] - p0[1]) * r))
            break
    return out


# ---------------------------------------------------------------- 调色

MINT_INK = (37, 107, 84, 255)
MINT_SOFT = (86, 172, 138, 255)
MINT_EDGE = (74, 148, 122, 240)
MINT_GLASS = (240, 252, 247, 240)
MINT_LIQUID = (126, 199, 171, 225)

RECEIPT_INK = (53, 96, 79, 255)
RECEIPT_SOFT = (120, 146, 131, 230)
RECEIPT_PAPER = (253, 250, 240, 255)
RECEIPT_LINE = (211, 203, 182, 255)

PAPER_INK = (35, 76, 60, 255)
PAPER_SOFT = (35, 76, 60, 130)
PAPER_SHEET = (252, 250, 243, 255)

# 物件在各自底板里的占比：圆章里留白多，物件要放大才不显空
OBJECT_SCALE = {"mint": 1.07, "receipt": 1.14, "paper": 1.02}

# 物件在各自底板里的占比：圆章里留白多，物件要放大才不显空
OBJECT_SCALE = {"mint": 1.07, "receipt": 1.14, "paper": 1.02}


# ---------------------------------------------------------------- 物件：清透薄荷

def _drop(draw: ImageDraw.ImageDraw, cx: float, cy: float, r: float, alpha: float) -> None:
    """一滴玻璃露珠：圆身 + 上尖 + 左上高光。"""
    if alpha <= 3:
        return
    a = int(alpha)
    body = (243, 252, 249, a)
    edge = (44, 116, 90, a)
    # 上面收成一段细颈，连到叶尖——宽三角会盖住叶尖，读成「球上长了个尖」
    draw.polygon([(u(cx - r * 0.30), u(cy - r * 0.70)), (u(cx + r * 0.30), u(cy - r * 0.70)),
                  (u(cx + r * 0.10), u(cy - r * 1.30)), (u(cx - r * 0.10), u(cy - r * 1.30))],
                 fill=body)
    draw.ellipse((u(cx - r), u(cy - r), u(cx + r), u(cy + r)),
                 fill=body, outline=edge, width=max(2, int(u(0.017))))
    # 水珠的厚度：右下角一道内阴影，左上一点高光，才不会读成空心圈
    if r > 0.02:
        draw.arc((u(cx - r * 0.86), u(cy - r * 0.86), u(cx + r * 0.86), u(cy + r * 0.86)),
                 20, 130, fill=(120, 190, 166, int(a * 0.75)), width=max(1, int(u(0.010))))
    draw.ellipse((u(cx - r * 0.62), u(cy - r * 0.56), u(cx - r * 0.10), u(cy - r * 0.04)),
                 fill=(255, 255, 255, a))


def mint_home(draw: ImageDraw.ImageDraw, k: float) -> None:
    """叶尖挂着一滴露：叶子垂下来、尖朝下，露珠挂在最低处，胀大、坠落、再聚。

    露珠要压过叶子当主角——小圆点缩到 56dp 就是个空心圈，认不出是水。
    """
    stroke_line(draw, [(0.330, 0.300), (0.398, 0.336), (0.442, 0.392)], u(0.015), MINT_INK)
    leaf(draw, (0.442, 0.392), (0.544, 0.582), 0.66, MINT_SOFT, side=-1)
    stroke_line(draw, [(0.452, 0.404), (0.536, 0.566)], u(0.009), (255, 255, 255, 145))
    tip_x, tip_y = 0.544, 0.590
    if k < 0.42:
        r = 0.042 + 0.044 * smooth(k / 0.42)
        _drop(draw, tip_x, tip_y + r * 0.92, r, 255)
    elif k < 0.66:
        p2 = (k - 0.42) / 0.24
        r = 0.086 - 0.012 * p2
        cy = tip_y + r + 0.230 * p2 * p2
        _drop(draw, tip_x, cy, r, 255 if p2 < 0.72 else 255 * (1 - (p2 - 0.72) / 0.28))
    else:
        p2 = (k - 0.66) / 0.34
        r = 0.040 + 0.046 * smooth(p2)
        _drop(draw, tip_x, tip_y + r * 0.92, r, 255)


def mint_ledger(draw: ImageDraw.ImageDraw, k: float) -> None:
    """玻璃零钱罐：一枚硬币掉进去落在硬币堆上。"""
    left, right, top, bottom = 0.352, 0.648, 0.392, 0.758
    draw.rounded_rectangle((u(left), u(top), u(right), u(bottom)), radius=u(0.058),
                           fill=(247, 254, 251, 135), outline=MINT_EDGE, width=int(u(0.020)))
    # 罐口厚边
    draw.rounded_rectangle((u(left - 0.014), u(top - 0.024), u(right + 0.014), u(top + 0.012)),
                           radius=u(0.014), fill=(206, 237, 225, 248), outline=MINT_EDGE,
                           width=int(u(0.012)))
    # 内壁高光
    stroke_line(draw, [(0.382, 0.470), (0.382, 0.660)], u(0.013), (255, 255, 255, 205))

    def coin(cx: float, cy: float, r: float, face, edge) -> None:
        draw.ellipse((u(cx - r), u(cy - r * 0.94), u(cx + r), u(cy + r * 0.94)), fill=face,
                     outline=edge, width=int(u(0.010)))
        draw.ellipse((u(cx - r * 0.52), u(cy - r * 0.50), u(cx + r * 0.52), u(cy + r * 0.50)),
                     outline=edge[:3] + (150,), width=int(u(0.007)))

    for cx, cy, r in ((0.418, 0.700, 0.048), (0.500, 0.688, 0.050), (0.582, 0.702, 0.046),
                      (0.458, 0.722, 0.046), (0.544, 0.724, 0.044)):
        coin(cx, cy, r, (178, 226, 205, 248), (52, 120, 96, 245))
    # 落下的那枚
    if k < 0.60:
        cy = 0.150 + (0.668 - 0.150) * smooth(k / 0.60)
        squash = 1.0
    else:
        cy, squash = 0.668, 1.0 - 0.20 * math.sin((k - 0.60) / 0.40 * math.pi)
    coin(0.500, cy, 0.046, (206, 240, 225, 252), (48, 116, 92, 252))


def mint_statistics(draw: ImageDraw.ImageDraw, k: float) -> None:
    """玻璃量杯：薄荷液面沿着杯壁涨一格再落回。"""
    top_y, bot_y = 0.292, 0.756
    xl_top, xr_top = 0.378, 0.622
    xl_bot, xr_bot = 0.402, 0.598

    def x_at(y: float) -> tuple[float, float]:
        r = (y - top_y) / (bot_y - top_y)
        return xl_top + (xl_bot - xl_top) * r, xr_top + (xr_bot - xr_top) * r

    level = bot_y - 0.020 - 0.320 * k
    lx, rx = x_at(level)
    draw.polygon([(u(lx), u(level)), (u(rx), u(level)), (u(xr_bot), u(bot_y - 0.014)),
                  (u(xl_bot), u(bot_y - 0.014))], fill=MINT_LIQUID)
    # 液面亮边
    stroke_line(draw, [(lx + 0.006, level), (rx - 0.006, level)], u(0.012), (232, 252, 244, 235))
    # 杯壁
    stroke_line(draw, [(xl_top, top_y), (xl_bot, bot_y), (xr_bot, bot_y), (xr_top, top_y)],
                u(0.018), MINT_EDGE)
    # 刻度
    for i, y in enumerate((0.430, 0.520, 0.610)):
        x0, _ = x_at(y)
        stroke_line(draw, [(x0 + 0.012, y), (x0 + 0.056 - i * 0.006, y)], u(0.010), MINT_INK)
    # 气泡
    for i in range(3):
        by = level + 0.040 + 0.055 * i
        if by > bot_y - 0.030:
            continue
        r = 0.010 + 0.004 * ((i + k * 3) % 3)
        bx = 0.470 + 0.030 * i
        draw.ellipse((u(bx - r), u(by - r), u(bx + r), u(by + r)), fill=(255, 255, 255, 170))


def mint_settings(draw: ImageDraw.ImageDraw, k: float) -> None:
    """薄荷玻璃旋钮：拧过一格，圆弧刻度上的指针对到下一位。

    刻度只铺顶部一段弧——整圈 12 道会被一眼读成钟表，和打卡钟撞车。
    """
    cx, cy = 0.500, 0.545
    angle = -math.pi / 2 + (-0.60 + 1.20 * k)
    # 顶部弧线刻度：7 道，正在对的那道加重
    for i in range(7):
        a = math.radians(202 + i * 22.7)
        active = abs(math.degrees(angle) % 360 - (202 + i * 22.7)) < 11
        stroke_line(draw, [(cx + math.cos(a) * 0.286, cy + math.sin(a) * 0.286),
                           (cx + math.cos(a) * (0.336 if active else 0.318), cy + math.sin(a) * (0.336 if active else 0.318))],
                    u(0.016 if active else 0.011),
                    MINT_INK if active else (140, 196, 174, 190))
    # 钮身：玻璃盘 + 盘缘滚花
    draw.ellipse((u(cx - 0.205), u(cy - 0.205), u(cx + 0.205), u(cy + 0.205)),
                 fill=(226, 246, 238, 250), outline=MINT_EDGE, width=int(u(0.022)))
    for i in range(40):
        a = math.pi * 2 * i / 40
        stroke_line(draw, [(cx + math.cos(a) * 0.172, cy + math.sin(a) * 0.172),
                           (cx + math.cos(a) * 0.198, cy + math.sin(a) * 0.198)],
                    u(0.009), (104, 168, 144, 195))
    draw.ellipse((u(cx - 0.156), u(cy - 0.156), u(cx + 0.156), u(cy + 0.156)),
                 fill=(243, 251, 247, 255), outline=(168, 214, 196, 200), width=int(u(0.008)))
    # 粗指针：从中心伸到盘缘的楔形，不做细针（细针＝表针）
    tip_x, tip_y = cx + math.cos(angle) * 0.186, cy + math.sin(angle) * 0.186
    nx, ny = -math.sin(angle), math.cos(angle)
    draw.polygon([(u(cx + nx * 0.042), u(cy + ny * 0.042)),
                  (u(cx - nx * 0.042), u(cy - ny * 0.042)),
                  (u(tip_x + nx * 0.014), u(tip_y + ny * 0.014)),
                  (u(tip_x - nx * 0.014), u(tip_y - ny * 0.014))], fill=MINT_INK)
    # 左上高光
    draw.arc((u(cx - 0.176), u(cy - 0.176), u(cx + 0.176), u(cy + 0.176)),
             200, 286, fill=(255, 255, 255, 235), width=int(u(0.014)))


def receipt_home(draw: ImageDraw.ImageDraw, k: float) -> None:
    """打卡钟：一张工时卡插进去，机身圆表盘上的指针跟着走到位。"""
    # 工时卡（先画，机身盖住下半截）
    card_top = 0.150 + 0.160 * ease_out(k)
    draw.rounded_rectangle((u(0.396), u(card_top), u(0.604), u(card_top + 0.320)), radius=u(0.012),
                           fill=RECEIPT_PAPER, outline=(70, 110, 92, 255), width=int(u(0.012)))
    for i, yy in enumerate((card_top + 0.052, card_top + 0.098, card_top + 0.144)):
        if yy < 0.404:
            stroke_line(draw, [(0.418, yy), (0.556 - i * 0.018, yy)], u(0.009), RECEIPT_SOFT)
    # 机身
    draw.rounded_rectangle((u(0.318), u(0.408), u(0.682), u(0.780)), radius=u(0.030),
                           fill=RECEIPT_PAPER, outline=RECEIPT_INK, width=int(u(0.020)))
    # 进卡口
    draw.rounded_rectangle((u(0.382), u(0.404), u(0.618), u(0.430)), radius=u(0.009),
                           fill=RECEIPT_INK)
    # 圆表盘：一眼看成钟，比一个空白窗口有用得多
    cx, cy, r = 0.500, 0.606, 0.118
    draw.ellipse((u(cx - r), u(cy - r), u(cx + r), u(cy + r)), fill=(255, 254, 246, 255),
                 outline=RECEIPT_INK, width=int(u(0.014)))
    for i in range(12):
        a = math.pi * 2 * i / 12
        long_tick = i % 3 == 0
        r0 = r - (0.030 if long_tick else 0.020)
        stroke_line(draw, [(cx + math.cos(a) * r0, cy + math.sin(a) * r0),
                           (cx + math.cos(a) * (r - 0.010), cy + math.sin(a) * (r - 0.010))],
                    u(0.008 if long_tick else 0.005), (90, 120, 104, 220))
    # 指针：打卡瞬间从 12 点扫到 9 点方向
    hand = -math.pi / 2 + math.radians(120) * smooth(k)
    stroke_line(draw, [(cx, cy), (cx + math.cos(hand) * r * 0.62, cy + math.sin(hand) * r * 0.62)],
                u(0.011), RECEIPT_INK)
    stroke_line(draw, [(cx, cy), (cx + 0.030, cy - 0.060)], u(0.009), RECEIPT_INK)
    draw.ellipse((u(cx - 0.014), u(cy - 0.014), u(cx + 0.014), u(cy + 0.014)), fill=RECEIPT_INK)


def receipt_ledger(draw: ImageDraw.ImageDraw, k: float) -> None:
    """小票打印机：纸从缝里往上吐一截，撕口是锯齿的。"""
    edge = 0.516 - 0.302 * ease_out(k)
    span = 0.258
    if edge < 0.512:
        draw.rectangle((u(0.371), u(edge), u(0.629), u(0.560)), fill=RECEIPT_PAPER)
        zig = []
        n = 5
        for i in range(n):
            x0 = 0.371 + span / n * i
            zig += [(x0, edge), (x0 + span / n / 2, edge - 0.028), (x0 + span / n, edge)]
        draw.polygon([(u(x), u(y)) for x, y in zig], fill=RECEIPT_PAPER)
        stroke_line(draw, zig, u(0.009), (74, 112, 94, 240))
        for i, yy in enumerate((edge + 0.056, edge + 0.098, edge + 0.140)):
            if yy < 0.520:
                w = (0.196, 0.150, 0.176)[i]
                stroke_line(draw, [(0.398, yy), (0.398 + w, yy)], u(0.010), RECEIPT_SOFT)
    # 机身
    draw.rounded_rectangle((u(0.310), u(0.548), u(0.690), u(0.778)), radius=u(0.026),
                           fill=RECEIPT_PAPER, outline=RECEIPT_INK, width=int(u(0.020)))
    draw.rounded_rectangle((u(0.352), u(0.548), u(0.648), u(0.574)), radius=u(0.008),
                           fill=RECEIPT_INK)
    # 面板上的两道刻度，像出纸长度的标记
    stroke_line(draw, [(0.372, 0.690), (0.628, 0.690)], u(0.008), (190, 182, 160, 200))
    stroke_line(draw, [(0.372, 0.726), (0.520, 0.726)], u(0.008), (190, 182, 160, 160))


def _pen_nib(draw: ImageDraw.ImageDraw, tip, prev, ink) -> None:
    """钢笔尖：一个朝行进方向的楔形 + 一道中缝。"""
    ang = math.atan2(tip[1] - prev[1], tip[0] - prev[0])
    back = 0.062
    half = 0.030
    bx, by = tip[0] - math.cos(ang) * back, tip[1] - math.sin(ang) * back
    nx, ny = -math.sin(ang), math.cos(ang)
    draw.polygon([(u(tip[0]), u(tip[1])),
                  (u(bx + nx * half), u(by + ny * half)),
                  (u(bx - nx * half), u(by - ny * half))], fill=ink)
    stroke_line(draw, [(tip[0] - math.cos(ang) * back * 0.9, tip[1] - math.sin(ang) * back * 0.9),
                       (tip[0], tip[1])], u(0.006), (255, 255, 255, 190))


def receipt_statistics(draw: ImageDraw.ImageDraw, k: float) -> None:
    """钢笔折线：笔尖自左向右划，墨色从湿到干。"""
    pts = [(0.322, 0.690), (0.418, 0.588), (0.500, 0.638), (0.596, 0.452), (0.678, 0.394)]
    # 底线
    for x in (0.322, 0.678):
        stroke_line(draw, [(x, 0.716), (x, 0.748)], u(0.008), (190, 182, 160, 200))
    stroke_line(draw, [(0.322, 0.730), (0.678, 0.730)], u(0.006), (200, 192, 172, 150))
    path = partial_path(pts, ease_out(k))
    if len(path) >= 2:
        stroke_line(draw, path, u(0.024), RECEIPT_INK)
        _pen_nib(draw, path[-1], path[-2], RECEIPT_INK)
    # 已画完的拐点按一枚小墨点
    for i in range(1, len(pts) - 1):
        if k * (len(pts) - 1) >= i + 0.15:
            r = 0.019
            draw.ellipse((u(pts[i][0] - r), u(pts[i][1] - r), u(pts[i][0] + r), u(pts[i][1] + r)),
                         fill=RECEIPT_INK)


def receipt_settings(draw: ImageDraw.ImageDraw, k: float) -> None:
    """铁皮拨杆开关：圆底座 + 一根拨杆，拨杆从左边拍到右边，灯跟着亮。

    正面画「上下两块的跷板」在 56dp 下会糊成一块空面板，所以改成拨杆——
    轮廓一眼就能认出来。
    """
    cx, cy = 0.500, 0.566
    draw.ellipse((u(cx - 0.168), u(cy - 0.168), u(cx + 0.168), u(cy + 0.168)),
                 fill=(252, 249, 240, 255), outline=RECEIPT_INK, width=int(u(0.018)))
    draw.ellipse((u(cx - 0.126), u(cy - 0.126), u(cx + 0.126), u(cy + 0.126)),
                 fill=(238, 232, 216, 255), outline=(168, 160, 140, 235), width=int(u(0.009)))
    # 底座上的 I / O 两道刻字位
    stroke_line(draw, [(0.372, cy), (0.398, cy)], u(0.010), (150, 144, 124, 220))
    draw.ellipse((u(0.604), u(cy - 0.013), u(0.630), u(cy + 0.013)), outline=(150, 144, 124, 220),
                 width=int(u(0.007)))
    # 拨杆：从底座中心伸出去，宽根窄梢 + 梢头一颗球
    a = -math.pi / 2 + math.radians(-34 + 68 * smooth(k))
    root_x, root_y = cx + math.cos(a) * 0.020, cy + math.sin(a) * 0.020
    tip_x, tip_y = cx + math.cos(a) * 0.214, cy + math.sin(a) * 0.214
    nx, ny = -math.sin(a), math.cos(a)
    draw.polygon([(u(root_x + nx * 0.050), u(root_y + ny * 0.050)),
                  (u(root_x - nx * 0.050), u(root_y - ny * 0.050)),
                  (u(tip_x - nx * 0.024), u(tip_y - ny * 0.024)),
                  (u(tip_x + nx * 0.024), u(tip_y + ny * 0.024))],
                 fill=(251, 247, 236, 255))
    stroke_line(draw, [(root_x + nx * 0.050, root_y + ny * 0.050),
                       (tip_x + nx * 0.024, tip_y + ny * 0.024),
                       (tip_x - nx * 0.024, tip_y - ny * 0.024),
                       (root_x - nx * 0.050, root_y - ny * 0.050)],
                u(0.012), (92, 116, 100, 255))
    stroke_line(draw, [(root_x + nx * 0.032, root_y + ny * 0.032),
                       (tip_x + nx * 0.010, tip_y + ny * 0.010)], u(0.008), (255, 255, 255, 200))
    draw.ellipse((u(tip_x - 0.034), u(tip_y - 0.034), u(tip_x + 0.034), u(tip_y + 0.034)),
                 fill=(228, 222, 206, 255), outline=RECEIPT_INK, width=int(u(0.012)))
    draw.ellipse((u(tip_x - 0.018), u(tip_y - 0.020), u(tip_x + 0.006), u(tip_y + 0.004)),
                 fill=(255, 255, 255, 190))
    # 拨到右边才亮的指示灯
    lit = smooth((k - 0.55) / 0.35)
    draw.ellipse((u(0.478), u(0.742), u(0.522), u(0.786)),
                 fill=(120, 140, 128, 130), outline=(110, 130, 118, 200), width=int(u(0.005)))
    if lit > 0.02:
        draw.ellipse((u(0.486), u(0.750), u(0.514), u(0.778)),
                     fill=(58, 196, 134, int(255 * lit)))


# ---------------------------------------------------------------- 物件：纸感极简

def paper_home(draw: ImageDraw.ImageDraw, k: float) -> None:
    """折角便签：右上角折下去、再展开。"""
    fold = 0.170 * (1 - smooth(k))
    L, R, T, B = 0.300, 0.700, 0.268, 0.752
    if fold > 0.006:
        pts = [(L, T), (R - fold, T), (R, T + fold), (R, B), (L, B)]
    else:
        pts = [(L, T), (R, T), (R, B), (L, B)]
    draw.polygon([(u(x), u(y)) for x, y in pts], fill=PAPER_SHEET,
                 outline=PAPER_INK, width=int(u(0.020)))
    if fold > 0.006:
        draw.polygon([(u(R - fold), u(T)), (u(R), u(T + fold)), (u(R - fold), u(T + fold))],
                     fill=(234, 229, 214, 255), outline=PAPER_INK, width=int(u(0.014)))
    for i, (y, x1) in enumerate(((0.404, 0.636), (0.492, 0.596), (0.580, 0.618))):
        stroke_line(draw, [(0.352, y), (x1, y)], u(0.014), PAPER_SOFT)


def paper_ledger(draw: ImageDraw.ImageDraw, k: float) -> None:
    """线装账本：左缘四针装订，右缘一页自己掀起来。"""
    L, R, T, B = 0.318, 0.688, 0.264, 0.748
    bulge = 0.078 * smooth(k)
    # 掀起的那一页（先画，压在书壳后面）
    if bulge > 0.004:
        page = bezier((R, T + 0.030), (R + bulge * 1.9, 0.505), (R, B - 0.030))
        draw.polygon([(u(x), u(y)) for x, y in page] + [(u(R), u(T + 0.030))],
                     fill=(255, 253, 246, 255))
        stroke_line(draw, page, u(0.016), PAPER_INK)
    draw.rectangle((u(L), u(T), u(R), u(B)), fill=PAPER_SHEET, outline=PAPER_INK, width=int(u(0.020)))
    # 装订边 + 四道针脚
    stroke_line(draw, [(0.368, T + 0.012), (0.368, B - 0.012)], u(0.011), (35, 76, 60, 165))
    for y in (0.334, 0.444, 0.566, 0.678):
        stroke_line(draw, [(0.336, y), (0.400, y)], u(0.013), PAPER_INK)
    # 内页三行账目，越往下越短
    for y, x1 in ((0.400, 0.636), (0.486, 0.596), (0.572, 0.560)):
        stroke_line(draw, [(0.412, y), (x1, y)], u(0.012), (35, 76, 60, 135))
    # 右缘掀起来的阴影
    if bulge > 0.004:
        stroke_line(draw, [(R, T + 0.045), (R + 0.006, B - 0.045)], u(0.008), (35, 76, 60, 90))


def paper_statistics(draw: ImageDraw.ImageDraw, k: float) -> None:
    """墨线折线：一笔画上去，笔过处洇一点点。"""
    pts = [(0.318, 0.664), (0.428, 0.548), (0.516, 0.598), (0.614, 0.414), (0.684, 0.362)]
    path = partial_path(pts, ease_out(k))
    if len(path) >= 2:
        stroke_line(draw, path, u(0.030), PAPER_INK)
        # 笔尖停处留一个小墨团
        r = 0.024
        draw.ellipse((u(path[-1][0] - r), u(path[-1][1] - r), u(path[-1][0] + r), u(path[-1][1] + r)),
                     fill=PAPER_INK)
    for i in range(1, len(pts) - 1):
        if k * (len(pts) - 1) >= i + 0.15:
            r = 0.017
            draw.ellipse((u(pts[i][0] - r), u(pts[i][1] - r), u(pts[i][0] + r), u(pts[i][1] + r)),
                         fill=PAPER_INK)


def paper_settings(draw: ImageDraw.ImageDraw, k: float) -> None:
    """线上墨点：一道横线，墨点从一头滑到另一头。"""
    stroke_line(draw, [(0.298, 0.520), (0.702, 0.520)], u(0.020), PAPER_INK)
    for x in (0.298, 0.702):
        stroke_line(draw, [(x, 0.479), (x, 0.561)], u(0.011), (35, 76, 60, 150))
    cx = 0.340 + 0.320 * smooth(k)
    r = 0.046
    draw.ellipse((u(cx - r), u(0.520 - r), u(cx + r), u(0.520 + r)), fill=PAPER_INK)
    # 墨在纸上洇的一圈淡边
    draw.ellipse((u(cx - r - 0.016), u(0.520 - r - 0.016), u(cx + r + 0.016), u(0.520 + r + 0.016)),
                 outline=(35, 76, 60, 70), width=int(u(0.008)))


OBJECTS = {
    ("mint", "home"): mint_home,
    ("mint", "ledger"): mint_ledger,
    ("mint", "statistics"): mint_statistics,
    ("mint", "settings"): mint_settings,
    ("receipt", "home"): receipt_home,
    ("receipt", "ledger"): receipt_ledger,
    ("receipt", "statistics"): receipt_statistics,
    ("receipt", "settings"): receipt_settings,
    ("paper", "home"): paper_home,
    ("paper", "ledger"): paper_ledger,
    ("paper", "statistics"): paper_statistics,
    ("paper", "settings"): paper_settings,
}


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
    draw.arc((u(cx - r + 0.055), u(cy - r + 0.055), u(cx + r - 0.055), u(cy + r - 0.055)),
             start=185, end=290, fill=(255, 255, 255, 190), width=int(u(0.020)))
    draw.ellipse((u(0.245), u(0.245), u(0.305), u(0.305)), fill=(255, 255, 255, 210))
    draw.arc((u(cx - r + 0.030), u(cy - r + 0.030), u(cx + r - 0.030), u(cy + r - 0.030)),
             start=20, end=95, fill=(150, 205, 182, 90), width=int(u(0.016)))

    if glow > 0:
        spark, sdraw = layer()
        sdraw.ellipse((u(0.320), u(0.320), u(0.680), u(0.680)), fill=(255, 255, 255, int(110 * glow)))
        spark = spark.filter(ImageFilter.GaussianBlur(u(0.07)))
        img = over(img, Image.composite(spark, Image.new("RGBA", img.size, (0, 0, 0, 0)), mask))

    draw = ImageDraw.Draw(img)
    draw.ellipse((u(cx - r), u(cy - r), u(cx + r), u(cy + r)),
                 outline=(58, 132, 106, 250), width=int(u(0.028)))
    return transform(img, scale=scale)


def receipt_base(scale: float = 1.0, dy: float = 0.0, rotate: float = 0.0) -> Image.Image:
    """小纸签：上下齿孔 + 发丝细框，不加投影（投影在小尺寸会糊成一圈脏边）。

    从 0.285~0.715 放宽到 0.220~0.780：打印机、开关这些物件比图标宽，
    窄签子里塞不下；而且 56dp 下纸签的可视面积本来就比圆章小，放宽后两边才等重。
    """
    left, right, top, bottom = 0.220, 0.780, 0.126, 0.874
    mask = Image.new("L", (CANVAS, CANVAS), 0)
    mdraw = ImageDraw.Draw(mask)
    mdraw.rectangle((u(left), u(top), u(right), u(bottom)), fill=255)
    step = (right - left) / 5
    for i in range(1, 5):
        x = left + step * i
        mdraw.ellipse((u(x - 0.022), u(top - 0.022), u(x + 0.022), u(top + 0.022)), fill=0)
        mdraw.ellipse((u(x - 0.022), u(bottom - 0.022), u(x + 0.022), u(bottom + 0.022)), fill=0)

    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    paper = Image.new("RGBA", (CANVAS, CANVAS), (252, 249, 237, 255))
    img.paste(paper, (0, 0), mask)
    draw = ImageDraw.Draw(img)
    draw.rectangle((u(left), u(top), u(right), u(bottom)), outline=(211, 203, 182, 255),
                   width=int(u(0.010)))
    return transform(img, scale=scale, dy=dy, rotate=rotate)


def paper_base(scale: float = 1.0, dy: float = 0.0) -> Image.Image:
    """极简：不画底，只在四角点四道裁切角标。"""
    img, draw = layer()
    tick = 0.050
    color = (150, 160, 145, 150)
    for x, sx in ((0.132, 1), (0.868, -1)):
        for y, sy in ((0.132, 1), (0.868, -1)):
            stroke_line(draw, [(x, y), (x + tick * sx, y)], u(0.013), color)
            stroke_line(draw, [(x, y), (x, y + tick * sy)], u(0.013), color)
    return transform(img, scale=scale, dy=dy)


# ---------------------------------------------------------------- 两个状态

def idle_k(skin: str, page: str, t: float) -> float:
    """待机时物件自己的小动作——不能是整枚徽章一起放大缩小。"""
    b = math.sin(t * math.pi * 2)
    if (skin, page) == ("mint", "home"):
        return t                                        # 露珠一轮一轮落
    if (skin, page) == ("mint", "ledger"):
        return min(1.0, t / 0.42)                       # 开头掉一枚，之后停住
    if (skin, page) == ("mint", "statistics"):
        return 0.56 + 0.22 * b                          # 液面轻晃
    if (skin, page) == ("mint", "settings"):
        return 0.5 + 0.16 * b                           # 旋钮微微呼吸
    if (skin, page) == ("receipt", "home"):
        return 0.36 + 0.05 * b                          # 卡半插在缝里，轻轻上下
    if (skin, page) == ("receipt", "ledger"):
        return 0.46 + 0.05 * b                          # 纸吐出一截，随呼吸抖
    if (skin, page) == ("receipt", "statistics"):
        return 0.86 + 0.14 * (b * 0.5 + 0.5)            # 线基本画完，笔尖在尾巴上顿
    if (skin, page) == ("receipt", "settings"):
        return 0.5 + 0.10 * b                           # 拨片停在中间轻颤
    if (skin, page) == ("paper", "home"):
        return 0.5 + 0.5 * b                            # 折角一开一合
    if (skin, page) == ("paper", "ledger"):
        return 0.10 + 0.10 * (b * 0.5 + 0.5)            # 书页轻微起伏
    if (skin, page) == ("paper", "statistics"):
        return 0.90 + 0.10 * (b * 0.5 + 0.5)            # 线画完了，笔尖停着
    return 0.5 + 0.42 * b                               # 墨点左右滑


def tap_k(skin: str, page: str, t: float) -> float:
    """点一下：物件把它自己的动作做完。"""
    if (skin, page) in (("mint", "home"), ("mint", "ledger")):
        return min(1.0, t / 0.72)                       # 露珠坠下 / 硬币落罐
    if (skin, page) == ("mint", "statistics"):
        return smooth(t / 0.42)                         # 液面一口气涨上去
    if (skin, page) == ("mint", "settings"):
        if t < 0.34:
            return smooth(t / 0.34)                     # 拧到最右
        if t < 0.62:
            return 1.0
        return 1.0 - smooth((t - 0.62) / 0.38)          # 手松开，弹回中位
    if (skin, page) == ("receipt", "home"):
        return 0.36 + 0.64 * smooth(t / 0.42)           # 卡再往里按一截
    if (skin, page) == ("receipt", "ledger"):
        return smooth(t / 0.62)                         # 吐一小票
    if (skin, page) == ("receipt", "statistics"):
        return smooth(t / 0.66)                         # 一笔画上去
    if (skin, page) == ("receipt", "settings"):
        if t < 0.30:
            return 1.0 - smooth(t / 0.30) * 0.0         # 先稳住
        if t < 0.44:
            return smooth((t - 0.30) / 0.14)            # 啪地拨过去
        if t < 0.80:
            return 1.0
        return 1.0 - smooth((t - 0.80) / 0.20)          # 弹回
    if (skin, page) == ("paper", "home"):
        if t < 0.40:
            return 1.0 - smooth(t / 0.40)               # 折下去
        return 1.0 - smooth((t - 0.40) / 0.34)          # 再展开
    if (skin, page) == ("paper", "ledger"):
        return math.sin(math.pi * min(1.0, t / 0.78)) ** 0.9   # 掀起又落下
    if (skin, page) == ("paper", "statistics"):
        return smooth(t / 0.62)                         # 一笔画上去
    return smooth(t / 0.30) if t < 0.45 else 1.0 - smooth((t - 0.45) / 0.40)


def build_frame(skin: str, page: str, k: float, *, scale: float, dy: float, ring: float,
                glow: float) -> Image.Image:
    """把「底 + 物件」拼成一帧；底的三套材质差异都在这里，物件本身只管画。"""
    obj_img, obj_draw = layer()
    OBJECTS[(skin, page)](obj_draw, k)

    if skin == "mint":
        out = mint_base(scale=scale, glow=glow)
        if ring >= 0:
            r = 0.42 + 0.28 * ring
            halo, hdraw = layer()
            hdraw.ellipse((u(0.5 - r), u(0.5 - r), u(0.5 + r), u(0.5 + r)),
                          outline=(127, 200, 172, int(180 * (1 - ring))), width=int(u(0.016)))
            out = over(out, halo)
        return over(out, transform(obj_img, scale=scale * OBJECT_SCALE["mint"], dy=dy * 0.5))

    if skin == "receipt":
        out = receipt_base(scale=scale, dy=dy, rotate=0.0)
        return over(out, transform(obj_img, scale=scale * OBJECT_SCALE["receipt"], dy=dy * 0.4))

    out = paper_base(dy=dy * 0.5)
    # 纸感保留「批注下划线」：一顿笔、一道线，是这套皮肤的签名
    out = over(out, transform(obj_img, scale=OBJECT_SCALE["paper"], alpha=1.0))
    return out


def idle_frame(skin: str, page: str, i: int) -> Image.Image:
    t = i / IDLE_FRAMES
    breath = math.sin(t * math.pi * 2)
    k = idle_k(skin, page, t)

    if skin == "paper":
        # 纸感没有底板跳动，只让下划线随呼吸抽一下，其余全交给物件
        out = build_frame(skin, page, k, scale=1.0, dy=0.0, ring=-1.0, glow=0.0)
        underline, ldraw = layer()
        progress = 0.86 + 0.14 * (breath * 0.5 + 0.5)
        stroke_line(ldraw, [(0.276, 0.848), (0.276 + 0.448 * progress, 0.848)],
                    u(0.024), (35, 76, 60, 215))
        return over(out, underline)

    return build_frame(skin, page, k,
                       scale=1.0 + (0.012 if skin == "mint" else 0.006) * breath,
                       dy=0.004 * breath,
                       ring=-1.0,
                       glow=max(0.0, breath) * 0.55 if skin == "mint" else 0.0)


def tap_frame(skin: str, page: str, i: int) -> Image.Image:
    t = i / (TAP_FRAMES - 1)
    if t < 0.18:
        kk = t / 0.18
        scale, dy, ring = 1.0 - 0.09 * kk, 0.008 * kk, -1.0
    elif t < 0.45:
        kk = (t - 0.18) / 0.27
        scale, dy, ring = 0.91 + 0.19 * kk, 0.008 * (1 - kk), kk
    elif t < 0.78:
        kk = (t - 0.45) / 0.33
        scale, dy, ring = 1.10 - 0.10 * kk, -0.005 * (1 - kk), 0.55
    else:
        kk = (t - 0.78) / 0.22
        scale, dy, ring = 1.0 + 0.010 * math.sin(kk * math.pi), 0.0, 0.0

    out = build_frame(skin, page, tap_k(skin, page, t),
                      scale=scale, dy=dy, ring=ring,
                      glow=0.9 if (skin == "mint" and 0.25 < t < 0.60) else 0.0)

    if skin == "paper":
        underline, ldraw = layer()
        if t < 0.15:
            progress = 0.55 * (t / 0.15)
        elif t < 0.55:
            progress = 0.55 + 0.55 * ((t - 0.15) / 0.40)
        else:
            progress = 1.10 - 0.14 * min(1.0, (t - 0.55) / 0.45)
        stroke_line(ldraw, [(0.276, 0.848), (0.276 + 0.448 * progress, 0.848)],
                    u(0.030), (35, 76, 60, 240))
        out = over(out, underline)

    if skin == "mint" and 0.25 < t < 0.72:
        kk = (t - 0.25) / 0.47
        sparks, sdraw = layer()
        for angle in (-2.3, -0.6, 0.7):
            x = 0.5 + math.cos(angle) * (0.40 + 0.14 * kk)
            y = 0.5 + math.sin(angle) * (0.40 + 0.14 * kk)
            r = 0.022 * (1 - kk)
            sdraw.ellipse((u(x - r), u(y - r), u(x + r), u(y + r)),
                          fill=(110, 190, 160, int(210 * (1 - kk))))
        out = over(out, sparks)
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
    small = Image.new("RGBA", (56 * 4 + 28, 56 * 3 + 20), (248, 244, 233, 255))
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
            small.alpha_composite(thumb, (10 + col * 56 + 4, 10 + row * 56))
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
