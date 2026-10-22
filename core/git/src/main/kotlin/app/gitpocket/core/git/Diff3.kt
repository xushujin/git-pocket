package app.gitpocket.core.git

/** 三方合并的单文件结果：合并后的文本与冲突块数量。 */
data class MergeText(val text: String, val conflicts: Int)

/**
 * 行级三方合并（diff3 思路）。
 *
 * 做法：
 * 1. 分别算出 base→ours、base→theirs 的改动块（改动块用 base 的区间表示）；
 * 2. 把两侧"重叠"的改动块归并成同一区域，区域之间原样复制 base；
 * 3. 每个区域里：只有一侧改了就用那一侧；两侧改得一样就取其一；否则写冲突标记。
 *
 * 取舍（写进已知限制）：
 * - 不做 rename detection，重命名会被当成"删除 + 新增"；
 * - 只按行合并，不按字符合并（手机上没有做逐字符 merge 的价值）；
 * - 两侧改动在 base 里紧挨着（不重叠）时按两个区域分别处理，与 git 一致；
 *   插入点正好落在对方改动边界时视为重叠，宁可多报一个冲突也不静默丢内容。
 */
object Diff3 {

    private data class Chunk(val baseStart: Int, val baseEnd: Int, val lines: List<String>) {
        val isInsertion: Boolean get() = baseEnd == baseStart
    }

    fun merge(
        baseText: String,
        oursText: String,
        theirsText: String,
        oursLabel: String = "HEAD",
        theirsLabel: String = "MERGE_HEAD",
    ): MergeText {
        if (oursText == theirsText) return MergeText(oursText, 0)
        if (baseText == oursText) return MergeText(theirsText, 0)
        if (baseText == theirsText) return MergeText(oursText, 0)

        val base = LineDiff.splitLines(baseText)
        val oursChunks = chunks(base, LineDiff.splitLines(oursText))
        val theirsChunks = chunks(base, LineDiff.splitLines(theirsText))

        val out = ArrayList<String>(base.size + 16)
        var conflicts = 0
        var pos = 0
        var oi = 0
        var ti = 0

        while (oi < oursChunks.size || ti < theirsChunks.size) {
            val nextOurs = oursChunks.getOrNull(oi)?.baseStart ?: Int.MAX_VALUE
            val nextTheirs = theirsChunks.getOrNull(ti)?.baseStart ?: Int.MAX_VALUE
            val start = minOf(nextOurs, nextTheirs)

            while (pos < start) {
                out.add(base[pos])
                pos++
            }

            // 归并区域：先把起点最早的那块吃进来，再吸收所有与它重叠的块。
            var end = if (nextOurs <= nextTheirs) oursChunks[oi].baseEnd else theirsChunks[ti].baseEnd
            val oFrom = oi
            val tFrom = ti
            if (nextOurs <= nextTheirs) oi++ else ti++

            var grew = true
            while (grew) {
                grew = false
                if (oi < oursChunks.size && overlaps(oursChunks[oi], start, end)) {
                    end = maxOf(end, oursChunks[oi].baseEnd)
                    oi++
                    grew = true
                }
                if (ti < theirsChunks.size && overlaps(theirsChunks[ti], start, end)) {
                    end = maxOf(end, theirsChunks[ti].baseEnd)
                    ti++
                    grew = true
                }
            }

            val oursLines = applyRegion(base, oursChunks.subList(oFrom, oi), start, end)
            val theirsLines = applyRegion(base, theirsChunks.subList(tFrom, ti), start, end)
            val baseSlice = base.subList(start, end)

            when {
                oursLines == baseSlice -> out.addAll(theirsLines)
                theirsLines == baseSlice -> out.addAll(oursLines)
                oursLines == theirsLines -> out.addAll(oursLines)
                else -> {
                    conflicts++
                    out.add("<<<<<<< $oursLabel")
                    out.addAll(oursLines)
                    out.add("=======")
                    out.addAll(theirsLines)
                    out.add(">>>>>>> $theirsLabel")
                }
            }
            pos = end
        }

        while (pos < base.size) {
            out.add(base[pos])
            pos++
        }
        return MergeText(out.joinToString("\n"), conflicts)
    }

    /** 区域 [start, end) 内某一侧的最终内容：把该区域内的改动块套到 base 切片上。 */
    private fun applyRegion(
        base: List<String>,
        regionChunks: List<Chunk>,
        start: Int,
        end: Int,
    ): List<String> {
        val out = ArrayList<String>(end - start + 4)
        var pos = start
        regionChunks.forEach { c ->
            while (pos < c.baseStart) {
                out.add(base[pos])
                pos++
            }
            out.addAll(c.lines)
            pos = maxOf(pos, c.baseEnd)
        }
        while (pos < end) {
            out.add(base[pos])
            pos++
        }
        return out
    }

    private fun overlaps(chunk: Chunk, start: Int, end: Int): Boolean {
        // 非插入块正好从区域末尾开始 → 属于下一个区域（与 git 一致）
        if (chunk.baseStart == end && !chunk.isInsertion) return false
        return chunk.baseStart <= end
    }

    /** 把 base→side 的 diff 归并成改动块（连续的增删合并为一块）。 */
    private fun chunks(base: List<String>, side: List<String>): List<Chunk> {
        val ops = LineDiff.computeOps(base, side)
            // 编辑距离超阈值时放弃精细比对：整块替换，宁可冲突也不丢内容
            ?: return listOf(Chunk(0, base.size, side))

        val out = ArrayList<Chunk>()
        var start = -1
        var end = -1
        var cursor = 0
        val lines = ArrayList<String>()

        fun flush() {
            if (start >= 0) {
                out.add(Chunk(start, end, lines.toList()))
                lines.clear()
                start = -1
                end = -1
            }
        }

        ops.forEach { op ->
            when (op) {
                is LineDiff.Op.Equal -> {
                    flush()
                    cursor = op.oldIndex + 1
                }

                is LineDiff.Op.Delete -> {
                    if (start < 0) start = op.oldIndex
                    end = op.oldIndex + 1
                    cursor = op.oldIndex + 1
                }

                is LineDiff.Op.Insert -> {
                    if (start < 0) start = cursor
                    end = maxOf(end, cursor)
                    lines.add(side[op.newIndex])
                }
            }
        }
        flush()
        return out
    }
}
