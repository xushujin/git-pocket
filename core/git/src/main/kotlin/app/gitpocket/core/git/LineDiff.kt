package app.gitpocket.core.git

import app.gitpocket.core.model.DiffHunk
import app.gitpocket.core.model.DiffLine
import app.gitpocket.core.model.DiffLineKind
import app.gitpocket.core.model.FileDiff

/**
 * 行级 diff（Myers O(ND)）。
 *
 * 取舍：
 * - 大文件（>5000 行）不逐行比对，直接返回统计并标记 truncated；
 * - 编辑距离超过阈值时放弃精细结果，退化为"整块替换"（正确但粗糙），避免卡死。
 */
object LineDiff {

    private const val CONTEXT = 3
    private const val MAX_LINES = 5000
    private const val MAX_EDIT_DISTANCE = 2000

    internal sealed interface Op {
        data class Equal(val oldIndex: Int, val newIndex: Int) : Op
        data class Delete(val oldIndex: Int) : Op
        data class Insert(val newIndex: Int) : Op
    }

    private data class Row(
        val kind: DiffLineKind,
        val oldLine: Int?,
        val newLine: Int?,
        val text: String,
    )

    fun diff(oldText: String, newText: String, path: String = ""): FileDiff {
        if (oldText == newText) {
            return FileDiff(path, false, emptyList(), 0, 0)
        }
        if (oldText.indexOf('\u0000') >= 0 || newText.indexOf('\u0000') >= 0) {
            return FileDiff(path, true, emptyList(), 0, 0)
        }

        val oldLines = splitLines(oldText)
        val newLines = splitLines(newText)

        if (oldLines.size > MAX_LINES || newLines.size > MAX_LINES) {
            return FileDiff(
                path = path,
                isBinary = false,
                hunks = emptyList(),
                additions = newLines.size,
                deletions = oldLines.size,
                truncated = true,
            )
        }

        val ops = computeOps(oldLines, newLines)
            ?: return FileDiff(
                path = path,
                isBinary = false,
                hunks = emptyList(),
                additions = newLines.size,
                deletions = oldLines.size,
                truncated = true,
            )

        val (hunks, counts) = buildHunks(ops, oldLines, newLines)
        return FileDiff(
            path = path,
            isBinary = false,
            hunks = hunks,
            additions = counts.first,
            deletions = counts.second,
        )
    }

    /**
     * 按行切分：空文本是 0 行（与 git 一致）；其余保留末尾空元素，
     * 以便"文件末尾换行"的差异仍然可见。
     */
    internal fun splitLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split("\n")

    /** 返回 null 表示超过编辑距离阈值，放弃精细比对。 */
    internal fun computeOps(a: List<String>, b: List<String>): List<Op>? {
        val n = a.size
        val m = b.size
        val max = n + m
        if (max == 0) return emptyList()

        val offset = max
        val v = IntArray(2 * max + 2)
        val limit = minOf(max, MAX_EDIT_DISTANCE)
        val trace = ArrayList<IntArray>(limit + 1)
        var finalD = -1

        for (d in 0..limit) {
            trace.add(v.copyOf())
            var k = -d
            while (k <= d) {
                val idx = k + offset
                var x = if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                    v[idx + 1]
                } else {
                    v[idx - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[idx] = x
                if (x >= n && y >= m) {
                    finalD = d
                    break
                }
                k += 2
            }
            if (finalD >= 0) break
        }
        if (finalD < 0) return null

        val ops = ArrayDeque<Op>()
        var x = n
        var y = m
        for (d in finalD downTo 0) {
            if (d == 0) {
                while (x > 0 && y > 0) {
                    ops.addFirst(Op.Equal(x - 1, y - 1))
                    x--
                    y--
                }
                continue
            }
            val vPrev = trace[d]
            val k = x - y
            val idx = k + offset
            val prevK = if (k == -d || (k != d && vPrev[idx - 1] < vPrev[idx + 1])) k + 1 else k - 1
            val prevX = vPrev[prevK + offset]
            val prevY = prevX - prevK

            while (x > prevX && y > prevY) {
                ops.addFirst(Op.Equal(x - 1, y - 1))
                x--
                y--
            }
            if (x == prevX) {
                ops.addFirst(Op.Insert(y - 1))
                y--
            } else {
                ops.addFirst(Op.Delete(x - 1))
                x--
            }
        }
        return ops.toList()
    }

    private fun buildHunks(
        ops: List<Op>,
        a: List<String>,
        b: List<String>,
    ): Pair<List<DiffHunk>, Pair<Int, Int>> {
        val rows = ArrayList<Row>(ops.size)
        var additions = 0
        var deletions = 0

        ops.forEach { op ->
            when (op) {
                is Op.Equal -> rows.add(Row(DiffLineKind.CONTEXT, op.oldIndex + 1, op.newIndex + 1, a[op.oldIndex]))
                is Op.Delete -> {
                    rows.add(Row(DiffLineKind.REMOVED, op.oldIndex + 1, null, a[op.oldIndex]))
                    deletions++
                }
                is Op.Insert -> {
                    rows.add(Row(DiffLineKind.ADDED, null, op.newIndex + 1, b[op.newIndex]))
                    additions++
                }
            }
        }

        val changed = rows.indices.filter { rows[it].kind != DiffLineKind.CONTEXT }
        if (changed.isEmpty()) return emptyList<DiffHunk>() to (0 to 0)

        val ranges = mutableListOf<IntRange>()
        var rangeStart = maxOf(0, changed.first() - CONTEXT)
        var rangeEnd = minOf(rows.lastIndex, changed.first() + CONTEXT)
        changed.drop(1).forEach { index ->
            val s = maxOf(0, index - CONTEXT)
            val e = minOf(rows.lastIndex, index + CONTEXT)
            if (s <= rangeEnd + 1) {
                rangeEnd = maxOf(rangeEnd, e)
            } else {
                ranges.add(rangeStart..rangeEnd)
                rangeStart = s
                rangeEnd = e
            }
        }
        ranges.add(rangeStart..rangeEnd)

        val hunks = ranges.map { range ->
            val slice = rows.subList(range.first, range.last + 1)
            val oldStart = slice.firstOrNull { it.oldLine != null }?.oldLine ?: 1
            val newStart = slice.firstOrNull { it.newLine != null }?.newLine ?: 1
            DiffHunk(
                oldStart = oldStart,
                oldCount = slice.count { it.oldLine != null },
                newStart = newStart,
                newCount = slice.count { it.newLine != null },
                lines = slice.map { DiffLine(it.kind, it.oldLine, it.newLine, it.text) },
            )
        }
        return hunks to (additions to deletions)
    }
}
