package app.gitpocket.core.git.transport

import app.gitpocket.core.git.GitException
import app.gitpocket.core.git.GitRepo
import app.gitpocket.core.git.ObjectWalk
import app.gitpocket.core.model.ObjectId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class PushResult(
    val ref: String,
    val oldOid: ObjectId?,
    val newOid: ObjectId,
    val upToDate: Boolean,
    val serverMessages: List<String>,
)

/**
 * push：把本地分支推到远端。
 *
 * 对象集 = 本地可达 − 远端可达。远端可达的部分按"远端广告的 tip 在本地遍历"算，
 * 因为克隆时已经把远端历史全拉下来了；如果本地缺远端对象（浅克隆），
 * 这里会少算，服务端会以 `missing necessary objects` 拒绝，属于可接受的失败方式。
 *
 * 只推单个分支，不做 force、不做 tag 推送——手机端误操作代价太高。
 */
class GitPusher(
    private val credentials: GitCredentials? = null,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 180_000,
) {

    fun push(
        url: String,
        repo: GitRepo,
        branch: String,
        onProgress: (SyncProgress) -> Unit = {},
    ): PushResult {
        onProgress(SyncProgress.Connecting)
        val client = GitHttpClient(credentials, connectTimeoutMs, readTimeoutMs)
        val advertisement = AdvertisementParser.parse(
            client.advertisement(url, GitHttpClient.SERVICE_RECEIVE_PACK),
        )
        if (advertisement.refs.isEmpty() && advertisement.capabilities.isEmpty()) {
            throw GitException.RemoteNotFound(url)
        }

        val refName = "refs/heads/$branch"
        val newOid = repo.refs.read(refName) ?: throw GitException.RefNotFound(refName)
        val oldOid = advertisement.refs[refName]

        if (oldOid == newOid) {
            onProgress(SyncProgress.Done)
            return PushResult(refName, oldOid, newOid, upToDate = true, serverMessages = emptyList())
        }
        if (oldOid != null && !ObjectWalk.isAncestor(repo.objects, oldOid, newOid)) {
            throw GitException.NonFastForward(refName)
        }

        val remoteReachable = ObjectWalk.reachable(repo.objects, advertisement.refs.values)
        val toSend = ObjectWalk.reachable(repo.objects, listOf(newOid), remoteReachable)
        val objects = toSend.map { repo.objects.read(it) }
        val pack = PackWriter.write(objects)

        val capabilities = buildList {
            add("report-status")
            if ("side-band-64k" in advertisement.capabilities) add("side-band-64k")
            add("agent=git/gitpocket-0.2")
        }
        val sideband = capabilities.contains("side-band-64k")

        val request = ByteArrayOutputStream(pack.size + 256)
        val oldHex = oldOid?.hex ?: ZERO_OID
        request.write(
            PktLine.encode("$oldHex ${newOid.hex} $refName\u0000${capabilities.joinToString(" ")}\n"),
        )
        request.write(PktLine.FLUSH)
        request.write(pack)

        val messages = client.postRpc(url, GitHttpClient.SERVICE_RECEIVE_PACK, request.toByteArray())
            .use { readReport(it, sideband) }

        onProgress(SyncProgress.Done)
        return PushResult(refName, oldOid, newOid, upToDate = false, serverMessages = messages)
    }

    /** 解析 report-status：`unpack ok` + 每个 ref 的 `ok` / `ng <reason>`。 */
    private fun readReport(input: InputStream, sideband: Boolean): List<String> {
        val raw = GitHttpClient.readAll(input)
        val payload = if (sideband) extractBand1(raw) else raw
        val reader = PktLineReader(ByteArrayInputStream(payload))
        val messages = mutableListOf<String>()

        while (!reader.eof) {
            val text = reader.nextText() ?: continue
            if (text.isEmpty()) continue
            messages += text
            when {
                text.startsWith("unpack ") && text != "unpack ok" ->
                    throw GitException.PushRejected(text.removePrefix("unpack "))

                text.startsWith("ng ") -> {
                    val rest = text.removePrefix("ng ").trim()
                    throw GitException.PushRejected(rest)
                }
            }
        }
        return messages
    }

    /** side-band 响应里，band 1 承载 report-status 正文。 */
    private fun extractBand1(raw: ByteArray): ByteArray {
        val reader = PktLineReader(ByteArrayInputStream(raw))
        val out = ByteArrayOutputStream(raw.size)
        while (!reader.eof) {
            val packet = reader.next() ?: continue
            if (packet.isEmpty()) continue
            if (packet[0].toInt() == 1) out.write(packet, 1, packet.size - 1)
        }
        return out.toByteArray()
    }

    private companion object {
        const val ZERO_OID = "0000000000000000000000000000000000000000"
    }
}
