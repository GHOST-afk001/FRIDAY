package com.friday.assistant.ai

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore-backed store for the user-supplied Gemini API key. */
class SecureApiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("friday_secure", Context.MODE_PRIVATE)
    private val alias = "friday_gemini_key"

    /** Saves without allowing a Keystore/provider failure to crash the UI thread. */
    fun save(value: String) {
        if (value.isBlank()) {
            clear()
            return
        }
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            prefs.edit()
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply()
        }.getOrElse { error ->
            throw IllegalStateException("Unable to securely store Gemini API key", error)
        }
    }

    fun read(): String? = runCatching {
        val iv = prefs.getString("iv", null) ?: return null
        val data = prefs.getString("data", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }.getOrNull()

    fun clear() {
        prefs.edit().remove("iv").remove("data").apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        // AES-128 is broadly supported by Android Keystore providers and is sufficient for
        // encrypting this short local API credential. Do not require AES-256 provider support.
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply { init(128) }.generateKey()
    }
}
