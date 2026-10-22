package app.gitpocket.core.common.text

/**
 * 文本撤销 / 重做栈（快照式）。
 *
 * 合并规则（决定"点一次回退退多少"）：
 * - 连续在末尾追加 1~3 个字符，且与上一次输入间隔小于 [burstMs] → 视为同一次输入，替换当前快照而不是新增；
 * - 换行、删除、粘贴、光标跳转等一律另起一步。
 *
 * 这样"写 aaa，停一下，再写 bbb，点回退"只会去掉 bbb；
 * 而一口气敲完的单词不会被拆成好几步。回退之后继续输入会丢弃重做分支（与常见编辑器一致）。
 *
 * 纯 Kotlin，时钟可注入，便于单测。
 */
class UndoStack(
    private val maxSize: Int = 100,
    private val burstMs: Long = 1_500L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 内容快照，最后一个元素不一定等于当前内容（用户可能刚打了字还没 record）。 */
    private val snapshots = ArrayList<String>()

    /** 当前快照下标；-1 表示还没有任何快照。 */
    private var index = -1

    private var lastEditAt = 0L
    private var lastWasTyping = false

    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index >= 0 && index < snapshots.lastIndex

    /** 记录一次内容变化；[previous] 是变化前的内容，[next] 是变化后的内容。 */
    fun record(previous: String, next: String) {
        if (previous == next) return

        val now = clock()
        val appended = if (next.startsWith(previous)) next.substring(previous.length) else ""
        val typing = appended.length in 1..3 && '\n' !in appended
        val sameBurst = typing && lastWasTyping && now - lastEditAt <= burstMs

        if (index < 0) {
            snapshots.add(previous)
            index = 0
        }

        if (sameBurst) {
            // 同一次输入：把当前快照替换成最新内容，不新增一步
            snapshots[index] = next
        } else {
            // 新的一步：丢弃重做分支，再压入
            while (snapshots.size > index + 1) snapshots.removeAt(snapshots.lastIndex)
            snapshots.add(next)
            index++
            // maxSize 是"可回退步数"，快照数比它多一个（当前状态本身不算一步）
            while (snapshots.size > maxSize + 1) {
                snapshots.removeAt(0)
                index--
            }
        }
        lastEditAt = now
        lastWasTyping = typing
    }

    /** 回退一步，返回回退后的内容；栈空返回 null。 */
    fun undo(): String? {
        if (!canUndo) return null
        index--
        lastWasTyping = false
        return snapshots[index]
    }

    /** 重做一步，返回重做后的内容；没有可重做的返回 null。 */
    fun redo(): String? {
        if (!canRedo) return null
        index++
        lastWasTyping = false
        return snapshots[index]
    }

    fun clear() {
        snapshots.clear()
        index = -1
        lastWasTyping = false
        lastEditAt = 0L
    }
}
