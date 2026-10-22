package app.gitpocket.core.git.transport

import app.gitpocket.core.git.GitObject
import app.gitpocket.core.git.GitObjectType
import app.gitpocket.core.git.Zlib
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * 生成 packfile（version 2，无 delta）。
 *
 * 为什么不写 delta：push 的对象集通常很小（一次提交涉及的文件），
 * 全量对象虽然体积略大，但省掉了 delta 窗口选择与基对象管理，
 * 出错面小很多。服务端能正常接收不带 delta 的 pack。
 */
internal object PackWriter {

    fun write(objects: List<GitObject>): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        out.write("PACK".toByteArray(Charsets.US_ASCII))
        writeBe32(out, 2)
        writeBe32(out, objects.size)

        objects.forEach { obj ->
            writeObjectHeader(out, obj.type, obj.content.size)
            out.write(Zlib.compress(obj.content))
        }

        val body = out.toByteArray()
        out.write(MessageDigest.getInstance("SHA-1").digest(body))
        return out.toByteArray()
    }

    /** 头部变长编码：首字节低 4 位存 size 低 4 位，其余每字节 7 位，最高位表示还有后续。 */
    private fun writeObjectHeader(out: ByteArrayOutputStream, type: GitObjectType, size: Int) {
        var remaining = size
        var byte = (type.code shl 4) or (remaining and 0x0F)
        remaining = remaining ushr 4
        while (remaining != 0) {
            out.write(byte or 0x80)
            byte = remaining and 0x7F
            remaining = remaining ushr 7
        }
        out.write(byte)
    }

    private fun writeBe32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
