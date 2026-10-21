package app.gitpocket.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gitpocket.core.common.markdown.ColumnAlign
import app.gitpocket.core.common.markdown.MdBlock
import app.gitpocket.core.common.syntax.Language

/**
 * Markdown 渲染（原生 Compose，不依赖 WebView / 第三方库）。
 *
 * 支持：标题、段落、围栏代码块（带语法高亮）、有序/无序列表、引用、分割线，
 * 行内支持 `code`、**粗体**、*斜体*、~~删除线~~、[链接](url)（仅样式，不可点击）。
 */
@Composable
fun MarkdownView(blocks: List<MdBlock>, modifier: Modifier = Modifier) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inline(block.text, palette),
                    style = headingStyle(block.level, typography),
                    color = palette.contentPrimary,
                )

                is MdBlock.Paragraph -> Text(
                    text = inline(block.text, palette),
                    style = typography.body,
                    color = palette.contentPrimary,
                )

                is MdBlock.CodeBlock -> MarkdownCodeBlock(block)

                is MdBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { item ->
                        Row {
                            Text(
                                text = "•",
                                style = typography.body,
                                color = palette.accent,
                                modifier = Modifier.width(16.dp),
                            )
                            Text(
                                text = inline(item, palette),
                                style = typography.body,
                                color = palette.contentPrimary,
                            )
                        }
                    }
                }

                is MdBlock.OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row {
                            Text(
                                text = "${block.start + index}.",
                                style = typography.body,
                                color = palette.accent,
                                modifier = Modifier.width(24.dp),
                            )
                            Text(
                                text = inline(item, palette),
                                style = typography.body,
                                color = palette.contentPrimary,
                            )
                        }
                    }
                }

                is MdBlock.Quote -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            drawRect(
                                color = palette.accent,
                                size = Size(3.dp.toPx(), size.height),
                            )
                        }
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = inline(block.text, palette),
                        style = typography.body.copy(fontStyle = FontStyle.Italic),
                        color = palette.contentSecondary,
                    )
                }

                is MdBlock.Table -> MarkdownTable(block)

                MdBlock.Divider -> GpDivider()
            }
        }
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .neumorphicInset(RoundedCornerShape(12.dp)),
    ) {
        TableRow(cells = table.header, aligns = table.aligns, header = true)
        table.rows.forEach { row ->
            TableRow(cells = row, aligns = table.aligns, header = false)
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, aligns: List<ColumnAlign>, header: Boolean) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    val columnCount = maxOf(cells.size, aligns.size, 1)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (header) palette.accent.copy(alpha = 0.10f) else Color.Transparent)
            .drawBehind {
                drawLine(
                    color = palette.contentSecondary.copy(alpha = 0.18f),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        repeat(columnCount) { index ->
            val cell = cells.getOrNull(index).orEmpty()
            val align = aligns.getOrNull(index) ?: ColumnAlign.LEFT
            Text(
                text = inline(cell, palette),
                style = if (header) typography.bodyStrong else typography.body,
                color = if (header) palette.accent else palette.contentPrimary,
                textAlign = when (align) {
                    ColumnAlign.LEFT -> TextAlign.Start
                    ColumnAlign.CENTER -> TextAlign.Center
                    ColumnAlign.RIGHT -> TextAlign.End
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MarkdownCodeBlock(block: MdBlock.CodeBlock) {
    val palette = LocalNeumorphicPalette.current
    val typography = LocalGitPocketTypography.current
    val language = remember(block.language) { Language.fromFence(block.language) }
    val highlighted = rememberHighlightedCode(block.code, language)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .neumorphicInset(RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        val fenceLanguage = block.language
        if (!fenceLanguage.isNullOrBlank()) {
            Text(
                text = fenceLanguage,
                style = typography.caption,
                color = palette.contentSecondary,
            )
            Spacer(Modifier.height(6.dp))
        }
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                text = highlighted,
                style = typography.mono.copy(
                    fontFamily = FontFamily.Monospace,
                    color = palette.contentPrimary,
                ),
            )
        }
    }
}

private fun headingStyle(level: Int, typography: GitPocketTypography): TextStyle {
    val size = when (level) {
        1 -> 26.sp
        2 -> 22.sp
        3 -> 19.sp
        4 -> 17.sp
        5 -> 16.sp
        else -> 15.sp
    }
    return typography.body.copy(fontSize = size, lineHeight = size * 1.35f, fontWeight = FontWeight.SemiBold)
}

/** 行内样式解析：产出去掉标记后的文本 + 对应样式区间。 */
private fun inline(text: String, palette: NeumorphicPalette): AnnotatedString {
    val out = StringBuilder(text.length)
    val spans = ArrayList<Triple<Int, Int, SpanStyle>>()
    var i = 0

    while (i < text.length) {
        when {
            text[i] == '`' && text.indexOf('`', i + 1) > i -> {
                val end = text.indexOf('`', i + 1)
                val start = out.length
                out.append(text, i + 1, end)
                spans.add(
                    Triple(
                        start,
                        out.length,
                        SpanStyle(color = palette.success, fontFamily = FontFamily.Monospace),
                    )
                )
                i = end + 1
            }

            text.startsWith("**", i) && text.indexOf("**", i + 2) > i -> {
                val end = text.indexOf("**", i + 2)
                val start = out.length
                out.append(text, i + 2, end)
                spans.add(Triple(start, out.length, SpanStyle(fontWeight = FontWeight.Bold)))
                i = end + 2
            }

            text.startsWith("~~", i) && text.indexOf("~~", i + 2) > i -> {
                val end = text.indexOf("~~", i + 2)
                val start = out.length
                out.append(text, i + 2, end)
                spans.add(Triple(start, out.length, SpanStyle(textDecoration = TextDecoration.LineThrough)))
                i = end + 2
            }

            text[i] == '[' -> {
                val closeBracket = text.indexOf(']', i + 1)
                val openParen = if (closeBracket > 0) closeBracket + 1 else -1
                if (closeBracket > i && openParen < text.length && text[openParen] == '(') {
                    val closeParen = text.indexOf(')', openParen + 1)
                    if (closeParen > openParen) {
                        val start = out.length
                        out.append(text, i + 1, closeBracket)
                        spans.add(
                            Triple(
                                start,
                                out.length,
                                SpanStyle(color = palette.accent, textDecoration = TextDecoration.Underline),
                            )
                        )
                        i = closeParen + 1
                    } else {
                        out.append(text[i]); i++
                    }
                } else {
                    out.append(text[i]); i++
                }
            }

            text[i] == '*' && text.indexOf('*', i + 1) > i -> {
                val end = text.indexOf('*', i + 1)
                val start = out.length
                out.append(text, i + 1, end)
                spans.add(Triple(start, out.length, SpanStyle(fontStyle = FontStyle.Italic)))
                i = end + 1
            }

            else -> {
                out.append(text[i])
                i++
            }
        }
    }

    val builder = AnnotatedString.Builder(out.toString())
    spans.forEach { (start, end, style) -> builder.addStyle(style, start, end) }
    return builder.toAnnotatedString()
}
