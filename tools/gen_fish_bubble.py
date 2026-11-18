"""把「默认状态 / 触碰时」两个气泡 GIF 切成头卡贴纸用的逐帧 PNG。

两套动画共用同一个取景：以「默认状态」的并集包围盒取正方形画布，
这样戳破复原时气泡不会跳位。GIF 本身是透明底、6 色的像素画，
直接按帧导出（不缩放），运行时用 NEAREST 缩放，硬边不会糊。

用法（仓库根目录）：
    python tools/gen_fish_bubble.py --idle <默认状态.gif> --tap <触碰时.gif>
"""

from __future__ import annotations

import argparse
import os
import shutil

from PIL import Image

IDLE_FRAMES = 16
TAP_FRAMES = 20
BUBBLE_MARGIN = 8  # 裁切时给气泡本体留的像素余量


def read_frames(path: str) -> list[Image.Image]:
    source = Image.open(path)
    frames: list[Image.Image] = []
    for index in range(source.n_frames):
        source.seek(index)
        frames.append(source.convert("RGBA"))
    return frames


def body_box(frames: list[Image.Image]) -> tuple[int, int, int, int]:
    """所有非空帧的并集包围盒。"""
    boxes = [box for box in (frame.split()[3].getbbox() for frame in frames) if box]
    return (
        min(box[0] for box in boxes),
        min(box[1] for box in boxes),
        max(box[2] for box in boxes),
        max(box[3] for box in boxes),
    )


def square_crop(box: tuple[int, int, int, int], canvas: tuple[int, int]) -> tuple[int, int, int, int]:
    """以包围盒中心取正方形，边长取长边 + 余量，并夹在画布内。"""
    left, top, right, bottom = box
    side = max(right - left, bottom - top) + BUBBLE_MARGIN * 2
    cx = (left + right) / 2
    cy = (top + bottom) / 2
    x0 = round(cx - side / 2)
    y0 = round(cy - side / 2)
    x0 = max(0, min(x0, canvas[0] - side))
    y0 = max(0, min(y0, canvas[1] - side))
    return x0, y0, x0 + side, y0 + side


def write_frames(frames: list[Image.Image], crop, out_dir: str) -> None:
    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir)
    for index, frame in enumerate(frames):
        frame.crop(crop).save(os.path.join(out_dir, f"{index:02d}.png"), "PNG", optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--idle", required=True, help="默认状态.gif")
    parser.add_argument("--tap", required=True, help="触碰时.gif")
    args = parser.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "app/src/main/assets/fish_bubble")

    idle = read_frames(args.idle)
    tap = read_frames(args.tap)
    crop = square_crop(body_box(idle), Image.open(args.idle).size)

    write_frames(idle, crop, os.path.join(out, "idle"))
    write_frames(tap, crop, os.path.join(out, "tap"))
    print(f"bubble frames: idle={len(idle)} tap={len(tap)} crop={crop} side={crop[2] - crop[0]}")


if __name__ == "__main__":
    main()
