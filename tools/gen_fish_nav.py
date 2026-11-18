"""从四张像素小图 GIF 生成底栏用的逐帧 PNG。

源图是 320x320 的透明背景 GIF，底部有一条深蓝文字（“怕怕”“头秃了”之类）。
文字和人物之间只有 1px 空隙，所以直接裁掉 y>=253 的部分；再把人物窗口
放大到统一画布上留白，保证四张图的头身比例一致。

用法：
    python tools/gen_fish_nav.py [源目录]

默认源目录是桌面上的素材文件夹，产物写到 app/src/main/assets/fish_nav/。
"""

from __future__ import annotations

import glob
import os
import sys

from PIL import Image

TEXT_TOP = 253          # 文字带起始行（源图坐标）
WINDOW_WIDTH = 242      # 人物窗口宽（四张图的最大宽度）
WINDOW_HEIGHT = 230     # 人物窗口高（22..252）
WINDOW_TOP = 22
CANVAS = 280            # 留白后的方画布
TARGET = 120            # 输出边长，36dp @3x 有余量

FILE_TO_DIR = {
    "首页": "home",
    "记账": "ledger",
    "统计": "stats",
    "设置": "settings",
}

DEFAULT_SRC = os.path.expanduser(r"~\Desktop\新建文件夹")
OUT_DIR = os.path.join("app", "src", "main", "assets", "fish_nav")


def char_box(frame: Image.Image, threshold: int = 20) -> tuple[int, int, int, int]:
    """人物区域（不含文字带）的不透明包围盒。"""
    alpha = frame.crop((0, 0, frame.width, TEXT_TOP)).split()[3]
    return alpha.point(lambda v: 255 if v > threshold else 0).getbbox()


def convert(path: str, out_root: str) -> tuple[str, int]:
    name = os.path.splitext(os.path.basename(path))[0]
    directory = FILE_TO_DIR[name]
    gif = Image.open(path)
    frames = []
    for index in range(gif.n_frames):
        gif.seek(index)
        frames.append(gif.convert("RGBA"))

    boxes = [char_box(frame) for frame in frames]
    left = min(box[0] for box in boxes)
    right = max(box[2] for box in boxes)
    centre = (left + right) // 2
    crop_left = centre - WINDOW_WIDTH // 2

    target_dir = os.path.join(out_root, directory)
    os.makedirs(target_dir, exist_ok=True)
    for index, frame in enumerate(frames):
        window = frame.crop(
            (crop_left, WINDOW_TOP, crop_left + WINDOW_WIDTH, WINDOW_TOP + WINDOW_HEIGHT),
        )
        canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
        canvas.paste(window, ((CANVAS - WINDOW_WIDTH) // 2, (CANVAS - WINDOW_HEIGHT) // 2))
        canvas.resize((TARGET, TARGET), Image.LANCZOS).save(
            os.path.join(target_dir, f"{index:02d}.png"),
            "PNG",
            optimize=True,
        )
    return directory, len(frames)


def main() -> None:
    source = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC
    gifs = sorted(glob.glob(os.path.join(source, "*.gif")))
    if not gifs:
        raise SystemExit(f"没有在 {source} 找到 GIF")
    for path in gifs:
        name = os.path.splitext(os.path.basename(path))[0]
        if name not in FILE_TO_DIR:
            print(f"跳过 {name}")
            continue
        directory, count = convert(path, OUT_DIR)
        print(f"{name} -> assets/fish_nav/{directory} ({count} 帧)")


if __name__ == "__main__":
    main()
