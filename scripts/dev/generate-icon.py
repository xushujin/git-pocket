#!/usr/bin/env python3
"""生成 GitPocket 启动图标（柔光拟态 / Tactile Minimalism）。

设计约束：
- 单一光源固定在左上方；体积只靠明暗过渡塑造，不描边、不发光、不叠多重投影。
- 低饱和近单色，靠明度拉层次；唯一的点缀色只出现在分支末端那颗小节点上。
- 形体连续圆润：底座是圆角方块，主体是一根从同一块材料上微微压出的分支管。

只依赖 Pillow。运行：
    python3 scripts/dev/generate-icon.py

产物：
    app/src/main/res/mipmap-*/ic_launcher_foreground.png   自适应图标前景（108dp 画布）
    docs/icon-512.png                                       商店 / README 用
    docs/icon-preview.png                                   多尺寸预览（自检用）
"""

from __future__ import annotations

import os
from PIL import Image, ImageDraw, ImageFilter

# ---------------------------------------------------------------- 设计参数

DESIGN = 1024  # 设计坐标空间

# 低饱和近单色：略带暖意的中性底，避免纯灰发脏、纯白刺眼
BASE_TOP = (232, 230, 223)
BASE_BOTTOM = (198, 194, 184)
LIGHT = (255, 255, 250)
SHADOW = (120, 112, 96)

# 主体比底座略亮：像从同一块材料上微微凸起
GLYPH_TOP = (251, 250, 246)
GLYPH_BOTTOM = (229, 226, 217)
# 唯一的点缀色：去饱和板岩蓝，只出现在分支末端
ACCENT = (104, 118, 148)
ACCENT_DARK = (78, 90, 116)

CORNER_RATIO = 0.228  # 圆角半径 / 边长
TUBE = 136  # 分支管粗细
NODE_R = 86  # 分叉处那颗节点的半径（比管子略粗一点，形成很轻的凸起）

# 主体几何（设计坐标）：一根竖管向上分成两支，节点落在分叉点
JUNCTION = (512, 566)
STEM_BOTTOM = (512, 800)
ARM_LEFT_END = (394, 350)
ARM_RIGHT_END = (642, 334)
ARM_CONTROL_DX = 34  # 支管起手先顺着主线走一点，避免和主线形成硬角


# ---------------------------------------------------------------- 基础工具


def _field(size: int, fn, resolution: int = 200) -> Image.Image:
    """按 0..1 归一化坐标计算灰度场，再放大到目标尺寸（避免逐像素跑大图）。"""
    small = Image.new("L", (resolution, resolution), 0)
    pixels = small.load()
    for y in range(resolution):
        ny = y / (resolution - 1)
        for x in range(resolution):
            pixels[x, y] = max(0, min(255, int(fn(x / (resolution - 1), ny) * 255)))
    return small.resize((size, size), Image.BICUBIC)


def _vertical_gradient(size: int, top, bottom) -> Image.Image:
    grad = Image.linear_gradient("L").resize((size, size), Image.BILINEAR)
    return Image.composite(
        Image.new("RGB", (size, size), bottom),
        Image.new("RGB", (size, size), top),
        grad,
    )


def _smoothstep(t: float) -> float:
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def _light_field(size: int) -> Image.Image:
    """唯一的柔光：左上一点，向外缓慢衰减，边缘自然消失。"""
    def fn(x: float, y: float) -> float:
        dx = (x - 0.26) * 1.15
        dy = (y - 0.20) * 1.35
        d = (dx * dx + dy * dy) ** 0.5
        return _smoothstep(1 - d / 0.78) ** 1.35
    return _field(size, fn)


def _topleft_field(size: int) -> Image.Image:
    """方向权重：左上 1、右下 0，用来把边缘高光与暗部分到两侧。"""
    return _field(size, lambda x, y: _smoothstep(1 - (x * 0.62 + y * 0.78)))


def squircle_mask(size: int, radius_ratio: float = CORNER_RATIO) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size - 1, size - 1), radius=int(size * radius_ratio), fill=255
    )
    return mask


def _quadratic(p0, p1, p2, steps: int = 72):
    points = []
    for index in range(steps + 1):
        t = index / steps
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]
        points.append((x, y))
    return points


