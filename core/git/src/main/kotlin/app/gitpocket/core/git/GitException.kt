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

    class AuthFailed(val host: String) : GitException("认证失败：$host（请检查 token 与权限）")

    class RemoteNotFound(val url: String) : GitException("仓库不存在或无权访问：$url")

    class Network(val retryable: Boolean, cause: Throwable) :
        GitException("网络错误：${cause.message ?: "未知原因"}", cause)

    class Io(val path: String, cause: Throwable) : GitException("读写失败：$path", cause)

    /** 远端拒绝（非快进、受保护分支、权限不足等）。 */
    class PushRejected(val detail: String) : GitException("推送被拒绝：$detail")

    /** 本地分支落后于远端：必须先拉取再推送。 */
    class NonFastForward(val ref: String) : GitException("远端有本地没有的提交，请先拉取：$ref")

    /** 工作区有未提交改动，切换分支 / 合并前必须先处理。 */
    class DirtyWorkTree(val count: Int) :
        GitException("还有 $count 个文件未提交，请先提交或撤销后再操作")

    /** 有未完成的合并（存在冲突待解决）。 */
    class MergeInProgress : GitException("还有冲突没解决，请先解决或放弃本次合并")

    /** 分支已存在。 */
    class BranchExists(val name: String) : GitException("分支已存在：$name")

    /** 分支名不合法。 */
    class InvalidBranchName(val name: String) : GitException("分支名不合法：$name")

    /** 合并会覆盖未跟踪文件：与 git 一致，直接拒绝而不是静默覆盖。 */
    class UntrackedOverwrite(val paths: List<String>) :
        GitException("这些未跟踪文件会被覆盖，请先移走：${paths.joinToString("、")}")
}
