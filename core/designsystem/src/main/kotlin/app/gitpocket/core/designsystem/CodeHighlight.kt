package app.gitpocket.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import app.gitpocket.core.common.syntax.Language
import app.gitpocket.core.common.syntax.SyntaxHighlighter
import app.gitpocket.core.common.syntax.TokenKind
import app.gitpocket.core.common.syntax.TokenSpan

/** 词法类别 -> 颜色。集中一处，编辑器与 Markdown 代码块共用。 */
fun codeSpanStyles(palette: NeumorphicPalette): Map<TokenKind, SpanStyle> = mapOf(
    TokenKind.KEYWORD to SpanStyle(color = palette.accent, fontWeight = FontWeight.Medium),
    TokenKind.TYPE to SpanStyle(color = palette.accent),
    TokenKind.STRING to SpanStyle(color = palette.success),
    TokenKind.COMMENT to SpanStyle(
        color = palette.contentSecondary.copy(alpha = 0.8f),
        fontStyle = FontStyle.Italic,
    ),
    TokenKind.NUMBER to SpanStyle(color = palette.danger.copy(alpha = 0.9f)),
    TokenKind.FUNCTION to SpanStyle(color = palette.contentPrimary, fontWeight = FontWeight.Medium),
    TokenKind.ANNOTATION to SpanStyle(color = palette.accent.copy(alpha = 0.85f)),
    TokenKind.TAG to SpanStyle(color = palette.accent),
    TokenKind.ATTRIBUTE to SpanStyle(color = palette.success.copy(alpha = 0.9f)),
    TokenKind.HEADING to SpanStyle(color = palette.accent, fontWeight = FontWeight.Bold),
    TokenKind.LINK to SpanStyle(color = palette.accent, textDecoration = TextDecoration.Underline),
    TokenKind.EMPHASIS to SpanStyle(fontWeight = FontWeight.Bold),
    TokenKind.CODE to SpanStyle(color = palette.success),
    TokenKind.MARKER to SpanStyle(color = palette.contentSecondary),
)

class SyntaxVisualTransformation(
    private val styles: Map<TokenKind, SpanStyle>,
    private val spans: List<TokenSpan>,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (spans.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val builder = AnnotatedString.Builder(text)
        spans.forEach { span ->
            val style = styles[span.kind] ?: return@forEach
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (end > start) builder.addStyle(style, start, end)
        }
        // 只加样式、不改字符，偏移映射保持恒等
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/** 编辑器用：文本变化时重新词法分析（O(n) 单遍，10KB 级无感）。 */
@Composable
fun rememberSyntaxTransformation(text: String, language: Language): VisualTransformation {
    val palette = LocalNeumorphicPalette.current
    val styles = remember(palette) { codeSpanStyles(palette) }
    val spans = remember(text, language) { SyntaxHighlighter.tokenize(text, language) }
    return remember(spans, styles) { SyntaxVisualTransformation(styles, spans) }
}

/** 只读场景（Markdown 代码块）用：直接产出带样式的 AnnotatedString。 */
@Composable
fun rememberHighlightedCode(code: String, language: Language): AnnotatedString {
    val palette = LocalNeumorphicPalette.current
    val styles = remember(palette) { codeSpanStyles(palette) }
    val spans = remember(code, language) { SyntaxHighlighter.tokenize(code, language) }
    return remember(spans, styles) {
        val builder = AnnotatedString.Builder(code)
        spans.forEach { span ->
            val style = styles[span.kind] ?: return@forEach
            val start = span.start.coerceIn(0, code.length)
            val end = span.end.coerceIn(start, code.length)
            if (end > start) builder.addStyle(style, start, end)
        }
        builder.toAnnotatedString()
    }
}
