package app.gitpocket.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Token 存储：AES-256-GCM，密钥在 AndroidKeyStore 中生成且不可导出。
 * 文件格式：version(1) + ivLen(1) + iv + ciphertext。
 *
 * 不用 androidx.security:security-crypto（其维护状态存在不确定性），
 * 直接用平台 KeyStore API，行为可预期。
 */
class KeystoreTokenStore(private val directory: File) {

    fun save(accountId: String, token: String) {
        directory.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))

        val payload = ByteArray(2 + iv.size + encrypted.size)
        payload[0] = FORMAT_VERSION
        payload[1] = iv.size.toByte()
        iv.copyInto(payload, 2)
        encrypted.copyInto(payload, 2 + iv.size)

        val file = fileFor(accountId)
        val tmp = File(directory, "${file.name}.tmp")
        tmp.writeBytes(payload)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun load(accountId: String): String? {
        val file = fileFor(accountId)
        if (!file.isFile) return null
        return runCatching {
            val payload = file.readBytes()
            if (payload.size < 3 || payload[0] != FORMAT_VERSION) return null
            val ivLength = payload[1].toInt()
            val iv = payload.copyOfRange(2, 2 + ivLength)
            val cipherText = payload.copyOfRange(2 + ivLength, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    fun delete(accountId: String) {
        fileFor(accountId).delete()
    }

    fun hasToken(accountId: String): Boolean = fileFor(accountId).isFile

    private fun fileFor(accountId: String): File =
        File(directory, "token_${accountId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}.bin")

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "gitpocket_token_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
    }
}
