"""预览脚本（不进 APK）：把肥鱼头卡右上角的点击动画渲染成 GIF，先看效果再决定接不接。

四个页面：
  home        好运星星：角色压扁弹起，五颗像素星星从身后扇形炸开、闪烁后熄灭
  ledger      金币雨：几枚像素金币从上方落下，砸到头顶弹一下再落出画面
  statistics  像素盖章：贴纸猛地压扁下压，背后浮出像素印框与一道墨线
  settings    光圈弹跳：贴纸压扁弹起，脚下荡开两圈阶梯像素光环

背景按头卡真实色值重建（深海渐变 + 一道浅浪），贴纸用 assets/fish_header/ 的原帧，
尺寸按 74dp 槽位 / 41dp 贴纸的比例换算，动画曲线与将来 Kotlin 侧要用的完全一致。

用法：python tools/gen_fish_reaction_preview.py
产物：tools/reaction_previews/<page>_<reaction>.gif 与 all_four.gif（已 gitignore）
"""
import math
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ROOT, "tools", "reaction_previews")

S = 300                      # 74dp 槽位在 4x 下的边长
STICKER_H = round(S * 41 / 74)   # 贴纸高度跟 Kotlin 侧 STICKER_HEIGHT 一致
STICKER_DY = round(S * -3 / 74)  # 贴纸整体上移 3dp

# 头卡底色：从 headers-fatty_fish.png 上采的竖直线性渐变
BG_STOPS = [
    (0.00, (26, 53, 101)),
    (0.55, (37, 77, 143)),
    (0.74, (54, 94, 157)),
    (0.86, (82, 121, 182)),
    (1.00, (86, 126, 187)),
]

INK = (226, 244, 255)
RING_COLOR = (176, 236, 255)
COIN_FILL = (255, 214, 96)
COIN_RIM = (176, 116, 22)
COIN_LIGHT = (255, 245, 200)


def clamp(v, lo=0.0, hi=1.0):
    return max(lo, min(hi, v))


def lerp(a, b, t):
    return a + (b - a) * t


def ease_out(t):
    t = clamp(t)
    return 1 - (1 - t) ** 3


def ease_in(t):
    t = clamp(t)
    return t * t


def background(size=S):
    """竖直渐变 + 一道浅色浪带，贴近肥鱼头卡的底。"""
    img = Image.new("RGBA", (size, size))
    px = img.load()
    for y in range(size):
        f = y / (size - 1)
        for i in range(len(BG_STOPS) - 1):
            f0, c0 = BG_STOPS[i]
            f1, c1 = BG_STOPS[i + 1]
            if f0 <= f <= f1:
                k = (f - f0) / (f1 - f0) if f1 > f0 else 0
                px_color = tuple(round(lerp(c0[j], c1[j], k)) for j in range(3)) + (255,)
                break
        else:
            px_color = BG_STOPS[-1][1] + (255,)
        for x in range(size):
            px[x, y] = px_color

    draw = ImageDraw.Draw(img, "RGBA")
    # 浪：一条起伏的浅色带，压在下方 1/4
    band = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bdraw = ImageDraw.Draw(band)
    for x in range(size):
        top = 0.80 * size + 7 * math.sin(x / size * math.pi * 2.2 + 0.6)
        bdraw.line([(x, top), (x, size)], fill=(150, 190, 226, 26))
    img = Image.alpha_composite(img, band)
    return img


def load_sticker(page):
    folder = os.path.join(ASSETS, "fish_header", page)
    frames = sorted(os.listdir(folder))
    out = []
    for name in frames:
        im = Image.open(os.path.join(folder, name)).convert("RGBA")
        w = round(STICKER_H * im.width / im.height)
        out.append(im.resize((w, STICKER_H), Image.LANCZOS))
    return out


# ---- 动作曲线：三个反馈共用「压扁系数」，1.0 表示原样 ---------------------------------

def stamp_squash(t):
    if t < 0.14:
        return lerp(1.0, 0.84, ease_out(t / 0.14))
    if t < 0.32:
        return lerp(0.84, 1.07, ease_out((t - 0.14) / 0.18))
    if t < 0.55:
        return lerp(1.07, 0.98, ease_out((t - 0.32) / 0.23))
    return lerp(0.98, 1.0, (t - 0.55) / 0.45)


def ring_squash(t):
    if t < 0.12:
        return lerp(1.0, 0.86, ease_out(t / 0.12))
    if t < 0.34:
        return lerp(0.86, 1.10, ease_out((t - 0.12) / 0.22))
    if t < 0.58:
        return lerp(1.10, 0.97, ease_out((t - 0.34) / 0.24))
    return lerp(0.97, 1.0, (t - 0.58) / 0.42)


