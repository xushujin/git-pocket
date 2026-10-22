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
}
