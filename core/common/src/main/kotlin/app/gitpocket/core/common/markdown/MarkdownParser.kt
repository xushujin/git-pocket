package app.gitpocket.core.common.markdown

/** Markdown 块级结构。行内样式在渲染层处理（保持解析器纯数据）。 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class CodeBlock(val language: String?, val code: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class OrderedList(val start: Int, val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(
        val header: List<String>,
        val aligns: List<ColumnAlign>,
        val rows: List<List<String>>,
    ) : MdBlock

    data object Divider : MdBlock
}

enum class ColumnAlign { LEFT, CENTER, RIGHT }

/**
 * 轻量 Markdown 解析（块级）。
 *
 * 支持：标题、段落、围栏代码块、无序/有序列表、引用、分割线。
 * 不支持：表格、脚注、HTML 内嵌、嵌套列表（嵌套会按同级处理）。
 */
object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val DIVIDER = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val BULLET = Regex("^[-*+]\\s+")
    private val ORDERED = Regex("^\\d+[.)]\\s+")
    private val ALIGN_CELL = Regex("^:?-{1,}:?$")

    fun parse(text: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = text.replace("\r\n", "\n").split("\n")
        val paragraph = mutableListOf<String>()
        var i = 0

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks.add(MdBlock.Paragraph(paragraph.joinToString("\n").trim()))
                paragraph.clear()
            }
        }

        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()

            when {
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    val language = trimmed.removePrefix("```").trim().ifBlank { null }
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        code.add(lines[i])
                        i++
                    }
                    if (i < lines.size) i++ // 跳过结束围栏
                    blocks.add(MdBlock.CodeBlock(language, code.joinToString("\n")))
                }

                trimmed.isEmpty() -> {
                    flushParagraph()
                    i++
                }

                HEADING.matches(trimmed) -> {
                    flushParagraph()
                    val match = HEADING.find(trimmed)!!
                    blocks.add(MdBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim()))
                    i++
                }

                parseTable(lines, i) != null -> {
                    flushParagraph()
                    val (table, next) = parseTable(lines, i)!!
                    blocks.add(table)
                    i = next
                }

                DIVIDER.matches(trimmed) -> {
                    flushParagraph()
                    blocks.add(MdBlock.Divider)
                    i++
                }

                trimmed.startsWith(">") -> {
                    flushParagraph()
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        quote.add(lines[i].trim().removePrefix(">").trimStart())
                        i++
                    }
                    blocks.add(MdBlock.Quote(quote.joinToString("\n")))
                }

                BULLET.containsMatchIn(trimmed) -> {
                    flushParagraph()
                    val items = mutableListOf<String>()
                    while (i < lines.size && BULLET.containsMatchIn(lines[i].trim())) {
                        items.add(lines[i].trim().replaceFirst(BULLET, ""))
                        i++
                    }
                    blocks.add(MdBlock.BulletList(items))
                }

                ORDERED.containsMatchIn(trimmed) -> {
                    flushParagraph()
                    val items = mutableListOf<String>()
                    var start = 1
                    var first = true
                    while (i < lines.size && ORDERED.containsMatchIn(lines[i].trim())) {
                        val line = lines[i].trim()
                        if (first) {
                            start = line.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                            first = false
                        }
                        items.add(line.replaceFirst(ORDERED, ""))
                        i++
                    }
                    blocks.add(MdBlock.OrderedList(start, items))
                }

                else -> {
                    paragraph.add(raw)
                    i++
                }
            }
        }
        flushParagraph()
        return blocks
    }

    /** 识别 `| a | b |` + `|---|---|` 形式的表格；不是表格返回 null。 */
    private fun parseTable(lines: List<String>, start: Int): Pair<MdBlock.Table, Int>? {
        if (start + 1 >= lines.size) return null
        val headerLine = lines[start]
        if (!headerLine.contains('|')) return null

        val separatorCells = splitRow(lines[start + 1])
        if (separatorCells.isEmpty() || !separatorCells.all { ALIGN_CELL.matches(it) }) return null

        val header = splitRow(headerLine)
        if (header.size != separatorCells.size) return null

        val aligns = separatorCells.map { cell ->
            when {
                cell.startsWith(":") && cell.endsWith(":") -> ColumnAlign.CENTER
                cell.endsWith(":") -> ColumnAlign.RIGHT
                else -> ColumnAlign.LEFT
            }
        }

        val rows = mutableListOf<List<String>>()
        var index = start + 2
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank() || !line.contains('|')) break
            rows.add(splitRow(line))
            index++
        }
        return MdBlock.Table(header, aligns, rows) to index
    }

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
}
