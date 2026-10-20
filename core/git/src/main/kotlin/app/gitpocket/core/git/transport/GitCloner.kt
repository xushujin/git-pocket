package app.gitpocket.core.git.transport

import app.gitpocket.core.git.GitCommit
import app.gitpocket.core.git.GitException
import app.gitpocket.core.git.GitRepo
import app.gitpocket.core.model.ObjectId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

sealed interface CloneProgress {
    data object Connecting : CloneProgress
    data class Receiving(val bytes: Long) : CloneProgress
    data class Unpacking(val objects: Int) : CloneProgress
    data class CheckingOut(val files: Int) : CloneProgress
}

data class CloneResult(
    val workTree: File,
    val defaultBranch: String,
    val refCount: Int,
    val objectCount: Int,
)

/**
 * HTTPS 克隆（smart HTTP protocol v0）。
 *
 * 限制（明确标注）：
 * - 只支持 HTTPS，不支持 SSH；
 * - 不请求 thin-pack（服务端会发全量对象），因此不会出现缺失基对象；
 * - 收到的是单分支集合（所有 heads + tags 的 tip），不做部分克隆。
 */
class GitCloner(
    private val credentials: GitCredentials? = null,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 180_000,
) {

    fun clone(
        url: String,
        targetDir: File,
        onProgress: (CloneProgress) -> Unit = {},
    ): CloneResult {
        if (!url.startsWith("https://")) {
            throw GitException.UnsupportedFeature("仅支持 https:// 仓库地址")
        }
        onProgress(CloneProgress.Connecting)

        val client = GitHttpClient(credentials, connectTimeoutMs, readTimeoutMs)
        val advertisement = AdvertisementParser.parse(client.advertisement(url))
        if (advertisement.refs.isEmpty()) throw GitException.RemoteNotFound(url)

        val wants = LinkedHashSet<ObjectId>()
        advertisement.branches().values.forEach(wants::add)
        advertisement.tags().values.forEach(wants::add)
        if (wants.isEmpty()) throw GitException.RemoteNotFound(url)

        val capabilities = buildList {
            if ("side-band-64k" in advertisement.capabilities) {
                add("side-band-64k")
            } else if ("side-band" in advertisement.capabilities) {
                add("side-band")
            }
            if ("ofs-delta" in advertisement.capabilities) add("ofs-delta")
            add("agent=git/gitpocket-0.1")
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
        request.write(PktLine.FLUSH)
        request.write(PktLine.encode("done\n"))

        val sideband = capabilities.any { it.startsWith("side-band") }
        val pack = client.uploadPack(url, request.toByteArray()).use { input ->
            if (sideband) readSideband(input, onProgress) else readRaw(input)
        }
        if (pack.isEmpty()) throw GitException.Network(retryable = true, cause = IOException("服务端返回空 pack"))

        targetDir.mkdirs()
        val repo = GitRepo.init(targetDir)
        try {
            onProgress(CloneProgress.Unpacking(0))
            val objectCount = PackIngest.ingest(pack, repo.objects) { onProgress(CloneProgress.Unpacking(it)) }

            // 只写 refs/ 下的引用；HEAD 单独按 symref 处理，避免把 HEAD 写成游离 oid
            advertisement.refs
                .filterKeys { it.startsWith("refs/") }
                .forEach { (name, oid) ->
                    if (repo.objects.exists(oid)) repo.refs.update(name, oid)
                }

            val defaultBranch = advertisement.headTarget?.removePrefix("refs/heads/")
                ?: advertisement.branches().keys.firstOrNull()?.removePrefix("refs/heads/")
                ?: "main"
            repo.refs.updateHead("refs/heads/$defaultBranch")

            val headOid = advertisement.refs["refs/heads/$defaultBranch"]
                ?: advertisement.headOid
                ?: wants.first()
            val commit = repo.objects.read(headOid) as? GitCommit
                ?: throw GitException.ObjectCorrupted(headOid.hex, "HEAD 不是 commit 对象")

            onProgress(CloneProgress.CheckingOut(0))
            repo.checkoutTree(commit.tree) { onProgress(CloneProgress.CheckingOut(it)) }
            writeConfig(targetDir, url)

            return CloneResult(
                workTree = targetDir,
                defaultBranch = defaultBranch,
                refCount = advertisement.refs.size,
                objectCount = objectCount,
            )
        } finally {
            repo.close()
        }
    }

    private fun readRaw(input: InputStream): ByteArray {
        val reader = PktLineReader(input)
        while (!reader.eof) {
            val packet = reader.next() ?: break
            val text = String(packet, StandardCharsets.US_ASCII).trim()
            if (text.startsWith("NAK") || text.startsWith("ACK")) break
        }
        return GitHttpClient.readAll(input)
    }

    private fun readSideband(input: InputStream, onProgress: (CloneProgress) -> Unit): ByteArray {
        val reader = PktLineReader(input)
        val out = ByteArrayOutputStream()
        var received = 0L

        while (!reader.eof) {
            val packet = reader.next() ?: continue
            if (packet.isEmpty()) continue
            when (packet[0].toInt()) {
                1 -> {
                    out.write(packet, 1, packet.size - 1)
                    received += packet.size - 1
                    onProgress(CloneProgress.Receiving(received))
                }

                2 -> Unit // 进度文本，忽略
                3 -> {
                    val message = String(packet, 1, packet.size - 1, StandardCharsets.UTF_8)
                    throw GitException.Network(retryable = false, cause = IOException("服务端错误：$message"))
                }

                else -> Unit // NAK / ACK 行
            }
        }
        return out.toByteArray()
    }

    private fun writeConfig(targetDir: File, url: String) {
        val config = """
            [core]
            	repositoryformatversion = 0
            	filemode = true
            	bare = false
            	logallrefupdates = true
            [remote "origin"]
            	url = $url
            	fetch = +refs/heads/*:refs/remotes/origin/*
        """.trimIndent() + "\n"
        File(targetDir, ".git/config").writeText(config, StandardCharsets.US_ASCII)
    }
}
