package app.gitpocket.core.git

import app.gitpocket.core.model.ObjectId
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

internal object Be {
    fun int(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
            ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or
            (b[off + 3].toInt() and 0xff)

    fun uInt(b: ByteArray, off: Int): Long = int(b, off).toLong() and 0xFFFFFFFFL

    fun long(b: ByteArray, off: Int): Long =
        (uInt(b, off) shl 32) or uInt(b, off + 4)
}

/**
 * packfile 读取器（idx v2 + pack v2/v3）。
 *
 * 支持：commit/tree/blob/tag、OBJ_OFS_DELTA(6)、OBJ_REF_DELTA(7)。
 * 不支持：idx v1、thin pack（补薄由上层 fetch 负责，这里只读落盘的完整包）。
 */
class PackFile internal constructor(
    private val packFile: File,
    idxFile: File,
) : Closeable {

    private val raf = RandomAccessFile(packFile, "r")
    private val count: Int
    private val shaTable: ByteArray
    private val offsets: LongArray

    val packName: String = packFile.name

    init {
        val idx = idxFile.readBytes()
        if (idx.size < 8 + 256 * 4 + 40) {
            throw GitException.ObjectCorrupted("", "pack 索引过短：${idxFile.name}")
        }
        val magic = byteArrayOf(0xFF.toByte(), 't'.code.toByte(), 'O'.code.toByte(), 'c'.code.toByte())
        if (!idx.copyOfRange(0, 4).contentEquals(magic)) {
            throw GitException.UnsupportedFeature("pack index v1（仅支持 v2）")
        }
        val version = Be.int(idx, 4)
        if (version != 2) throw GitException.UnsupportedFeature("pack index v$version")

        count = Be.int(idx, 8 + 255 * 4)
        if (count < 0) throw GitException.ObjectCorrupted("", "pack 索引条目数为负")

        var p = 8 + 256 * 4
        if (p + count * 20 + count * 8 > idx.size) {
            throw GitException.ObjectCorrupted("", "pack 索引长度与条目数不匹配")
        }
        shaTable = idx.copyOfRange(p, p + count * 20)
        p += count * 20
        p += count * 4 // CRC32 表，读取时不需要

        val rawOffsets = LongArray(count) { Be.uInt(idx, p + it * 4) }
        p += count * 4

        for (i in 0 until count) {
            val v = rawOffsets[i]
            if (v and 0x80000000L != 0L) {
                val largeIndex = (v and 0x7FFFFFFFL).toInt()
                val at = p + largeIndex * 8
                if (at + 8 > idx.size) {
                    throw GitException.ObjectCorrupted("", "pack 大偏移表越界")
                }
                rawOffsets[i] = Be.long(idx, at)
            }
        }
        offsets = rawOffsets
    }

    fun contains(id: ObjectId): Boolean = indexOf(id) >= 0

    fun objectCount(): Int = count

    fun read(id: ObjectId, resolveExternal: (ObjectId) -> GitObject): GitObject {
        val index = indexOf(id)
        if (index < 0) throw GitException.ObjectCorrupted(id.hex, "pack 中不存在该对象")
        return synchronized(raf) { readAt(offsets[index], resolveExternal, 0) }
    }

    private fun indexOf(id: ObjectId): Int {
        val target = Sha1.fromHex(id.hex)
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareBytes(shaTable, mid * 20, target)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun readAt(offset: Long, resolveExternal: (ObjectId) -> GitObject, depth: Int): GitObject {
        if (depth > 64) throw GitException.ObjectCorrupted("", "delta 链过深（>64）")
        raf.seek(offset)

        var b = raf.read()
        if (b < 0) throw GitException.ObjectCorrupted("", "pack 对象偏移越界：$offset")
        val typeCode = (b ushr 4) and 0x07
        var size = (b and 0x0f).toLong()
        var shift = 4
        while (b and 0x80 != 0) {
            b = raf.read()
            if (b < 0) throw GitException.ObjectCorrupted("", "pack 对象头截断")
            size += (b and 0x7f).toLong() shl shift
            shift += 7
            if (shift > 63) throw GitException.ObjectCorrupted("", "pack 对象长度字段非法")
        }

        return when (typeCode) {
            6 -> {
                // OBJ_OFS_DELTA：负偏移变长编码
                var c = raf.read()
                if (c < 0) throw GitException.ObjectCorrupted("", "ofs-delta 头截断")
                var back = (c and 0x7f).toLong()
                while (c and 0x80 != 0) {
                    c = raf.read()
                    if (c < 0) throw GitException.ObjectCorrupted("", "ofs-delta 偏移截断")
                    back = ((back + 1) shl 7) or (c and 0x7f).toLong()
                }
                val dataStart = raf.filePointer
                val delta = inflateAt(dataStart, size)
                val base = readAt(offset - back, resolveExternal, depth + 1)
                decodeObject(base.type, Delta.apply(base.content, delta))
            }

            7 -> {
                val baseId = ByteArray(20)
                raf.readFully(baseId)
                val dataStart = raf.filePointer
                val delta = inflateAt(dataStart, size)
                val base = resolveExternal(ObjectId(Sha1.toHex(baseId)))
                decodeObject(base.type, Delta.apply(base.content, delta))
            }

            else -> {
                val objectType = GitObjectType.fromCode(typeCode)
                val dataStart = raf.filePointer
                val data = inflateAt(dataStart, size)
                if (data.size.toLong() != size) {
                    throw GitException.ObjectCorrupted("", "对象长度不符：期望 $size，实际 ${data.size}")
                }
                decodeObject(objectType, data)
            }
        }
    }

    private fun inflateAt(start: Long, expected: Long): ByteArray {
        raf.seek(start)
        val input = object : InputStream() {
            override fun read(): Int = raf.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
        }
        return Zlib.inflateStream(input, expected.toInt())
    }

    /** 校验 pack 尾部 SHA-1。O(文件大小)，仅在测试/诊断时调用。 */
    fun verifyChecksum(): Boolean {
        val fileLength = raf.length()
        if (fileLength < 32) return false
        raf.seek(0)
        val digest = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(64 * 1024)
        var remaining = fileLength - 20
        while (remaining > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            digest.update(buf, 0, n)
            remaining -= n
        }
        val actual = digest.digest()
        val expected = ByteArray(20)
        raf.readFully(expected)
        return actual.contentEquals(expected)
    }

    override fun close() {
        raf.close()
    }

    private fun compareBytes(table: ByteArray, offset: Int, target: ByteArray): Int {
        for (i in 0 until 20) {
            val a = table[offset + i].toInt() and 0xff
            val b = target[i].toInt() and 0xff
            if (a != b) return a - b
        }
        return 0
    }
}

/** delta 指令应用（Git 的 copy/insert 指令集）。 */
internal object Delta {

    fun apply(base: ByteArray, delta: ByteArray): ByteArray {
        var p = 0

        fun readVarInt(): Int {
            var value = 0
            var shift = 0
            while (true) {
                if (p >= delta.size) throw GitException.ObjectCorrupted("", "delta 长度字段截断")
                val b = delta[p++].toInt() and 0xff
                value = value or ((b and 0x7f) shl shift)
                shift += 7
                if (b and 0x80 == 0) break
                if (shift > 28) throw GitException.ObjectCorrupted("", "delta 长度字段过大")
            }
            return value
        }

        val srcSize = readVarInt()
        if (srcSize != base.size) {
            throw GitException.ObjectCorrupted("", "delta 基对象长度不符：期望 $srcSize，实际 ${base.size}")
        }
        val dstSize = readVarInt()
        val out = ByteArray(dstSize)
        var o = 0

        while (p < delta.size) {
            val op = delta[p++].toInt() and 0xff
            if (op and 0x80 != 0) {
                var copyOffset = 0
                var copySize = 0
                if (op and 0x01 != 0) copyOffset = copyOffset or (delta[p++].toInt() and 0xff)
                if (op and 0x02 != 0) copyOffset = copyOffset or ((delta[p++].toInt() and 0xff) shl 8)
                if (op and 0x04 != 0) copyOffset = copyOffset or ((delta[p++].toInt() and 0xff) shl 16)
                if (op and 0x08 != 0) copyOffset = copyOffset or ((delta[p++].toInt() and 0xff) shl 24)
                if (op and 0x10 != 0) copySize = copySize or (delta[p++].toInt() and 0xff)
                if (op and 0x20 != 0) copySize = copySize or ((delta[p++].toInt() and 0xff) shl 8)
                if (op and 0x40 != 0) copySize = copySize or ((delta[p++].toInt() and 0xff) shl 16)
                if (copySize == 0) copySize = 0x10000
                if (copyOffset + copySize > base.size) {
                    throw GitException.ObjectCorrupted("", "delta copy 越界")
                }
                if (o + copySize > out.size) throw GitException.ObjectCorrupted("", "delta 输出越界")
                System.arraycopy(base, copyOffset, out, o, copySize)
                o += copySize
            } else if (op != 0) {
                if (p + op > delta.size || o + op > out.size) {
                    throw GitException.ObjectCorrupted("", "delta insert 越界")
                }
                System.arraycopy(delta, p, out, o, op)
                p += op
                o += op
            } else {
                throw GitException.ObjectCorrupted("", "delta 非法操作码 0x00")
            }
        }

        if (o != dstSize) throw GitException.ObjectCorrupted("", "delta 输出长度不符：期望 $dstSize，实际 $o")
        return out
    }
}
