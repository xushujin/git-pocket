package app.gitpocket.core.git

/** Git 引擎抛出的类型化异常：UI 层据此映射成"用户能懂的一句话 + 一个动作"。 */
sealed class GitException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class NotARepository(val path: String) : GitException("不是 Git 仓库：$path")

    class ObjectCorrupted(val oid: String, val detail: String) :
        GitException("对象损坏 ${oid.ifEmpty { "(未知)" }}：$detail")

    class IndexCorrupted(val detail: String) : GitException("索引损坏：$detail")

    class UnsupportedFeature(val feature: String) : GitException("暂不支持：$feature")

    class CheckoutConflict(val path: String) : GitException("文件存在未提交修改：$path")

    class RefNotFound(val name: String) : GitException("引用不存在：$name")

    class NothingToCommit : GitException("没有可提交的变更")

    class Io(val path: String, cause: Throwable) : GitException("读写失败：$path", cause)
}
