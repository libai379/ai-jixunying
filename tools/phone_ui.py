"""在插线的安卓手机上按文字点界面、截图（真机测试用，不用记坐标）。

用法（Git Bash，中文输出要加 -X utf8；-I 会忽略 PYTHONIOENCODING）：
  python -I -X utf8 tools/phone_ui.py dump              列出屏幕上的文字和坐标
  python -I -X utf8 tools/phone_ui.py tap 文字 [第几个]   点第 n 个（默认 0）包含这段文字的控件
  python -I -X utf8 tools/phone_ui.py shot 名字 [目录]    截图，缩到 1/3 存成 名字.jpg（默认存当前目录）

多台设备时用环境变量 JXY_PHONE 指定序列号。adb 默认用 F:\\android_sdk\\platform-tools\\adb.exe，可用 ADB 环境变量改。
"""
import io
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", r"F:\android_sdk\platform-tools\adb.exe")
DEV = os.environ.get("JXY_PHONE", "")


def adb(*args, binary=False):
    cmd = [ADB] + (["-s", DEV] if DEV else []) + list(args)
    out = subprocess.run(cmd, capture_output=True)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/jxy_ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/jxy_ui.xml")
    root = ET.fromstring(xml[xml.index("<"):])
    res = []
    for n in root.iter("node"):
        label = n.get("text") or n.get("content-desc") or ""
        if not label:
            continue
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        res.append((label, (x1 + x2) // 2, (y1 + y2) // 2, n.get("bounds")))
    return res


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "dump":
        for label, x, y, b in nodes():
            print(f"{label!r} @ {x},{y} {b}")
    elif cmd == "tap":
        text = sys.argv[2]
        idx = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        hits = [n for n in nodes() if text in n[0]]
        if len(hits) <= idx:
            print(f"没找到：{text!r}（可能在屏幕外，要先滑过去）")
            sys.exit(1)
        label, x, y, _ = hits[idx]
        adb("shell", "input", "tap", str(x), str(y))
        print(f"点了 {label!r} @ {x},{y}")
    elif cmd == "shot":
        from PIL import Image
        out_dir = sys.argv[3] if len(sys.argv) > 3 else "."
        im = Image.open(io.BytesIO(adb("exec-out", "screencap", "-p", binary=True))).convert("RGB")
        path = os.path.join(out_dir, sys.argv[2] + ".jpg")
        im.resize((im.size[0] // 3, im.size[1] // 3)).save(path, quality=80)
        print("存到", path)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
