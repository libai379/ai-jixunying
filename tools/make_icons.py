"""
从一张原图生成 AI集训营 的全部图标（2026-10-12，用户给的「戴学士帽的机器人 + AI集训营」那张）。

用法（仓库根目录）：python -I -X utf8 tools/make_icons.py art/app-icon-source.png
需要 Pillow。原图换了就重跑一次，然后重新打包。

生成：
- art/app-icon-1024.png：主图。裁出原图里的圆角方块（四角透明，留住边上的光）。
- shared/src/desktopMain/resources/jxy-app-icon.png（256）：电脑窗口 / 任务栏图标、App 里左上角和「关于」页的图标。
- desktopApp/icons/ai-jixunying.ico（16～256 多种尺寸）：Windows 安装包和 exe 的图标。
- androidApp/src/main/res/drawable-nodpi/jxy_app_icon.png（256）：手机 App 里左上角和「关于」页的图标。
- androidApp/src/main/res/mipmap-*/ic_launcher_foreground.png：安卓启动图标的前景层（自适应图标，108dp 画布）。
  整张主图占中间 72dp（启动器遮罩之后看得见的就是这 72dp）：三星这类圆角方形遮罩下和原图一样；
  圆形遮罩的启动器会把「AI」「营」两边切掉一点（2026-10-12 做预览比过，按用户的三星手机选的这个）。
  背景层是纯色 @color/ic_launcher_background（#0B1424，原图四角的深蓝）。

原图里圆角方块的位置是量出来的（中线上从外往里找第一个亮度 > 60 的点：左 96、右 1157、上 86、下 1157；
圆角半径用几行的左边界代入圆方程估的，约 250 像素）。换了原图要重新量：改下面 BOX_* 和 RADIUS。
"""
import os
import sys

from PIL import Image, ImageDraw

BOX_LEFT, BOX_RIGHT, BOX_TOP, BOX_BOTTOM = 96, 1157, 86, 1157
RADIUS = 250
GLOW = 14  # 方块外面再留 14 像素，边上的光不被切掉


def rounded_mask(size: int, radius: int, ss: int = 4) -> Image.Image:
    m = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size * ss - 1, size * ss - 1], radius * ss, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def master_from(src: Image.Image) -> Image.Image:
    cx = (BOX_LEFT + BOX_RIGHT) / 2
    cy = (BOX_TOP + BOX_BOTTOM) / 2
    half = max(BOX_RIGHT - BOX_LEFT, BOX_BOTTOM - BOX_TOP) / 2 + GLOW
    sq = src.crop((round(cx - half), round(cy - half), round(cx + half), round(cy + half)))
    sq.putalpha(rounded_mask(sq.size[0], RADIUS + GLOW))
    return sq.resize((1024, 1024), Image.LANCZOS)


def save(img: Image.Image, path: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("写好", path, img.size)


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("用法：python -I -X utf8 tools/make_icons.py <原图>")
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src = Image.open(sys.argv[1]).convert("RGBA")
    master = master_from(src)
    save(master, os.path.join(root, "art", "app-icon-1024.png"))

    small = master.resize((256, 256), Image.LANCZOS)
    save(small, os.path.join(root, "shared", "src", "desktopMain", "resources", "jxy-app-icon.png"))
    save(small, os.path.join(root, "androidApp", "src", "main", "res", "drawable-nodpi", "jxy_app_icon.png"))

    ico = os.path.join(root, "desktopApp", "icons", "ai-jixunying.ico")
    os.makedirs(os.path.dirname(ico), exist_ok=True)
    master.save(ico, sizes=[(16, 16), (20, 20), (24, 24), (32, 32), (40, 40), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("写好", ico)

    # 安卓自适应图标前景：108dp 画布，主图占中间 72dp
    for folder, px in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]:
        canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        inner = round(px * 72 / 108)
        canvas.alpha_composite(master.resize((inner, inner), Image.LANCZOS), ((px - inner) // 2, (px - inner) // 2))
        save(canvas, os.path.join(root, "androidApp", "src", "main", "res", f"mipmap-{folder}", "ic_launcher_foreground.png"))


if __name__ == "__main__":
    main()
