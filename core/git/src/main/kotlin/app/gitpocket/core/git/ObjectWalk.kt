package app.gitpocket.core.git

import app.gitpocket.core.model.ObjectId

/**
 * 对象图遍历：算出"从这些 tip 出发能到达的全部对象"。
 *
 * push 时用它做差集：本地可达 − 远端可达 = 需要上传的对象。
 * 只做广度优先、不做拓扑排序——pack 里的对象顺序对服务端没有要求
 * （非 delta pack 之间不互相引用）。
 */
object ObjectWalk {

    fun reachable(
        db: ObjectDatabase,
        tips: Collection<ObjectId>,
        exclude: Set<ObjectId> = emptySet(),
    ): LinkedHashSet<ObjectId> {
        val seen = LinkedHashSet<ObjectId>()
        val queue = ArrayDeque<ObjectId>()
        tips.forEach { if (db.exists(it)) queue.addLast(it) }

        while (queue.isNotEmpty()) {
            val oid = queue.removeFirst()
            if (oid in exclude) continue
            if (!seen.add(oid)) continue

            val obj = runCatching { db.read(oid) }.getOrNull() ?: continue
            when (obj) {
                is GitCommit -> {
                    queue.addLast(obj.tree)
                    obj.parents.forEach { queue.addLast(it) }
                }

                is GitTree -> obj.entries.forEach { entry ->
                    // 子模块是 gitlink，本地没有对应对象
                    if (entry.mode != GitTreeEntry.MODE_SUBMODULE) queue.addLast(entry.oid)
                }

                is GitTag -> queue.addLast(obj.objectId)
                else -> Unit
            }
        }
        return seen
    }

    /** 判断 [ancestor] 是否是 [descendant] 的祖先（用于 push 的非快进检测）。 */
    fun isAncestor(db: ObjectDatabase, ancestor: ObjectId, descendant: ObjectId): Boolean {
        if (ancestor == descendant) return true
        val seen = HashSet<ObjectId>()
        val queue = ArrayDeque<ObjectId>()
        queue.addLast(descendant)

        while (queue.isNotEmpty()) {
            val oid = queue.removeFirst()
            if (!seen.add(oid)) continue
            if (oid == ancestor) return true
            val commit = runCatching { db.read(oid) }.getOrNull() as? GitCommit ?: continue
            commit.parents.forEach { queue.addLast(it) }
        }
        return false
    }

    /**
     * 求两个提交的合并基点（best common ancestor）。
     *
     * 做法：先给 a 的全部祖先打上深度，再从 b 逐层向外找，取"深度和最小"的公共祖先。
     * 不追求 git 那套 generation number / 位图优化——手机上的历史规模用不到，
     * 但允许返回"非最小公共祖先"（与 git 的 criss-cross 处理可能有差异，已记入限制）。
     */
    fun mergeBase(db: ObjectDatabase, a: ObjectId, b: ObjectId): ObjectId? {
        if (a == b) return a

        val depthA = HashMap<ObjectId, Int>()
        val queueA = ArrayDeque<Pair<ObjectId, Int>>()
        queueA.addLast(a to 0)
        while (queueA.isNotEmpty()) {
            val (oid, depth) = queueA.removeFirst()
            if (depthA.containsKey(oid)) continue
            depthA[oid] = depth
            val commit = runCatching { db.read(oid) }.getOrNull() as? GitCommit ?: continue
            commit.parents.forEach { queueA.addLast(it to depth + 1) }
        }

        var best: ObjectId? = null
        var bestCost = Int.MAX_VALUE
        val seen = HashSet<ObjectId>()
        val queueB = ArrayDeque<Pair<ObjectId, Int>>()
        queueB.addLast(b to 0)

        while (queueB.isNotEmpty()) {
            val (oid, depth) = queueB.removeFirst()
            if (depth >= bestCost) break
            if (!seen.add(oid)) continue

            val depthAValue = depthA[oid]
            if (depthAValue != null) {
                val cost = depth + depthAValue
                if (cost < bestCost) {
                    bestCost = cost
                    best = oid
                }
                // 同一个提交的祖先必然更远，不必再往下走
                continue
            }
            val commit = runCatching { db.read(oid) }.getOrNull() as? GitCommit ?: continue
            commit.parents.forEach { queueB.addLast(it to depth + 1) }
        }
        return best
    }
}
