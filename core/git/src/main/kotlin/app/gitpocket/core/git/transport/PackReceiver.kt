package app.gitpocket.core.git.transport

import app.gitpocket.core.git.GitException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * 从 smart HTTP 响应里取出 packfile 字节。
 *
 * 服务端可能用 side-band 把 pack 切成 pkt-line（band 1 是数据，band 2 是进度，
 * band 3 是错误），也可能直接裸发。两种都要能处理。
 */
internal object PackReceiver {

    fun read(input: InputStream, sideband: Boolean, onBytes: (Long) -> Unit = {}): ByteArray =
        if (sideband) readSideband(input, onBytes) else readRaw(input)

    private fun readRaw(input: InputStream): ByteArray {
        // 跳过 ACK/NAK 之类的文本行，剩下的就是 pack
        val reader = PktLineReader(input)
        while (!reader.eof) {
            val packet = reader.next() ?: break
            val text = String(packet, StandardCharsets.US_ASCII).trim()
            if (text.startsWith("NAK") || text.startsWith("ACK")) break
            // 非 ACK 的首包通常已经是 pack 数据（"PACK"），此时直接交给下游
            if (packet.size >= 4 && String(packet, 0, 4, StandardCharsets.US_ASCII) == "PACK") {
                val rest = ByteArrayOutputStream()
                rest.write(packet)
                rest.write(GitHttpClient.readAll(input))
                return rest.toByteArray()
            }
        }
        return GitHttpClient.readAll(input)
    }

    private fun readSideband(input: InputStream, onBytes: (Long) -> Unit): ByteArray {
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
                    onBytes(received)
                }

                2 -> Unit // 进度文本，忽略
                3 -> {
                    val message = String(packet, 1, packet.size - 1, StandardCharsets.UTF_8)
                    throw GitException.Network(retryable = false, cause = IOException("服务端错误：$message"))
                }

                else -> Unit // ACK / NAK
            }
        }
        return out.toByteArray()
    }
}
