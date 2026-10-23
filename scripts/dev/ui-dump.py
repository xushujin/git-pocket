#!/usr/bin/env python3
"""打印当前界面的文本 + bounds，用于点击定位（不靠截图目测）。"""
import re
import subprocess
import sys

ADB = ".tooling/android-sdk/platform-tools/adb"


def main() -> None:
    subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                   capture_output=True)
    xml = subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"],
                         capture_output=True, text=True).stdout
    pattern = re.compile(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    rows = []
    for m in pattern.finditer(xml):
        text, x1, y1, x2, y2 = m.groups()
        if not text.strip():
            continue
        rows.append((int(y1), int(x1), int(x2), int(y2), text))
    for y1, x1, x2, y2, text in sorted(rows):
        print(f"{text[:48]:<50} tap=({(x1 + x2) // 2},{(y1 + y2) // 2})  [{x1},{y1}][{x2},{y2}]")
    # 键盘可见性：ime 区域高度
    out = subprocess.run([ADB, "shell", "dumpsys", "input_method"],
                         capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "mInputShown" in line or "mIsInputViewShown" in line:
            print("[ime]", line.strip())
            break


if __name__ == "__main__":
    sys.exit(main())