def _tube(draw: ImageDraw.ImageDraw, points, width: int) -> None:
    """沿路径逐点盖圆。比 line(joint="curve") 干净：曲线不会被切成锯齿。"""
    r = width / 2
    dense = []
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        length = ((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5
        steps = max(2, int(length / max(1.0, r * 0.22)))
        for index in range(steps):
            t = index / steps
            dense.append((x0 + (x1 - x0) * t, y0 + (y1 - y0) * t))
    dense.append(points[-1])
    for x, y in dense:
        draw.ellipse((x - r, y - r, x + r, y + r), fill=255)


def glyph_mask(size: int) -> Image.Image:
    """分支：一根竖管在分叉点分成两支，节点落在分叉处。"""
    scale = size / DESIGN
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    tube = max(2, int(TUBE * scale))

    jx, jy = JUNCTION[0] * scale, JUNCTION[1] * scale
    _tube(draw, [(jx, jy), (STEM_BOTTOM[0] * scale, STEM_BOTTOM[1] * scale)], tube)
    for end in (ARM_LEFT_END, ARM_RIGHT_END):
        arm = _quadratic(
            (jx, jy),
            (jx + ARM_CONTROL_DX * scale * (1 if end[0] > JUNCTION[0] else -1), (JUNCTION[1] - 96) * scale),
            (end[0] * scale, end[1] * scale),
        )
        _tube(draw, arm, tube)

    r = NODE_R * scale
    draw.ellipse((jx - r, jy - r, jx + r, jy + r), fill=255)
    return mask


def _edge_ring(mask: Image.Image, width: int) -> Image.Image:
    """形状内侧的一圈边，用来做倒角的高光/暗部过渡。"""
    eroded = mask.filter(ImageFilter.MinFilter(width * 2 + 1))
    return Image.composite(mask, Image.new("L", mask.size, 0), Image.eval(eroded, lambda v: 255 - v))


def _paint(mask: Image.Image, color, strength: float, softness: float) -> Image.Image:
    """把一张灰度图当作 alpha，铺成一层带颜色的半透明像素。"""
    layer = Image.new("RGBA", mask.size, color + (0,))
    alpha = mask.filter(ImageFilter.GaussianBlur(softness))
    layer.putalpha(Image.eval(alpha, lambda v: int(v * strength)))
    return layer


def node_layer_at(size: int, cx: float, cy: float, r: float, accent, accent_dark) -> Image.Image:
    """任意位置的小球：同光源下左上亮、右下暗。"""
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=accent + (255,))

    light = _field(
        size,
        lambda x, y: _smoothstep(
            1 - (((x - (cx - r * 0.34) / size) ** 2 + (y - (cy - r * 0.38) / size) ** 2) ** 0.5)
            / (r * 2.6 / size)
        ),
    )
    layer.alpha_composite(_paint(light, (255, 255, 255), 0.42, size * 0.004))

    shade = _field(
        size,
        lambda x, y: _smoothstep(
            1 - (((x - (cx + r * 0.5) / size) ** 2 + (y - (cy + r * 0.55) / size) ** 2) ** 0.5)
            / (r * 2.4 / size)
        ),
    )
    layer.alpha_composite(_paint(shade, accent_dark, 0.55, size * 0.006))

    # 只保留圆形范围内的像素
    disc = Image.new("L", (size, size), 0)
    ImageDraw.Draw(disc).ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)
    layer.putalpha(Image.composite(layer.getchannel("A"), Image.new("L", (size, size), 0), disc))
    return layer


def _node_layer(size: int) -> Image.Image:
    scale = size / DESIGN
    return node_layer_at(
        size,
        JUNCTION[0] * scale,
        JUNCTION[1] * scale,
        NODE_R * scale,
        ACCENT,
        ACCENT_DARK,
    )


