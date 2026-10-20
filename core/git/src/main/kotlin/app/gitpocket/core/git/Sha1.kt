package app.gitpocket.core.git

import app.gitpocket.core.model.ObjectId
import java.security.MessageDigest

/** SHA-1 工具。Git 用 SHA-1 作为对象寻址，这里集中处理，避免散落。 */
internal object Sha1 {

    fun digest(bytes: ByteArray): ObjectId = ObjectId(toHex(MessageDigest.getInstance("SHA-1").digest(bytes)))

    fun digest(vararg chunks: ByteArray): ObjectId {
        val md = MessageDigest.getInstance("SHA-1")
        chunks.forEach { md.update(it) }
        return ObjectId(toHex(md.digest()))
    }

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-1")

    fun toHex(bytes: ByteArray): String {
        val table = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(table[v ushr 4]).append(table[v and 0x0f])
        }
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "十六进制长度必须为偶数：${hex.length}" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "非法十六进制字符：$hex" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
