package app.gitpocket.core.git.transport

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Git pkt-line 编解码。
 * 格式：4 位十六进制长度（含这 4 字节本身）+ payload。
 * 特殊：`0000` flush、`0001` delimiter、`0002` response-end。
 */
internal object PktLine {

    val FLUSH = byteArrayOf('0'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(), '0'.code.toByte())

    fun encode(payload: ByteArray): ByteArray {
        val length = payload.size + 4
        require(length <= 0xFFFF) { "pkt-line 过长：$length" }
        val header = String.format("%04x", length).toByteArray(StandardCharsets.US_ASCII)
        return header + payload
    }

    fun encode(text: String): ByteArray = encode(text.toByteArray(StandardCharsets.UTF_8))

    fun write(output: OutputStream, text: String) {
        output.write(encode(text))
    }

    fun writeFlush(output: OutputStream) {
        output.write(FLUSH)
    }
}

/**
 * 逐条读取 pkt-line。
 * `next()` 返回 null 表示遇到 flush/delimiter；真正的流结束通过 [eof] 判断。
 */
internal class PktLineReader(private val input: InputStream) {

    var eof: Boolean = false
        private set

    fun next(): ByteArray? {
        val header = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = input.read(header, read, 4 - read)
            if (n < 0) {
                if (read == 0) {
                    eof = true
                    return null
                }
                throw EOFException("pkt-line 头被截断")
            }
            read += n
        }
        val length = String(header, StandardCharsets.US_ASCII).toIntOrNull(16)
            ?: throw IllegalStateException("非法 pkt-line 长度：${String(header)}")
        if (length == 0 || length == 1 || length == 2) return null
        if (length < 4) throw IllegalStateException("非法 pkt-line 长度：$length")

        val payload = ByteArray(length - 4)
        var offset = 0
        while (offset < payload.size) {
            val n = input.read(payload, offset, payload.size - offset)
            if (n < 0) throw EOFException("pkt-line 内容被截断")
            offset += n
        }
        return payload
    }

    fun nextText(): String? = next()?.let { String(it, StandardCharsets.UTF_8).trimEnd('\n') }
}
