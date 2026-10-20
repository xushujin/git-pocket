package app.gitpocket.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 拟态修饰符。
 *
 * 实现取舍：不使用 RenderEffect/setShadowLayer 的模糊（API 差异大、列表里开销高），
 * 改用「多层同形状错位叠加」模拟柔和阴影 —— 全 API 表现一致、可预测、无模糊开销。
 * 列表行请用 [neumorphicFlat]（单层描边），只有卡片/主按钮用 [neumorphicRaised]。
 */

@Composable
fun Modifier.neumorphicRaised(
    shape: Shape = RoundedCornerShape(20.dp),
    depth: Dp = 6.dp,
    pressed: Boolean = false,
): Modifier {
    val palette = LocalNeumorphicPalette.current
    val actualDepth = if (pressed) depth * 0.35f else depth
    return this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val radius = actualDepth.toPx()
        val offset = radius * 0.55f
        val face = if (pressed) palette.base else palette.surface
        val shadowAlpha = if (palette.isDark) 0.30f else 0.16f
        val lightAlpha = if (palette.isDark) 0.22f else 0.55f

        onDrawBehind {
            drawOutline(outline, palette.base)
            if (!pressed) {
                drawLayeredShadow(outline, size, offset, offset, radius, palette.darkShadow, shadowAlpha)
                drawLayeredShadow(outline, size, -offset, -offset, radius, palette.lightShadow, lightAlpha)
            }
            drawOutline(outline, face)
        }
    }
}

/** 扁平拟态：只画底 + 双色 1dp 描边，零阴影开销，用于列表项。 */
@Composable
fun Modifier.neumorphicFlat(
    shape: Shape = RoundedCornerShape(14.dp),
    borderWidth: Dp = 1.dp,
): Modifier {
    val palette = LocalNeumorphicPalette.current
    return this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(width = borderWidth.toPx())
        val brush = Brush.linearGradient(
            colors = listOf(palette.lightShadow, palette.darkShadow),
            start = androidx.compose.ui.geometry.Offset.Zero,
            end = androidx.compose.ui.geometry.Offset(size.width, size.height),
        )
        onDrawBehind {
            drawOutline(outline, palette.surface)
            drawOutline(outline, brush = brush, style = stroke)
        }
    }
}

/** 内凹（输入框、代码块）。用「左上暗边 + 右下亮边」表达凹陷。 */
@Composable
fun Modifier.neumorphicInset(
    shape: Shape = RoundedCornerShape(14.dp),
): Modifier {
    val palette = LocalNeumorphicPalette.current
    return this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(width = 1.dp.toPx())
        onDrawBehind {
            drawOutline(outline, palette.base)
            drawOutline(
                outline = outline,
                brush = Brush.linearGradient(
                    colors = listOf(palette.darkShadow, palette.lightShadow),
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                ),
                style = stroke,
            )
        }
    }
}

private fun DrawScope.drawLayeredShadow(
    outline: Outline,
    size: Size,
    dx: Float,
    dy: Float,
    radius: Float,
    color: Color,
    totalAlpha: Float,
    layers: Int = 6,
) {
    val step = radius / layers
    val perLayer = totalAlpha / layers * 1.6f
    for (i in 1..layers) {
        val t = i.toFloat() / layers
        translate(dx * t, dy * t) {
            drawOutline(outline, color.copy(alpha = perLayer * (1.15f - 0.5f * t)))
        }
    }
    // size 参数保留用于未来的圆角外扩，避免签名随实现变化
    check(size.width >= 0f)
}
