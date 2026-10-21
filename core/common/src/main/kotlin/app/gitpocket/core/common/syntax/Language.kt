package app.gitpocket.core.common.syntax

/** 支持高亮的语言。按扩展名判定，未知类型退回纯文本。 */
enum class Language {
    KOTLIN,
    JAVA,
    JAVASCRIPT,
    TYPESCRIPT,
    JSON,
    XML,
    PYTHON,
    SHELL,
    MARKDOWN,
    PLAIN,
    ;

    companion object {
        fun fromPath(path: String): Language = when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts", "gradle" -> KOTLIN
            "java" -> JAVA
            "js", "jsx", "mjs", "cjs" -> JAVASCRIPT
            "ts", "tsx" -> TYPESCRIPT
            "json", "json5" -> JSON
            "xml", "html", "htm", "svg", "xhtml" -> XML
            "py" -> PYTHON
            "sh", "bash", "zsh", "properties", "toml", "yaml", "yml", "conf" -> SHELL
            "md", "markdown", "mdown" -> MARKDOWN
            else -> PLAIN
        }

        /** 代码围栏里的语言名（```kotlin）。 */
        fun fromFence(name: String?): Language = when (name?.trim()?.lowercase()) {
            "kotlin", "kt", "kts" -> KOTLIN
            "java" -> JAVA
            "js", "javascript", "jsx", "node" -> JAVASCRIPT
            "ts", "typescript", "tsx" -> TYPESCRIPT
            "json" -> JSON
            "xml", "html", "svg" -> XML
            "python", "py" -> PYTHON
            "sh", "bash", "shell", "zsh", "console" -> SHELL
            "md", "markdown" -> MARKDOWN
            else -> PLAIN
        }
    }
}

enum class TokenKind {
    KEYWORD,
    TYPE,
    STRING,
    COMMENT,
    NUMBER,
    FUNCTION,
    ANNOTATION,
    TAG,
    ATTRIBUTE,
    HEADING,
    LINK,
    EMPHASIS,
    CODE,
    MARKER,
}

/** [start, end) 字符区间。多个 span 不重叠，按 start 升序。 */
data class TokenSpan(val start: Int, val end: Int, val kind: TokenKind) {
    init {
        require(start in 0..end) { "非法 span: $start..$end" }
    }
}
