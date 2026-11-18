"""生成「蓝色肥鱼」皮肤用的位图资源。

三件事：
1. 顶部动画贴纸：把 `上方美化栏替换.gif` 底部的「好运来」统一取景后切成逐帧 PNG，
   并把字从「深蓝填充 + 白描边」翻成「白填充 + 深墨描边」（见 `recolor_sticker_text`）。
2. 空记录插画：把高清立绘（吃饭那张）抠掉白底，输出 `whale_mascot.png`。
3. 启动图标：把比赞那张抠底、裁成头肩构图，生成自适应前景与各密度旧版图标。

用法（仓库根目录）：python tools/gen_fish_art.py --gif <路径> --mascot <路径> --icon <路径>
只重刷已生成的头卡帧（手边没有原 GIF 时）：python tools/gen_fish_art.py --recolor-header
"""

from __future__ import annotations

import argparse
import os
import shutil

from PIL import Image, ImageChops, ImageDraw, ImageFilter

HEADER_FRAME_HEIGHT = 288  # 竖版贴纸：人物 + 底部「好运来」，保持原图 216:271 的比例
MASCOT_PX = 768

# 头卡是半透明的深海气泡，深蓝字会跟底色糊在一起：把字翻成白字 + 深墨描边。
TEXT_TOP_RATIO = 0.79  # 「好运来」顶部在竖版贴纸的 79% 处（228/288）
TEXT_FILL = (255, 255, 255)  # 原深蓝笔画 → 白
TEXT_OUTLINE = (16, 26, 56)  # 原近白描边 → 深墨（比深海底更深，白字才有边）
TEXT_FILL_LUMA = 0.20  # 原深蓝填充的亮度
TEXT_OUTLINE_LUMA = 0.97  # 原近白描边的亮度
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


def recolor_sticker_text(frame: Image.Image) -> Image.Image:
    """把底部「好运来」翻成白字：原深蓝笔画 → 白，原近白描边 → 深墨。

    字是像素图，只有「深蓝填充 + 近白描边」两档加抗锯齿过渡；按亮度在两色之间
    插值再映射回「白 ↔ 深墨」，等于一次双色反相 —— 字形、描边宽度、抗锯齿都原样
    保留，不会像直接阈值替换那样把笔画边缘砍成锯齿。
    """
    frame = frame.convert("RGBA")
    width, height = frame.size
    top = round(height * TEXT_TOP_RATIO)
    out = frame.copy()
    span = TEXT_OUTLINE_LUMA - TEXT_FILL_LUMA
    for y in range(top, height):
        for x in range(width):
            red, green, blue, alpha = frame.getpixel((x, y))
            if alpha == 0:
                continue
            luma = (0.299 * red + 0.587 * green + 0.114 * blue) / 255
            t = min(1.0, max(0.0, (luma - TEXT_FILL_LUMA) / span))
            out.putpixel(
                (x, y),
                (
                    round(TEXT_FILL[0] + (TEXT_OUTLINE[0] - TEXT_FILL[0]) * t),
                    round(TEXT_FILL[1] + (TEXT_OUTLINE[1] - TEXT_FILL[1]) * t),
                    round(TEXT_FILL[2] + (TEXT_OUTLINE[2] - TEXT_FILL[2]) * t),
                    alpha,
                ),
            )
    return out


def build_header_frames(gif_path: str, out_dir: str) -> int:
    """统一取景后逐帧输出，「好运来」保留并翻成白字。"""
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
        canvas = recolor_sticker_text(canvas.resize((target_w, target_h), Image.LANCZOS))
        # 像素图只有几十种颜色，转成调色板后体积降一个量级（69KB → 13KB/帧），
        # 边缘本来就是硬边，量化看不出差别。
        canvas.quantize(colors=64, method=Image.FASTOCTREE).save(
            os.path.join(out_dir, f"{index:02d}.png"), "PNG", optimize=True
        )
    return len(frames), target_w, target_h


def recolor_header_dir(out_dir: str) -> int:
    """没有原 GIF 时直接重刷已生成的头卡帧，帧名与尺寸保持不变。"""
    names = sorted(name for name in os.listdir(out_dir) if name.endswith(".png"))
    for name in names:
        path = os.path.join(out_dir, name)
        frame = recolor_sticker_text(Image.open(path).convert("RGBA"))
        frame.quantize(colors=64, method=Image.FASTOCTREE).save(path, "PNG", optimize=True)
    return len(names)


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
    parser.add_argument("--gif", help="上方美化栏 GIF")
    parser.add_argument("--mascot", help="空记录高清立绘")
    parser.add_argument("--icon", help="启动图标立绘")
    parser.add_argument(
        "--recolor-header",
        action="store_true",
        help="只把已生成的头卡帧重刷成白字（手边没有原 GIF 时用），不动其他资源",
    )
    args = parser.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    header_dir = os.path.join(root, "app/src/main/assets/fish_header")

    if args.recolor_header:
        print(f"recoloured header frames: {recolor_header_dir(header_dir)}")
        return
    if not (args.gif and args.mascot and args.icon):
        parser.error("要么给齐 --gif/--mascot/--icon，要么用 --recolor-header")

    count, frame_w, frame_h = build_header_frames(args.gif, header_dir)
    build_mascot(args.mascot, os.path.join(root, "app/src/main/res/drawable-nodpi/whale_mascot.png"))
    build_launcher(args.icon, os.path.join(root, "app/src/main/res"))
    print(f"header frames: {count} @ {frame_w}x{frame_h}")


if __name__ == "__main__":
    main()
