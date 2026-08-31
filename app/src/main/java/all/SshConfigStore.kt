package io.github.aixtin.nyral

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SshConfigStore {

    data class SshConfig(
        val name: String,
        val host: String,
        val port: Int,
        val user: String,
        val password: String? = null,
        val privateKey: String? = null,
        val passphrase: String? = null,
        // 跳板机(经 SSH 跳转): 非空时先连跳板机再经隧道连目标
        val proxyHost: String? = null,
        val proxyPort: Int = 22,
        val proxyUser: String? = null,
        val proxyPassword: String? = null,
        val proxyPrivateKey: String? = null,
        val proxyPassphrase: String? = null
    ) {
        val hasProxy: Boolean get() = !proxyHost.isNullOrBlank()
    }

    private const val PREFS = "ssh_configs"
    private const val KEY_ALIAS = "droid_agent_ssh_key"
    private const val GCM_TAG_BITS = 128

    fun load(context: Context): List<SshConfig> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("configs_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(decrypt(context, raw))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SshConfig(
                    name = o.optString("name", ""),
                    host = o.optString("host", ""),
                    port = o.optInt("port", 22),
                    user = o.optString("user", ""),
                    password = o.optString("password", "").ifEmpty { null },
                    privateKey = o.optString("privateKey", "").ifEmpty { null },
                    passphrase = o.optString("passphrase", "").ifEmpty { null },
                    proxyHost = o.optString("proxyHost", "").ifEmpty { null },
                    proxyPort = o.optInt("proxyPort", 22),
                    proxyUser = o.optString("proxyUser", "").ifEmpty { null },
                    proxyPassword = o.optString("proxyPassword", "").ifEmpty { null },
                    proxyPrivateKey = o.optString("proxyPrivateKey", "").ifEmpty { null },
                    proxyPassphrase = o.optString("proxyPassphrase", "").ifEmpty { null }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, configs: List<SshConfig>) {
        val arr = JSONArray()
        configs.forEach { c ->
            arr.put(JSONObject().apply {
                put("name", c.name)
                put("host", c.host)
                put("port", c.port)
                put("user", c.user)
                put("password", c.password ?: "")
                put("privateKey", c.privateKey ?: "")
                put("passphrase", c.passphrase ?: "")
                put("proxyHost", c.proxyHost ?: "")
                put("proxyPort", c.proxyPort)
                put("proxyUser", c.proxyUser ?: "")
                put("proxyPassword", c.proxyPassword ?: "")
                put("proxyPrivateKey", c.proxyPrivateKey ?: "")
                put("proxyPassphrase", c.proxyPassphrase ?: "")
            })
        }
        val encrypted = encrypt(context, arr.toString())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("configs_json", encrypted).apply()
    }

    private fun getOrCreateKey(): SecretKey {
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

    private fun encrypt(context: Context, plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val out = ByteArray(4 + iv.size + ct.size)
        // 4字节iv长度 + iv + ciphertext
        out[0] = (iv.size ushr 24).toByte(); out[1] = (iv.size ushr 16).toByte()
        out[2] = (iv.size ushr 8).toByte(); out[3] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 4, iv.size)
        System.arraycopy(ct, 0, out, 4 + iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(context: Context, encoded: String): String {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLen = ((data[0].toInt() and 0xFF) shl 24) or ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val iv = data.copyOfRange(4, 4 + ivLen)
        val ct = data.copyOfRange(4 + ivLen, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}
