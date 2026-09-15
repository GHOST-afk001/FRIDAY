package com.friday.assistant.security

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local encrypted storage for the optional speaker-verification profile.
 * The profile never leaves the device and the encryption key is kept in Android Keystore.
 */
class SpeakerProfileStore(context: Context) {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "friday_speaker_profile_key"
        private const val PREFS = "friday_speaker_profile"
        private const val DATA = "encrypted_profile"
        private const val IV_LENGTH = 12
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(profile: FloatArray): Boolean = runCatching {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val bytes = ByteBuffer.allocate(4 + profile.size * 4).apply {
            putInt(profile.size)
            profile.forEach { putFloat(it) }
        }.array()
        val encrypted = cipher.doFinal(bytes)
        val packed = ByteBuffer.allocate(IV_LENGTH + encrypted.size).apply {
            put(cipher.iv)
            put(encrypted)
        }.array()
        prefs.edit().putString(DATA, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
        true
    }.getOrDefault(false)

    fun load(): FloatArray? = runCatching {
        val encoded = prefs.getString(DATA, null) ?: return@runCatching null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        if (packed.size <= IV_LENGTH) return@runCatching null
        val iv = packed.copyOfRange(0, IV_LENGTH)
        val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(ciphertext)
        val buffer = ByteBuffer.wrap(plain)
        val count = buffer.int
        if (count <= 0 || count > 10_000 || plain.size != 4 + count * 4) return@runCatching null
        FloatArray(count) { buffer.float }.takeIf { it.all(Float::isFinite) }
    }.getOrNull()

    fun clear() {
        prefs.edit().remove(DATA).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance("AES", KEYSTORE)
        generator.init(256)
        return generator.generateKey()
    }
}
