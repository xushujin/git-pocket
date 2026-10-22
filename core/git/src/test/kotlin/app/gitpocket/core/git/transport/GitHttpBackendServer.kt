package app.gitpocket.core.git.transport

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * 测试用的 smart HTTP 服务端：把请求转给真实的 `git http-backend`（CGI）。
 *
 * 这样测的不是"我理解的协议"，而是 git 自己实现的协议——
 * fetch/push 的 pkt-line、side-band、report-status 都由真 git 校验。
 */
class GitHttpBackendServer(private val root: File) {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun start() {
        server.executor = Executors.newFixedThreadPool(2)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes()
        val env = buildMap {
            put("GIT_PROJECT_ROOT", root.absolutePath)
            put("GIT_HTTP_EXPORT_ALL", "1")
            put("PATH_INFO", exchange.requestURI.path)
            put("QUERY_STRING", exchange.requestURI.rawQuery.orEmpty())
            put("REQUEST_METHOD", exchange.requestMethod)
            put("CONTENT_TYPE", exchange.requestHeaders.getFirst("Content-Type").orEmpty())
            put("CONTENT_LENGTH", body.size.toString())
            put("REMOTE_USER", "")
            put("GATEWAY_INTERFACE", "CGI/1.1")
        }

        val process = ProcessBuilder("git", "http-backend")
            .apply { environment().putAll(env) }
            .start()

        process.outputStream.use { it.write(body) }
        val raw = process.inputStream.readBytes()
        process.waitFor()

        // CGI 输出：头部（含可能的 Status:） + 空行 + 正文
        val separator = indexOfHeaderEnd(raw)
        val headerBytes = raw.copyOfRange(0, separator)
        val payload = raw.copyOfRange(separator + 4, raw.size)
        val headers = String(headerBytes, Charsets.ISO_8859_1).lines().filter { it.contains(':') }

        var status = 200
        headers.forEach { line ->
            val name = line.substringBefore(':').trim()
            val value = line.substringAfter(':').trim()
            if (name.equals("Status", ignoreCase = true)) {
                status = value.substringBefore(' ').toIntOrNull() ?: 200
            } else if (name.isNotEmpty()) {
                exchange.responseHeaders.add(name, value)
            }
        }

        exchange.sendResponseHeaders(status, payload.size.toLong())
        exchange.responseBody.use { it.write(payload) }
    }

    private fun indexOfHeaderEnd(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == 13.toByte() && bytes[i + 1] == 10.toByte() &&
                bytes[i + 2] == 13.toByte() && bytes[i + 3] == 10.toByte()
            ) {
                return i
            }
        }
        return bytes.size
    }
}
