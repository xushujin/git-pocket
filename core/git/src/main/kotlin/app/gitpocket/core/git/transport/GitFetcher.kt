package app.gitpocket.core.git.transport

import app.gitpocket.core.git.GitException
import app.gitpocket.core.git.GitRepo
import app.gitpocket.core.model.ObjectId
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/** 同步过程事件：UI 直接展示这些阶段。 */
sealed interface SyncProgress {
    data object Connecting : SyncProgress
    data class Receiving(val bytes: Long) : SyncProgress
    data class Unpacking(val objects: Int) : SyncProgress
    data object Done : SyncProgress
}

data class FetchResult(
    /** 更新后的远端跟踪引用（refs/remotes/<remote>/<branch>）。 */
    val updatedRefs: Map<String, ObjectId>,
    val newObjects: Int,
    val remoteHeadBranch: String?,
    val remoteHeadOid: ObjectId?,
)

/**
 * fetch：把远端新增的对象拉下来，并更新远端跟踪引用。
 *
 * 不做 checkout、不动本地分支——那是 pull 的事。
 * 协议细节：
 * - 发送 want（远端有、本地没有的 tip）+ have（本地所有引用 tip）；
 * - 服务端据此只回缺失对象；
 * - 请求 side-band-64k 以便边收边报进度。
 */
class GitFetcher(
    private val credentials: GitCredentials? = null,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 180_000,
) {

    fun fetch(
        url: String,
        repo: GitRepo,
        remote: String = "origin",
        onProgress: (SyncProgress) -> Unit = {},
    ): FetchResult {
        onProgress(SyncProgress.Connecting)
        val client = GitHttpClient(credentials, connectTimeoutMs, readTimeoutMs)
        val advertisement = AdvertisementParser.parse(client.advertisement(url))
        if (advertisement.refs.isEmpty()) throw GitException.RemoteNotFound(url)

        val localTips = repo.refs.listRefs("refs/").values.toSet()
        val wants = advertisement.refs
            .filterKeys { it.startsWith("refs/") }
            .values
            .distinct()
            .filterNot { repo.objects.exists(it) }

        var newObjects = 0
        if (wants.isNotEmpty()) {
            val capabilities = buildList {
                if ("side-band-64k" in advertisement.capabilities) {
                    add("side-band-64k")
                } else if ("side-band" in advertisement.capabilities) {
                    add("side-band")
                }
                if ("ofs-delta" in advertisement.capabilities) add("ofs-delta")
                // 无状态的 smart HTTP 必须显式协商，否则服务端不会基于 have 计算增量
                if ("multi_ack_detailed" in advertisement.capabilities) add("multi_ack_detailed")
                if ("thin-pack" in advertisement.capabilities) add("thin-pack")
                // 无状态 HTTP 下没有 no-done 时服务端会等第二轮请求，只回 ACK 不回 pack
                if ("no-done" in advertisement.capabilities) add("no-done")
                if ("no-progress" in advertisement.capabilities) add("no-progress")
                add("agent=git/gitpocket-0.2")
            }

            val request = ByteArrayOutputStream()
            wants.forEachIndexed { index, oid ->
                val line = if (index == 0) {
                    "want ${oid.hex} ${capabilities.joinToString(" ")}\n"
                } else {
                    "want ${oid.hex}\n"
                }
                request.write(PktLine.encode(line))
            }
            // 顺序必须严格是：want… → flush → have… → done → flush
            // flush 放错位置（例如放在 have 之后）服务端会直接返回空响应
            request.write(PktLine.FLUSH)
            localTips.forEach { request.write(PktLine.encode("have ${it.hex}\n")) }
            request.write(PktLine.encode("done\n"))
            request.write(PktLine.FLUSH)

            val sideband = capabilities.any { it.startsWith("side-band") }
            val pack = client.uploadPack(url, request.toByteArray()).use { input ->
                PackReceiver.read(input, sideband) { onProgress(SyncProgress.Receiving(it)) }
            }
            if (pack.isNotEmpty()) {
                onProgress(SyncProgress.Unpacking(0))
                newObjects = PackIngest.ingest(pack, repo.objects) { onProgress(SyncProgress.Unpacking(it)) }
            }
        }

        // 更新远端跟踪引用；tag 也一并落到本地（与 clone 行为一致）
        val updated = LinkedHashMap<String, ObjectId>()
        advertisement.branches().forEach { (name, oid) ->
            if (!repo.objects.exists(oid)) return@forEach
            val tracking = "refs/remotes/$remote/" + name.removePrefix("refs/heads/")
            repo.refs.update(tracking, oid)
            updated[tracking] = oid
        }
        advertisement.tags().forEach { (name, oid) ->
            if (repo.objects.exists(oid)) repo.refs.update(name, oid)
        }

        val headBranch = advertisement.headTarget?.removePrefix("refs/heads/")
        val headOid = headBranch?.let { advertisement.refs["refs/heads/$it"] }
        writeFetchHead(repo, url, advertisement.branches(), headBranch)

        onProgress(SyncProgress.Done)
        return FetchResult(
            updatedRefs = updated,
            newObjects = newObjects,
            remoteHeadBranch = headBranch,
            remoteHeadOid = headOid,
        )
    }

    private fun writeFetchHead(
        repo: GitRepo,
        url: String,
        branches: Map<String, ObjectId>,
        headBranch: String?,
    ) {
        if (branches.isEmpty()) return
        val lines = branches.entries.joinToString("") { (name, oid) ->
            val short = name.removePrefix("refs/heads/")
            val mark = if (short == headBranch) "not-for-merge" else ""
            "${oid.hex}\t\tbranch '$short' of $url\n"
        }
        runCatching { File(repo.gitDir, "FETCH_HEAD").writeText(lines, StandardCharsets.UTF_8) }
    }
}