def _glyph_body(size: int) -> Image.Image:
    """主体：本体渐变 + 左上高光边 + 节点。"""
    glyph = glyph_mask(size)
    body = _vertical_gradient(size, GLYPH_TOP, GLYPH_BOTTOM).convert("RGBA")
    body.putalpha(glyph)

    ring = _edge_ring(glyph, max(2, size // 150))
    rim_alpha = Image.composite(ring, Image.new("L", (size, size), 0), _topleft_field(size))
    body.alpha_composite(_paint(rim_alpha, LIGHT, 0.62, size * 0.005))

    # 节点自身也压出一个很轻的接触阴影，才像"落"在材料上，而不是贴上去
    scale = size / DESIGN
    disc = Image.new("L", (size, size), 0)
    r = NODE_R * scale
    cx, cy = JUNCTION[0] * scale, JUNCTION[1] * scale
    ImageDraw.Draw(disc).ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)
    body.alpha_composite(_paint(disc, SHADOW, 0.22, size * 0.009), (int(size * 0.004), int(size * 0.008)))

    body.alpha_composite(_node_layer(size))
    return body


# ---------------------------------------------------------------- 两种成品


def render_plate(size: int = DESIGN) -> Image.Image:
    """完整图标：底座 + 主体（商店图 / 预览用）。"""
    plate = squircle_mask(size)
    canvas = _vertical_gradient(size, BASE_TOP, BASE_BOTTOM).convert("RGBA")
    canvas.putalpha(plate)

    # 柔光（唯一光源）
    canvas.alpha_composite(_paint(_light_field(size), LIGHT, 0.55, size * 0.02))

    # 底座倒角：左上一条极淡的高光，右下一条更淡的暗部
    ring = _edge_ring(plate, max(3, size // 90))
    top = Image.composite(ring, Image.new("L", (size, size), 0), _topleft_field(size))
    canvas.alpha_composite(_paint(top, LIGHT, 0.50, size * 0.012))
    bottom = Image.composite(ring, Image.new("L", (size, size), 0), _topleft_field(size).point(lambda v: 255 - v))
    canvas.alpha_composite(_paint(bottom, SHADOW, 0.26, size * 0.012))

    # 主体投影：方向与光源一致（向右下），柔和、不堆叠
    glyph = glyph_mask(size)
    shadow = _paint(glyph, SHADOW, 0.32, size * 0.022)
    canvas.alpha_composite(shadow, (int(size * 0.008), int(size * 0.014)))

    canvas.alpha_composite(_glyph_body(size))
    return canvas


def render_foreground(size: int = DESIGN) -> Image.Image:
    """自适应图标前景：只画主体与它的柔影，四周留足安全区。"""
    work = DESIGN
    composed = Image.new("RGBA", (work, work), (0, 0, 0, 0))
    shadow = _paint(glyph_mask(work), SHADOW, 0.32, work * 0.022)
    composed.alpha_composite(shadow, (int(work * 0.008), int(work * 0.014)))
    composed.alpha_composite(_glyph_body(work))

    bbox = composed.getchannel("A").getbbox()
    cropped = composed.crop(bbox)

    target = int(size * 0.56)
    ratio = target / max(cropped.width, cropped.height)
    scaled = cropped.resize(
        (max(1, round(cropped.width * ratio)), max(1, round(cropped.height * ratio))),
        Image.LANCZOS,
    )
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(scaled, ((size - scaled.width) // 2, (size - scaled.height) // 2))
    return canvas


DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def main() -> None:
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    res = os.path.join(root, "app", "src", "main", "res")
    docs = os.path.join(root, "docs")
    os.makedirs(docs, exist_ok=True)

    plate = render_plate(1024)
    foreground = render_foreground(1024)

    for bucket, factor in DENSITIES.items():
        folder = os.path.join(res, f"mipmap-{bucket}")
        os.makedirs(folder, exist_ok=True)
        edge = int(round(108 * factor))
        foreground.resize((edge, edge), Image.LANCZOS).save(
            os.path.join(folder, "ic_launcher_foreground.png")
        )

    plate.resize((512, 512), Image.LANCZOS).save(os.path.join(docs, "icon-512.png"))

    sizes = [192, 96, 72, 48]
    gap = 24
    width = sum(sizes) + gap * (len(sizes) + 1)
    preview = Image.new("RGBA", (width, max(sizes) + gap * 2), (246, 245, 241, 255))
    x = gap
    for edge in sizes:
        preview.alpha_composite(
            plate.resize((edge, edge), Image.LANCZOS),
            (x, gap + (max(sizes) - edge) // 2),
        )
        x += edge + gap
    preview.save(os.path.join(docs, "icon-preview.png"))

    print("icon: 生成完成")
    for bucket, factor in DENSITIES.items():
        print(f"  mipmap-{bucket}/ic_launcher_foreground.png  {int(round(108 * factor))}px")
    print("  docs/icon-512.png, docs/icon-preview.png")


if __name__ == "__main__":
    main()
