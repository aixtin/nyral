package io.github.aixtin.nyral

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 通用敏感数据加密助手（AndroidKeyStore AES/GCM）。
 * 与 SshConfigStore 的 SSH 凭据加密同一套机制，alias 独立避免互扰。
 * 用于 ApiConfig 的模型 Key、WebTools 的 site_auth Cookie 等敏感落盘数据加密。
 */
object Secrets {
    private const val KEY_ALIAS = "nyral_app_secrets"

    fun encrypt(context: Context, plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(context))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val out = ByteArray(4 + iv.size + ct.size)
        out[0] = (iv.size ushr 24).toByte(); out[1] = (iv.size ushr 16).toByte()
        out[2] = (iv.size ushr 8).toByte(); out[3] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 4, iv.size)
        System.arraycopy(ct, 0, out, 4 + iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(context: Context, encoded: String): String? {
        return try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            if (data.size < 5) return null
            val ivLen = ((data[0].toInt() and 0xFF) shl 24) or ((data[1].toInt() and 0xFF) shl 16) or
                    ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            if (ivLen <= 0 || 4 + ivLen > data.size) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(context),
                javax.crypto.spec.GCMParameterSpec(128, data, 4, ivLen))
            String(cipher.doFinal(data, 4 + ivLen, data.size - 4 - ivLen), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    private fun getOrCreateKey(context: Context): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return kg.generateKey()
    }
}
