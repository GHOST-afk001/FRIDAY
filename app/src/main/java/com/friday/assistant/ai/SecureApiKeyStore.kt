package com.friday.assistant.ai

import android.content.Context
import android.util.Base64
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Android Keystore-backed store with an app-private recovery copy for OEM Keystore failures. */
class SecureApiKeyStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("friday_secure", Context.MODE_PRIVATE)
    private val fallbackFile = File(appContext.filesDir, ".friday_gemini_key")
    private val alias = "friday_gemini_key"

    fun save(value: String): Boolean {
        val clean = value.trim()
        if (clean.isBlank()) return false
        clear()
        val encrypted = runCatching { writeEncrypted(clean) && readEncrypted() == clean }.getOrDefault(false)
        if (encrypted) return true
        return runCatching {
            fallbackFile.writeText(clean, Charsets.UTF_8)
            fallbackFile.exists() && fallbackFile.readText(Charsets.UTF_8) == clean
        }.getOrDefault(false)
    }

    fun read(): String? {
        readEncrypted()?.let { if (it.isNotBlank()) return it }
        return runCatching {
            fallbackFile.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun clear() {
        runCatching { prefs.edit().remove("iv").remove("data").commit() }
        runCatching { fallbackFile.delete() }
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
    }

    private fun readEncrypted(): String? = runCatching {
        val iv = prefs.getString("iv", null) ?: return null
        val data = prefs.getString("data", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun writeEncrypted(clean: String): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
    }.getOrDefault(false)

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