def star_squash(t):
    if t < 0.10:
        return lerp(1.0, 0.82, ease_out(t / 0.10))
    if t < 0.30:
        return lerp(0.82, 1.12, ease_out((t - 0.10) / 0.20))
    if t < 0.52:
        return lerp(1.12, 0.97, ease_out((t - 0.30) / 0.22))
    return lerp(0.97, 1.0, (t - 0.52) / 0.48)


COIN_STARTS = (0.00, 0.08, 0.16, 0.25, 0.34, 0.44, 0.54)
COIN_SPAN = 0.46


def coin_hits():
    """每枚金币砸到贴纸头顶的时刻（飞出画面之前）。"""
    return [s + COIN_SPAN * 0.58 for s in COIN_STARTS]


def coins_squash(t):
    s = 1.0
    for hit in coin_hits():
        d = (t - hit) / 0.055
        if abs(d) < 3:
            s -= 0.15 * math.exp(-d * d)
    return max(0.82, s)


SQUASH = {
    "stamp": stamp_squash,
    "ring": ring_squash,
    "coins": coins_squash,
    "stars": star_squash,
}


def paste_sticker(img, sticker, squash):
    h = max(1, round(sticker.height * squash))
    w = round(sticker.width * (1 + (1 - squash) * 0.55))  # 压扁时横向撑开，保住体积感
    scaled = sticker.resize((w, h), Image.LANCZOS)
    cx = S // 2
    bottom = (S + STICKER_H) // 2 + STICKER_DY
    img.alpha_composite(scaled, (cx - w // 2, bottom - h))


# ---- 三种特效 ------------------------------------------------------------------------

def draw_stamp(img, t):
    """四方角标紧贴角色 + 字幕下的一道墨线，和压扁同时落下。"""
    if t < 0.05:
        return img
    fade = 1.0
    if t < 0.18:
        fade = ease_out((t - 0.05) / 0.13)
    elif t > 0.72:
        fade = max(0.0, 1 - ease_out((t - 0.72) / 0.26))
    if fade <= 0.01:
        return img

    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    a = int(240 * fade)
    left, right = round(0.20 * S), round(0.80 * S)
    top, bottom = round(0.13 * S), round(0.60 * S)
    thick = round(0.034 * S)
    arm = round(0.10 * S)
    for cx, sx in ((left, 1), (right, -1)):
        for cy, sy in ((top, 1), (bottom, -1)):
            hx, hy = cx + arm * sx, cy + thick * sy
            draw.rectangle((min(cx, hx), min(cy, hy), max(cx, hx), max(cy, hy)), fill=INK + (a,))
            vx, vy = cx + thick * sx, cy + arm * sy
            draw.rectangle((min(cx, vx), min(cy, vy), max(cx, vx), max(cy, vy)), fill=INK + (a,))

    # 墨线：从中间往两头划开，收在字幕底下
    grow = ease_out(clamp((t - 0.09) / 0.26))
    if grow > 0:
        half = 0.22 * S * grow
        y = 0.885 * S
        draw.rectangle((S / 2 - half, y, S / 2 + half, y + round(0.022 * S)),
                       fill=INK + (int(225 * fade),))
    return Image.alpha_composite(img, layer)


def pixel_ring(layer, radius_frac, thickness_frac, alpha, center=(0.5, 0.5)):
    """在 1/4 分辨率上画圆环再放大，边缘落在像素格上，才是像素游戏的「嗡」一下。"""
    q = 4
    low = Image.new("L", (S // q, S // q), 0)
    draw = ImageDraw.Draw(low)
    r = radius_frac * (S // q)
    cx, cy = center[0] * (S // q), center[1] * (S // q)
    thickness = max(1, round(thickness_frac * (S // q)))
    draw.ellipse((cx - r, cy - r, cx + r, cy + r), outline=255, width=thickness)
    mask = low.resize((S, S), Image.NEAREST)
    stamp = Image.new("RGBA", layer.size, RING_COLOR + (alpha,))
    layer.paste(stamp, (0, 0), mask)


def draw_rings(img, t):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    for start, span, thick in ((0.00, 0.62, 0.055), (0.10, 0.66, 0.030)):
        p = clamp((t - start) / span)
        if p <= 0 or p >= 1:
            continue
        radius = lerp(0.185, 0.455, ease_out(p))
        alpha = int(245 * (1 - p) ** 0.85)
        if alpha <= 5:
            continue
        pixel_ring(layer, radius, thick, alpha)
    return Image.alpha_composite(img, layer)


COIN_CELLS = 11
COIN_INK = (122, 76, 12)
COIN_DARK = (222, 165, 44)
COIN_FILL = (255, 214, 96)
COIN_LIGHT = (255, 246, 206)


def coin_sprite():
    """11x11 的像素硬币：按半径分成描边 / 暗面 / 亮面 / 高光四层。"""
    r = COIN_CELLS / 2
    grid = []
    for y in range(COIN_CELLS):
        row = []
        for x in range(COIN_CELLS):
            dx, dy = x + 0.5 - r, y + 0.5 - r
            d = math.hypot(dx, dy)
            if d > r:
                row.append(None)
            elif d > r - 1.1:
                row.append(COIN_INK)
            elif dx + dy > 1.2:
                row.append(COIN_DARK)
            else:
                row.append(COIN_FILL)
        grid.append(row)
    # 左上高光：一小块，够看就行，别做成方块洞
    for x, y in ((4, 3), (3, 4), (4, 4)):
        grid[y][x] = COIN_LIGHT
    return grid


COIN_SPRITE = coin_sprite()


def draw_coin(layer, cx, cy, unit, squish=1.0):
    draw = ImageDraw.Draw(layer)
    half = COIN_CELLS * unit / 2
    for ry, row in enumerate(COIN_SPRITE):
        for rx, color in enumerate(row):
            if color is None:
                continue
            x0 = cx - half + rx * unit
            y0 = cy - half + ry * unit * squish
            draw.rectangle((x0, y0, x0 + unit - 0.4, y0 + unit * squish - 0.4), fill=color + (255,))


def draw_coins(img, t):
    """金币从上落下，砸到贴纸头顶弹一下，再落出画面。"""
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    unit = max(3, round(S / 64))
    land = 0.255 * S         # 砸到头顶的位置
    for i, start in enumerate(COIN_STARTS):
        p = clamp((t - start) / COIN_SPAN)
        if p <= 0 or p >= 1:
            continue
        x = (0.17 + 0.115 * i) * S + 4 * math.sin(p * math.pi * 2 + i)
        if p < 0.62:
            # 自由落体：前段慢、后段快，才不像匀速平移
            y = lerp(-0.12 * S, land, clamp(p / 0.62) ** 1.7)
            squish = 1.0
        elif p < 0.78:
            q = (p - 0.62) / 0.16
            y = lerp(land, land - 0.115 * S, math.sin(q * math.pi))
            squish = 0.80
        else:
            q = (p - 0.78) / 0.22
            y = lerp(land - 0.015 * S, 1.16 * S, ease_in(q))
            squish = 0.92
        draw_coin(layer, x, y, unit, squish)
    return Image.alpha_composite(img, layer)


# ---- 首页：好运星星 ------------------------------------------------------------------

STAR_GLOW = (255, 226, 128)     # 暖黄，跟金币一个色系但不抢戏
STAR_CORE = (255, 248, 214)
STAR_BIG = [
    "..A..",
    ".A.A.",
    "A.B.A",
    ".A.A.",
    "..A..",
]
STAR_SMALL = [
    ".A.",
    "ABA",
    ".A.",
]
STAR_STARTS = (0.02, 0.11, 0.20, 0.30, 0.40)
STAR_SPAN = 0.52


def draw_star(layer, cx, cy, unit, big):
    draw = ImageDraw.Draw(layer)
    mask = STAR_BIG if big else STAR_SMALL
    rows, cols = len(mask), len(mask[0])
    x0 = cx - cols * unit / 2
    y0 = cy - rows * unit / 2
    for ry, row in enumerate(mask):
        for rx, ch in enumerate(row):
            if ch == ".":
                continue
            color = STAR_CORE if ch == "B" else STAR_GLOW
            px, py = x0 + rx * unit, y0 + ry * unit
            draw.rectangle((px, py, px + unit - 0.4, py + unit - 0.4), fill=color + (255,))


def draw_stars(img, t):
    """五颗像素星星从角色身后扇形炸开：出现 → 大星与小白交替闪 → 缩回小星 → 熄灭。"""
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    unit = max(3, round(S / 64))
    ox, oy = 0.50 * S, 0.44 * S          # 炸开的原点：角色胸口附近
    for i, start in enumerate(STAR_STARTS):
        p = clamp((t - start) / STAR_SPAN)
        if p <= 0 or p >= 0.88:
            continue
        ang = -math.pi / 2 + (i - 2) * 0.62          # 以正上为主，往两边扇开
        dist = lerp(0.055 * S, (0.24 + 0.035 * (i % 3)) * S, ease_out(p))
        x = ox + math.cos(ang) * dist
        y = oy + math.sin(ang) * dist * 0.92
        if p < 0.14:
            big = False                               # 先冒出小星
        elif p < 0.60:
            big = int((t * 1000) // 80) % 2 == 0      # 大星 / 小星交替闪
        else:
            big = False                               # 收回小星后熄灭
        draw_star(layer, x, y, unit, big)
    return Image.alpha_composite(img, layer)


# ---- 帧合成 --------------------------------------------------------------------------

def render_frame(page, reaction, t, sticker):
    img = background()
    # 星星画在贴纸下层，看起来是从角色身后蹦出来的
    if reaction == "stars":
        img = draw_stars(img, t)
    elif reaction == "stamp":
        img = draw_stamp(img, t)
    elif reaction == "ring":
        img = draw_rings(img, t)
    paste_sticker(img, sticker, SQUASH[reaction](t))
    if reaction == "coins":
        img = draw_coins(img, t)
    return img


DURATION = {"stars": 1.05, "stamp": 0.90, "ring": 1.05, "coins": 1.40}
STEP_MS = 40
IDLE_FRAMES = 9          # 动作前先待机一小段，才看得出差别
TAIL_FRAMES = 6

PAGES = [
    ("home", "首页 Today（好运星星）", "stars"),
    ("ledger", "账单 Ledger（金币雨）", "coins"),
    ("statistics", "统计 Stats（像素盖章）", "stamp"),
    ("settings", "设置 Settings（光圈弹跳）", "ring"),
]


def build(page, reaction, total_frames):
    """所有页面共用一条 40ms 的时间轴：待机 → 动作 → 收尾，四列对齐好对比。"""
    sticker = load_sticker(page)
    duration = DURATION[reaction]

    frames = []
    for i in range(total_frames):
        if i < IDLE_FRAMES:
            t = 1.0
        else:
            elapsed = (i - IDLE_FRAMES) * STEP_MS / 1000.0
            t = clamp(elapsed / duration)
            if t >= 1.0 and i < total_frames - TAIL_FRAMES:
                t = 1.0
        frames.append(render_frame(page, reaction, t, sticker[(i // 2) % len(sticker)]))
    return frames


def save_gif(frames, path):
    quantized = [f.convert("RGB").quantize(colors=96, method=Image.MEDIANCUT) for f in frames]
    quantized[0].save(path, save_all=True, append_images=quantized[1:],
                      duration=[STEP_MS] * len(frames), loop=0, optimize=True, disposal=2)


def filmstrip(frames, picks, path):
    """挑几帧横排，静态一眼看完整个动作。"""
    w = len(picks) * S + (len(picks) + 1) * 8
    sheet = Image.new("RGBA", (w, S + 16), (246, 250, 255, 255))
    for col, idx in enumerate(picks):
        sheet.alpha_composite(frames[idx], (8 + col * (S + 8), 8))
    sheet.convert("RGB").save(path)


def combined(columns, path, total_frames):
    gap, cap = 12, 34
    w = len(columns) * S + (len(columns) + 1) * gap
    h = S + cap + gap * 2
    font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 19)
    frames = []
    for idx in range(total_frames):
        sheet = Image.new("RGBA", (w, h), (246, 250, 255, 255))
        draw = ImageDraw.Draw(sheet)
        for col, (frames_c, label) in enumerate(columns):
            x = gap + col * (S + gap)
            sheet.alpha_composite(frames_c[idx], (x, cap))
            draw.text((x + 2, 9), label, font=font, fill=(40, 70, 120, 255))
        frames.append(sheet)
    quantized = [f.convert("RGB").quantize(colors=160, method=Image.MEDIANCUT) for f in frames]
    quantized[0].save(path, save_all=True, append_images=quantized[1:],
                      duration=[STEP_MS] * total_frames, loop=0, optimize=True, disposal=2)


def main():
    os.makedirs(OUT, exist_ok=True)
    total = IDLE_FRAMES + int(max(DURATION.values()) * 1000 / STEP_MS) + TAIL_FRAMES
    columns = []
    for page, label, reaction in PAGES:
        frames = build(page, reaction, total)
        path = os.path.join(OUT, f"{page}_{reaction}.gif")
        save_gif(frames, path)
        picks = [0, IDLE_FRAMES - 1] + [IDLE_FRAMES + int(k * total / 6) for k in (1, 2, 3, 4)] + [total - 1]
        filmstrip(frames, [min(p, total - 1) for p in picks], os.path.join(OUT, f"{page}_strip.png"))
        columns.append((frames, label))
        print(f"{path}  {len(frames)} frames")
    combined(columns, os.path.join(OUT, "all_four.gif"), total)
    print(os.path.join(OUT, "all_four.gif"), total, "frames")



if __name__ == "__main__":
    main()
