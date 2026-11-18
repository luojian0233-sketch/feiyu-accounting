"""生成「蓝色肥鱼」皮肤用的位图资源。

三件事：
1. 顶部动画贴纸：把 `上方美化栏替换.gif` 底部的「好运来」去掉，统一取景后切成逐帧 PNG。
2. 空记录插画：把高清立绘（吃饭那张）抠掉白底，输出 `whale_mascot.png`。
3. 启动图标：把比赞那张抠底、裁成头肩构图，生成自适应前景与各密度旧版图标。

用法（仓库根目录）：python tools/gen_fish_art.py --gif <路径> --mascot <路径> --icon <路径>
"""

from __future__ import annotations

import argparse
import os
import shutil

from PIL import Image, ImageChops, ImageDraw, ImageFilter

HEADER_FRAME_HEIGHT = 288  # 竖版贴纸：人物 + 底部「好运来」，保持原图 216:271 的比例
MASCOT_PX = 768
SAND_TOP = (255, 251, 242)
SAND_BOTTOM = (255, 228, 188)


def matte_white_background(image: Image.Image, tolerance: int = 241, feather: float = 1.6) -> Image.Image:
    """只抠与边界连通的近白区域：围裙、米饭这些被线条包住的白会保留下来。"""
    rgb = image.convert("RGB")
    width, height = rgb.size
    red, green, blue = rgb.split()
    min_channel = ImageChops.darker(ImageChops.darker(red, green), blue)
    near_white = min_channel.point(lambda v: 255 if v >= tolerance else 0)

    seeds = [(0, 0), (width - 1, 0), (0, height - 1), (width - 1, height - 1)]
    for x in range(0, width, 2):
        seeds += [(x, 0), (x, height - 1)]
    for y in range(0, height, 2):
        seeds += [(0, y), (width - 1, y)]
    for seed in seeds:
        if near_white.getpixel(seed) == 255:
            ImageDraw.floodfill(near_white, seed, 128, thresh=0)

    alpha = near_white.point(lambda v: 0 if v == 128 else 255)
    # 只在交界 3px 带内换成模糊值，既有抗锯齿又不让整幅图发软
    band = ImageChops.difference(
        alpha.filter(ImageFilter.MaxFilter(3)),
        alpha.filter(ImageFilter.MinFilter(3)),
    ).point(lambda v: 255 if v > 0 else 0)
    alpha = Image.composite(alpha.filter(ImageFilter.GaussianBlur(feather)), alpha, band)

    out = rgb.convert("RGBA")
    out.putalpha(alpha)
    return out


def square_canvas(image: Image.Image, side: int, padding: float, anchor_bottom: bool = False) -> Image.Image:
    """把图像放进正方形透明画布，尽量保持原比例、留统一外边距。"""
    bbox = image.split()[3].getbbox()
    art = image.crop(bbox)
    scale = side * (1.0 - padding) / max(art.width, art.height)
    art = art.resize((max(1, round(art.width * scale)), max(1, round(art.height * scale))), Image.LANCZOS)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(art, ((side - art.width) // 2, side - art.height if anchor_bottom else (side - art.height) // 2), art)
    return canvas


def build_header_frames(gif_path: str, out_dir: str) -> int:
    """去掉底部文字，四边留白固定，逐帧输出。"""
    source = Image.open(gif_path)
    frames: list[Image.Image] = []
    for index in range(source.n_frames):
        source.seek(index)
        frames.append(source.convert("RGBA"))

    # 「好运来」保留，所以取整幅画面的并集包围盒
    x0 = y0 = 10**9
    x1 = y1 = -1
    for frame in frames:
        alpha = frame.split()[3]
        bbox = alpha.point(lambda v: 255 if v > 24 else 0).getbbox()
        if not bbox:
            continue
        x0, y0 = min(x0, bbox[0]), min(y0, bbox[1])
        x1, y1 = max(x1, bbox[2]), max(y1, bbox[3])

    # 统一取景：按并集包围盒裁死，四周留 6% 透明边，再按原比例缩放
    art_w, art_h = x1 - x0, y1 - y0
    pad = round(art_h * 0.06)
    frame_w = art_w + pad * 2
    frame_h = art_h + pad * 2
    target_h = HEADER_FRAME_HEIGHT
    target_w = round(target_h * frame_w / frame_h)

    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir)
    for index, frame in enumerate(frames):
        canvas = Image.new("RGBA", (frame_w, frame_h), (0, 0, 0, 0))
        canvas.paste(frame.crop((x0, y0, x1, y1)), (pad, pad))
        canvas.resize((target_w, target_h), Image.LANCZOS).save(
            os.path.join(out_dir, f"{index:02d}.png"), "PNG", optimize=True
        )
    return len(frames), target_w, target_h


def build_mascot(image_path: str, out_path: str) -> None:
    matted = matte_white_background(Image.open(image_path))
    square_canvas(matted, MASCOT_PX, padding=0.05).save(out_path, "PNG", optimize=True)


def sand_background(size: int) -> Image.Image:
    strip = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        strip.putpixel((0, y), tuple(round(SAND_TOP[i] + (SAND_BOTTOM[i] - SAND_TOP[i]) * t) for i in range(3)))
    return strip.resize((size, size))


ICON_CROP = (200, 140, 1340, 1280)  # 头肩构图：留着呆毛、头饰、比赞的手和左侧「!!」
# 画布 0.70 宽、让脸中心落在画布中心：66dp 安全圈里能装下呆毛、头饰和比赞的手
ICON_ART_WIDTH = 0.70
LEGACY_ART_WIDTH = 0.74
FACE_CENTER_RATIO = 0.49


def build_launcher(image_path: str, res_dir: str) -> None:
    art = matte_white_background(Image.open(image_path)).crop(ICON_CROP)
    densities = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
    for density, scale in densities.items():
        folder = os.path.join(res_dir, f"mipmap-{density}")

        foreground_px = round(108 * scale)
        foreground = Image.new("RGBA", (foreground_px, foreground_px), (0, 0, 0, 0))
        w = round(foreground_px * ICON_ART_WIDTH)
        h = round(w * art.height / art.width)
        scaled = art.resize((w, h), Image.LANCZOS)
        top = round(foreground_px * 0.5 - h * FACE_CENTER_RATIO)
        foreground.paste(scaled, ((foreground_px - w) // 2, top), scaled)
        foreground.save(os.path.join(folder, "ic_launcher_foreground.png"), "PNG", optimize=True)

        legacy_px = round(48 * scale)
        legacy = sand_background(legacy_px).convert("RGBA")
        w = round(legacy_px * LEGACY_ART_WIDTH)
        h = round(w * art.height / art.width)
        scaled = art.resize((w, h), Image.LANCZOS)
        top = round(legacy_px * 0.5 - h * FACE_CENTER_RATIO)
        legacy.paste(scaled, ((legacy_px - w) // 2, top), scaled)
        legacy.save(os.path.join(folder, "ic_launcher.png"), "PNG", optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gif", required=True, help="上方美化栏 GIF")
    parser.add_argument("--mascot", required=True, help="空记录高清立绘")
    parser.add_argument("--icon", required=True, help="启动图标立绘")
    args = parser.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    count, frame_w, frame_h = build_header_frames(args.gif, os.path.join(root, "app/src/main/assets/fish_header"))
    build_mascot(args.mascot, os.path.join(root, "app/src/main/res/drawable-nodpi/whale_mascot.png"))
    build_launcher(args.icon, os.path.join(root, "app/src/main/res"))
    print(f"header frames: {count} @ {frame_w}x{frame_h}")


if __name__ == "__main__":
    main()
