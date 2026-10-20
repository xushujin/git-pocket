package app.gitpocket.core.git

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** zlib 压缩/解压。使用平台自带 java.util.zip，不引入第三方库。 */
internal object Zlib {

    fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buf = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    fun decompress(data: ByteArray): ByteArray {
        val inflater = Inflater(false)
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 3)
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buf)
                } catch (e: java.util.zip.DataFormatException) {
                    throw GitException.ObjectCorrupted("", "zlib 解压失败：${e.message}")
                }
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /** 从流中解压一个 zlib 段（用于 packfile：长度未知，读到流结束）。 */
    fun inflateStream(input: java.io.InputStream, expectedSize: Int = 0): ByteArray {
        InflaterInputStream(input, Inflater(false), 16 * 1024).use { ins ->
            val out = ByteArrayOutputStream(if (expectedSize > 0) expectedSize else 16 * 1024)
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
