package com.tongway.translator

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ключ OpenRouter хранится только на телефоне, зашифрованным ключом из системного
 * хранилища Android (Keystore): прочитать его может только Tongway на этом телефоне.
 */
object SecretStore {
    private const val ALIAS = "tongway_api_key"
    private const val PREF_KEY = "openrouter_key_enc"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun saveApiKey(context: Context, key: String) {
        val trimmed = key.trim()
        val prefs = Prefs.prefs(context).edit()
        if (trimmed.isEmpty()) {
            prefs.remove(PREF_KEY).apply()
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val packed = cipher.iv + encrypted
            prefs.putString(PREF_KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
        }.onFailure { AppLog.e("Не удалось сохранить ключ", it) }
    }

    fun apiKey(context: Context): String? {
        val stored = Prefs.prefs(context).getString(PREF_KEY, null) ?: return null
        return runCatching {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, IV_SIZE)
            val data = packed.copyOfRange(IV_SIZE, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.onFailure { AppLog.e("Не удалось прочитать ключ", it) }.getOrNull()
    }

    /** Для журнала: только начало и конец ключа. */
    fun masked(key: String?): String =
        if (key.isNullOrEmpty()) "нет" else if (key.length <= 12) "…" else key.take(6) + "…" + key.takeLast(4)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private const val IV_SIZE = 12
}
