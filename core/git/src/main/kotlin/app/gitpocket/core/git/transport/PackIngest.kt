package app.gitpocket.core.git.transport

import app.gitpocket.core.git.Delta
import app.gitpocket.core.git.GitException
import app.gitpocket.core.git.GitObject
import app.gitpocket.core.git.GitObjectType
import app.gitpocket.core.git.ObjectDatabase
import app.gitpocket.core.git.Sha1
import app.gitpocket.core.git.decodeObject
import app.gitpocket.core.model.ObjectId
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater

/**
 * 把收到的 packfile 拆成 loose object 落库。
 *
 * 取舍：不做 idx/pack 落盘（那需要额外维护 .idx、多 pack 与 repack），
 * 直接把每个对象解压后按 loose 写入。实现简单、可验证，代价是首次 clone 后
 * 对象目录更碎、占用略大。后续可替换为 pack + idx 落盘而不影响上层。
 */
internal object PackIngest {

    fun ingest(pack: ByteArray, db: ObjectDatabase, onProgress: (Int) -> Unit = {}): Int {
        if (pack.size < 32) throw GitException.ObjectCorrupted("", "pack 过短（${pack.size} 字节）")
        if (String(pack, 0, 4, StandardCharsets.US_ASCII) != "PACK") {
            throw GitException.ObjectCorrupted("", "pack 魔数错误")
        }
        val version = be32(pack, 4)
        if (version !in 2..3) throw GitException.UnsupportedFeature("pack v$version")

        val count = be32(pack, 8)
        val bodyEnd = pack.size - 20
        val expected = Sha1.digest(pack.copyOfRange(0, bodyEnd))
        val actual = ObjectId(Sha1.toHex(pack.copyOfRange(bodyEnd, pack.size)))
        if (expected != actual) throw GitException.ObjectCorrupted("", "pack 校验和不匹配")

        var p = 12
        val offsetToOid = HashMap<Long, ObjectId>()
        val written = HashSet<ObjectId>()

        repeat(count) { index ->
            val objectStart = p.toLong()
            if (p >= bodyEnd) throw GitException.ObjectCorrupted("", "pack 对象头越界")

            var b = pack[p++].toInt() and 0xff
            val typeCode = (b ushr 4) and 0x07
            var size = (b and 0x0f).toLong()
            var shift = 4
            while (b and 0x80 != 0) {
                if (p >= bodyEnd) throw GitException.ObjectCorrupted("", "pack 对象头截断")
                b = pack[p++].toInt() and 0xff
                size += (b and 0x7f).toLong() shl shift
                shift += 7
                if (shift > 63) throw GitException.ObjectCorrupted("", "pack 对象长度非法")
            }

            var baseOffset = -1L
            var baseId: ObjectId? = null
            when (typeCode) {
                6 -> {
                    var c = pack[p++].toInt() and 0xff
                    var back = (c and 0x7f).toLong()
                    while (c and 0x80 != 0) {
                        c = pack[p++].toInt() and 0xff
                        back = ((back + 1) shl 7) or (c and 0x7f).toLong()
                    }
                    baseOffset = objectStart - back
                }

                7 -> {
                    if (p + 20 > bodyEnd) throw GitException.ObjectCorrupted("", "ref-delta base 截断")
                    baseId = ObjectId(Sha1.toHex(pack.copyOfRange(p, p + 20)))
                    p += 20
                }
            }

            val (data, consumed) = inflateAt(pack, p, size.toInt())
            p += consumed

            if (typeCode in 1..4 && data.size.toLong() != size) {
                throw GitException.ObjectCorrupted("", "对象长度不符：期望 $size，实际 ${data.size}")
            }

            val obj: GitObject = when (typeCode) {
                1, 2, 3, 4 -> decodeObject(GitObjectType.fromCode(typeCode), data)
                6 -> {
                    val baseOid = offsetToOid[baseOffset]
                        ?: throw GitException.ObjectCorrupted("", "ofs-delta 基对象缺失（偏移 $baseOffset）")
                    val base = db.read(baseOid)
                    decodeObject(base.type, Delta.apply(base.content, data))
                }

                7 -> {
                    val base = runCatching { db.read(baseId!!) }.getOrElse {
                        throw GitException.UnsupportedFeature("thin pack（基对象 ${baseId!!.hex} 不在本地）")
                    }
                    decodeObject(base.type, Delta.apply(base.content, data))
                }

                else -> throw GitException.ObjectCorrupted("", "未知 pack 对象类型 $typeCode")
            }

            val oid = db.write(obj)
            offsetToOid[objectStart] = oid
            written.add(oid)

            if ((index + 1) % 100 == 0) onProgress(index + 1)
        }

        if (p > bodyEnd + 1) throw GitException.ObjectCorrupted("", "pack 解析越界")
        onProgress(count)
        return written.size
    }

    private fun inflateAt(bytes: ByteArray, offset: Int, expected: Int): Pair<ByteArray, Int> {
        if (offset >= bytes.size) throw GitException.ObjectCorrupted("", "zlib 流越界")
        val inflater = Inflater(false)
        try {
            inflater.setInput(bytes, offset, bytes.size - offset)
            val out = ByteArrayOutputStream(if (expected > 0) expected else 8192)
            val buffer = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buffer, 0, n)
                }
            }
            val consumed = (bytes.size - offset) - inflater.remaining
            return out.toByteArray() to consumed
        } catch (e: java.util.zip.DataFormatException) {
            throw GitException.ObjectCorrupted("", "pack 内 zlib 解压失败：${e.message}")
        } finally {
            inflater.end()
        }
    }

    private fun be32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}
