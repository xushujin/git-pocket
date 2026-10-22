package app.gitpocket.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 文件 / 文件夹图标。
 *
 * 不用 emoji：emoji 由系统字体渲染，颜色、线条粗细、基线都不受主题控制，
 * 在拟态界面里会显得"贴上去的"。这里按 24×24 网格自绘，
 * 只用当前配色的强调色/次要色 + 透明度分层，明暗主题都自洽。
 */
@Composable
fun FileTypeIcon(
    isDirectory: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val palette = LocalNeumorphicPalette.current
    val base = if (isDirectory) palette.accent else palette.contentSecondary

    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)

        if (isDirectory) {
            // 文件夹：左上标签 + 主体，两层透明度做出厚度
            drawRoundRect(
                color = base.copy(alpha = 0.55f),
                topLeft = p(1.5f, 3f),
                size = Size(9.5f * unit, 6f * unit),
                cornerRadius = CornerRadius(2f * unit),
            )
            drawRoundRect(
                color = base.copy(alpha = 0.85f),
                topLeft = p(1.5f, 6f),
                size = Size(21f * unit, 15f * unit),
                cornerRadius = CornerRadius(3.2f * unit),
            )
            // 顶部一条更亮的高光，模拟拟态受光面
            drawRoundRect(
                color = Color.White.copy(alpha = if (palette.isDark) 0.10f else 0.45f),
                topLeft = p(3.5f, 7.6f),
                size = Size(17f * unit, 2f * unit),
                cornerRadius = CornerRadius(1f * unit),
            )
        } else {
            // 文件：右上折角
            val body = Path().apply {
                moveTo(4.5f * unit, 2.5f * unit)
                lineTo(14f * unit, 2.5f * unit)
                lineTo(19.5f * unit, 8f * unit)
                lineTo(19.5f * unit, 21.5f * unit)
                lineTo(4.5f * unit, 21.5f * unit)
                close()
            }
            drawPath(body, color = base.copy(alpha = 0.85f))

            // 折角三角：用底色叠一层，形成"纸角"的观感
            val fold = Path().apply {
                moveTo(14f * unit, 2.5f * unit)
                lineTo(14f * unit, 8f * unit)
                lineTo(19.5f * unit, 8f * unit)
                close()
            }
            drawPath(
                fold,
                color = if (palette.isDark) Color.Black.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.55f),
            )

            // 两条内容线
            val line = if (palette.isDark) Color.Black.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.60f)
            drawRoundRect(
                color = line,
                topLeft = p(7.5f, 12f),
                size = Size(9f * unit, 1.6f * unit),
                cornerRadius = CornerRadius(0.8f * unit),
            )
            drawRoundRect(
                color = line,
                topLeft = p(7.5f, 16f),
                size = Size(6f * unit, 1.6f * unit),
                cornerRadius = CornerRadius(0.8f * unit),
            )
        }
    }
}

/** 折角标记（供文件行右侧状态等场景复用）。 */
@Composable
fun ChevronGlyph(
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    color: Color = LocalNeumorphicPalette.current.contentSecondary,
) {
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 14f
        val path = Path().apply {
            moveTo(5f * unit, 2f * unit)
            lineTo(10f * unit, 7f * unit)
            lineTo(5f * unit, 12f * unit)
        }
        drawPath(path, color = color, style = Stroke(width = 1.8f * unit))
    }
}
