package app.gitpocket.core.git

import app.gitpocket.core.model.ObjectId
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/** 索引条目（对应 Git index 的一个 entry）。stage != 0 表示冲突的三方之一。 */
data class IndexEntry(
    val path: String,
    val oid: ObjectId,
    val mode: Int = GitTreeEntry.MODE_FILE,
    val stage: Int = 0,
    val size: Long = 0,
    val mtimeSeconds: Long = 0,
    val mtimeNanos: Int = 0,
    val ctimeSeconds: Long = 0,
    val ctimeNanos: Int = 0,
    val assumeValid: Boolean = false,
) {
    val isConflict: Boolean get() = stage != 0
}

/**
 * Git index（DIRC）读写，支持 v2/v3。
 * 不支持 v4（路径前缀压缩），遇到时抛 UnsupportedFeature。
 */
class GitIndex(val entries: MutableList<IndexEntry> = mutableListOf()) {

    fun entry(path: String, stage: Int = 0): IndexEntry? =
        entries.firstOrNull { it.path == path && it.stage == stage }

    fun put(entry: IndexEntry) {
        entries.removeAll { it.path == entry.path && it.stage == entry.stage }
        entries.add(entry)
    }

    fun remove(path: String) {
        entries.removeAll { it.path == path }
    }

    fun toMap(): Map<String, IndexEntry> =
        entries.filter { it.stage == 0 }.associateBy { it.path }

    fun writeTo(file: File) {
        val sorted = entries.sortedWith(compareBy({ it.path }, { it.stage }))
        val out = ByteArrayOutputStream()
        out.write("DIRC".toByteArray(StandardCharsets.US_ASCII))
        writeInt(out, 2)
        writeInt(out, sorted.size)

        sorted.forEach { e ->
            val entryStart = out.size()
            writeInt(out, e.ctimeSeconds.toInt())
            writeInt(out, e.ctimeNanos)
            writeInt(out, e.mtimeSeconds.toInt())
            writeInt(out, e.mtimeNanos)
            writeInt(out, 0) // dev
            writeInt(out, 0) // ino
            writeInt(out, e.mode)
            writeInt(out, 0) // uid
            writeInt(out, 0) // gid
            writeInt(out, e.size.toInt())
            out.write(Sha1.fromHex(e.oid.hex))

            val pathBytes = e.path.toByteArray(StandardCharsets.UTF_8)
            val flags = minOf(pathBytes.size, 0xFFF) or
                ((e.stage and 0x3) shl 12) or
                (if (e.assumeValid) 0x8000 else 0)
            writeShort(out, flags)
            out.write(pathBytes)
            out.write(0)
            while ((out.size() - entryStart) % 8 != 0) out.write(0)
        }

        val body = out.toByteArray()
        val digest = Sha1.newDigest().apply { update(body) }.digest()
        val payload = ByteArray(body.size + 20)
        body.copyInto(payload, 0)
        digest.copyInto(payload, body.size)

        file.parentFile?.mkdirs()
        val lock = File(file.parentFile, "${file.name}.lock")
        try {
            lock.writeBytes(payload)
            if (!lock.renameTo(file)) {
                file.delete()
                if (!lock.renameTo(file)) throw GitException.Io(file.path, java.io.IOException("写入 index 失败"))
            }
        } finally {
            if (lock.exists()) lock.delete()
        }
    }

    companion object {
        fun empty(): GitIndex = GitIndex()

        fun read(file: File): GitIndex {
            if (!file.isFile) return empty()
            val bytes = file.readBytes()
            if (bytes.size < 32) throw GitException.IndexCorrupted("文件过短（${bytes.size} 字节）")

            val signature = String(bytes, 0, 4, StandardCharsets.US_ASCII)
            if (signature != "DIRC") throw GitException.IndexCorrupted("签名非法：$signature")

            val version = Be.int(bytes, 4)
            if (version !in 2..3) throw GitException.UnsupportedFeature("index v$version")

            val count = Be.int(bytes, 8)
            if (count < 0) throw GitException.IndexCorrupted("条目数为负")

            val expected = ByteArray(20)
            bytes.copyInto(expected, 0, bytes.size - 20, bytes.size)
            val actual = Sha1.newDigest().apply { update(bytes, 0, bytes.size - 20) }.digest()
            if (!expected.contentEquals(actual)) throw GitException.IndexCorrupted("SHA-1 校验失败")

            val result = ArrayList<IndexEntry>(count)
            var p = 12
            repeat(count) {
                val start = p
                if (p + 62 > bytes.size - 20) throw GitException.IndexCorrupted("条目越界")

                val ctimeSeconds = Be.uInt(bytes, p)
                val ctimeNanos = Be.uInt(bytes, p + 4)
                val mtimeSeconds = Be.uInt(bytes, p + 8)
                val mtimeNanos = Be.uInt(bytes, p + 12)
                val mode = Be.int(bytes, p + 24)
                val size = Be.uInt(bytes, p + 36)
                val oid = ObjectId(Sha1.toHex(bytes.copyOfRange(p + 40, p + 60)))
                val flags = ((bytes[p + 60].toInt() and 0xff) shl 8) or (bytes[p + 61].toInt() and 0xff)

                var cursor = p + 62
                val extended = flags and 0x4000 != 0
                if (extended) {
                    if (version < 3) throw GitException.IndexCorrupted("v2 索引出现扩展标志")
                    cursor += 2
                }

                val nameLength = flags and 0xFFF
                val nameStart = cursor
                val nameEnd = if (nameLength < 0xFFF) {
                    nameStart + nameLength
                } else {
                    var i = nameStart
                    while (i < bytes.size && bytes[i] != 0.toByte()) i++
                    i
                }
                if (nameEnd >= bytes.size) throw GitException.IndexCorrupted("路径越界")

                val path = String(bytes, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8)
                result.add(
                    IndexEntry(
                        path = path,
                        oid = oid,
                        mode = mode,
                        stage = (flags ushr 12) and 0x3,
                        size = size,
                        mtimeSeconds = mtimeSeconds,
                        mtimeNanos = mtimeNanos.toInt(),
                        ctimeSeconds = ctimeSeconds,
                        ctimeNanos = ctimeNanos.toInt(),
                        assumeValid = flags and 0x8000 != 0,
                    )
                )

                val entryLength = (nameEnd - start) + 1 // 含 NUL
                p = start + ((entryLength + 7) / 8) * 8
            }
            return GitIndex(result)
        }

        private fun writeInt(out: ByteArrayOutputStream, value: Int) {
            out.write((value ushr 24) and 0xff)
            out.write((value ushr 16) and 0xff)
            out.write((value ushr 8) and 0xff)
            out.write(value and 0xff)
        }

        private fun writeShort(out: ByteArrayOutputStream, value: Int) {
            out.write((value ushr 8) and 0xff)
            out.write(value and 0xff)
        }
    }
}
