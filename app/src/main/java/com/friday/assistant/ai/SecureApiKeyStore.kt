package com.friday.assistant.ai

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Android Keystore-backed store for user-supplied AI API keys. */
class SecureApiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("friday_secure", Context.MODE_PRIVATE)
    private val alias = "friday_gemini_key"

    fun save(value: String): Boolean {
        val clean = value.trim()
        if (clean.isBlank()) return false
        clear()
        return runCatching { writeEncrypted(clean) && read() == clean }.getOrDefault(false)
    }

    fun read(): String? = runCatching {
        val iv = prefs.getString("iv", null) ?: return null
        val data = prefs.getString("data", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }.getOrNull()

    fun saveNamed(name: String, value: String): Boolean {
        val safeName = safeName(name)
        val clean = value.trim()
        if (clean.isBlank()) {
            clearNamed(safeName)
            return true
        }
        return runCatching {
            val keyAlias = "friday_" + safeName + "_key"
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeyForAlias(keyAlias))
            val encrypted = cipher.doFinal(clean.toByteArray(StandardCharsets.UTF_8))
            prefs.edit()
                .putString("named_" + safeName + "_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("named_" + safeName + "_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()
        }.getOrDefault(false)
    }

    fun readNamed(name: String): String? = runCatching {
        val safeName = safeName(name)
        val iv = prefs.getString("named_" + safeName + "_iv", null) ?: return null
        val data = prefs.getString("named_" + safeName + "_data", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKeyForAlias("friday_" + safeName + "_key"),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }.getOrNull()

    fun clear() {
        clearNamed("gemini")
        runCatching { prefs.edit().remove("iv").remove("data").commit() }
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
    }

    fun clearNamed(name: String) {
        val safeName = safeName(name)
        runCatching {
            prefs.edit()
                .remove("named_" + safeName + "_iv")
                .remove("named_" + safeName + "_data")
                .commit()
        }
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val keyAlias = "friday_" + safeName + "_key"
            if (ks.containsAlias(keyAlias)) ks.deleteEntry(keyAlias)
        }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun writeEncrypted(clean: String): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
    }.getOrDefault(false)

    private fun getOrCreateKeyForAlias(keyAlias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun getOrCreateKey(): SecretKey = getOrCreateKeyForAlias(alias)
}
