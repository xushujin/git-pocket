package app.gitpocket.core.git.transport

import app.gitpocket.core.git.GitException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.zip.GZIPInputStream

/** HTTPS 认证信息。password 位置放 PAT。 */
data class GitCredentials(
    val username: String = "x-access-token",
    val password: String,
) {
    fun basicHeader(): String {
        val raw = "$username:$password".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.getEncoder().encodeToString(raw)
    }
}

/**
 * smart HTTP 客户端（protocol v0）。
 * 只依赖 java.net，不引入第三方 HTTP 库。
 *
 * 注意：HttpURLConnection 一旦读取 responseCode 就视为已连接，
 * 之后不能再 setRequestProperty —— 所以配置与校验必须分开。
 */
internal class GitHttpClient(
    private val credentials: GitCredentials?,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 180_000,
) {

    fun advertisement(
        baseUrl: String,
        service: String = SERVICE_UPLOAD_PACK,
    ): ByteArray {
        val url = URI("${baseUrl.trimEnd('/')}/info/refs?service=$service").toURL()
        val connection = newConnection(url, "GET")
        try {
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Pragma", "no-cache")
            ensureSuccess(connection, url.toString())
            return decodeBody(connection).use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    fun uploadPack(baseUrl: String, body: ByteArray): InputStream =
        postRpc(baseUrl, SERVICE_UPLOAD_PACK, body)

    /** receive-pack 用于 push，协议形状与 upload-pack 一致，只是 service 名与 Content-Type 不同。 */
    fun postRpc(baseUrl: String, service: String, body: ByteArray): InputStream {
        val url = URI("${baseUrl.trimEnd('/')}/$service").toURL()
        val connection = newConnection(url, "POST")
        connection.setRequestProperty("Content-Type", "application/x-$service-request")
        connection.setRequestProperty("Accept", "application/x-$service-result")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        ensureSuccess(connection, url.toString())
        return connection.inputStream
    }

    private fun newConnection(url: java.net.URL, method: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", "git/2.50.1 (GitPocket)")
            setRequestProperty("Accept-Encoding", "gzip")
            credentials?.let { setRequestProperty("Authorization", it.basicHeader()) }
        }

    private fun ensureSuccess(connection: HttpURLConnection, url: String) {
        val code = connection.responseCode
        if (code in 200..299) return

        val detail = runCatching {
            connection.errorStream?.use { stream -> stream.readBytes().toString(Charsets.UTF_8).take(200) }
        }.getOrNull()
        connection.disconnect()
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        throw when (code) {
            401, 403 -> GitException.AuthFailed(host)
            404 -> GitException.RemoteNotFound(url)
            429 -> GitException.Network(retryable = true, cause = java.io.IOException("被限流（429）"))
            else -> GitException.Network(
                retryable = code >= 500,
                cause = java.io.IOException("HTTP $code${detail?.let { "：$it" }.orEmpty()}"),
            )
        }
    }

    private fun decodeBody(connection: HttpURLConnection): InputStream {
        val raw = connection.inputStream
        return if (connection.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
    }

    companion object {
        const val SERVICE_UPLOAD_PACK = "git-upload-pack"
        const val SERVICE_RECEIVE_PACK = "git-receive-pack"

        fun readAll(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
