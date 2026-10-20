package app.gitpocket.core.git

import app.gitpocket.core.model.ObjectId
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 对象数据库：先查 loose object，再查 packfile。
 * 写入一律走"临时文件 + rename"，避免半截对象。
 */
class ObjectDatabase(private val gitDir: File) : Closeable {

    private val objectsDir = File(gitDir, "objects")
    private val packsLock = Any()
    @Volatile
    private var cachedPacks: List<PackFile>? = null

    fun read(id: ObjectId): GitObject = readInternal(id, 0)

    private fun readInternal(id: ObjectId, depth: Int): GitObject {
        if (depth > 64) throw GitException.ObjectCorrupted(id.hex, "对象引用链过深")

        val loose = looseFile(id)
        if (loose.isFile) return readLoose(loose, id)

        val pack = packs().firstOrNull { it.contains(id) }
            ?: throw GitException.ObjectCorrupted(id.hex, "对象不存在（loose 与 pack 均未命中）")
        return pack.read(id) { base -> readInternal(base, depth + 1) }
    }

    fun exists(id: ObjectId): Boolean {
        if (looseFile(id).isFile) return true
        return packs().any { it.contains(id) }
    }

    fun write(obj: GitObject): ObjectId {
        val content = obj.content
        val header = "${obj.type.label} ${content.size}\u0000".toByteArray(StandardCharsets.US_ASCII)
        val raw = ByteArray(header.size + content.size)
        header.copyInto(raw, 0)
        content.copyInto(raw, header.size)

        val id = Sha1.digest(raw)
        val target = looseFile(id)
        if (target.isFile) return id

        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
        try {
            tmp.writeBytes(Zlib.compress(raw))
            if (!tmp.renameTo(target)) {
                // 某些文件系统 rename 目标已存在会失败：删除后重试一次
                target.delete()
                if (!tmp.renameTo(target)) throw GitException.Io(target.path, java.io.IOException("rename 失败"))
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return id
    }

    fun listPacks(): List<PackFile> = packs()

    private fun readLoose(file: File, expected: ObjectId): GitObject {
        val raw = try {
            Zlib.decompress(file.readBytes())
        } catch (e: GitException) {
            throw GitException.ObjectCorrupted(expected.hex, "loose object 解压失败")
        }
        val nul = raw.indexOf(0.toByte())
        if (nul <= 0) throw GitException.ObjectCorrupted(expected.hex, "loose object 头缺少 NUL")

        val header = String(raw, 0, nul, StandardCharsets.US_ASCII)
        val space = header.indexOf(' ')
        if (space <= 0) throw GitException.ObjectCorrupted(expected.hex, "loose object 头格式非法：$header")

        val type = GitObjectType.fromLabel(header.substring(0, space))
        val size = header.substring(space + 1).toIntOrNull()
            ?: throw GitException.ObjectCorrupted(expected.hex, "loose object 长度非法：$header")

        val content = raw.copyOfRange(nul + 1, raw.size)
        if (content.size != size) {
            throw GitException.ObjectCorrupted(expected.hex, "长度不符：期望 $size，实际 ${content.size}")
        }

        val actual = Sha1.digest(raw)
        if (actual != expected) {
            throw GitException.ObjectCorrupted(expected.hex, "SHA-1 校验失败（实际 ${actual.hex}）")
        }
        return decodeObject(type, content)
    }

    private fun looseFile(id: ObjectId): File =
        File(File(objectsDir, id.hex.substring(0, 2)), id.hex.substring(2))

    private fun packs(): List<PackFile> {
        cachedPacks?.let { return it }
        synchronized(packsLock) {
            cachedPacks?.let { return it }
            val dir = File(objectsDir, "pack")
            val loaded = if (dir.isDirectory) {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".idx") }
                    ?.sortedBy { it.name }
                    ?.map { idx ->
                        val pack = File(dir, idx.name.removeSuffix(".idx") + ".pack")
                        if (!pack.isFile) {
                            throw GitException.ObjectCorrupted("", "pack 索引缺少对应 pack：${idx.name}")
                        }
                        PackFile(pack, idx)
                    }
                    .orEmpty()
            } else {
                emptyList()
            }
            cachedPacks = loaded
            return loaded
        }
    }

    override fun close() {
        synchronized(packsLock) {
            cachedPacks?.forEach { runCatching { it.close() } }
            cachedPacks = null
        }
    }
}
