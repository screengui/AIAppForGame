package com.gameagent.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.gameagent.core.diagnostics.DiagnosticEventSink
import com.gameagent.core.diagnostics.EventCategory
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the optional AI provider key.
 *
 * Why not just SharedPreferences: preferences are world-readable to anything with root, appear in
 * `adb backup` on some OEM builds and end up in bug reports. Instead the value is encrypted with an
 * AES-256-GCM key that lives in the Android Keystore and never leaves it, so the on-disk bytes are
 * useless without the device.
 *
 * This is *storage* hardening, not a claim of perfect security: the key is still used by the app
 * process, so a rooted or instrumented device can read it in memory. The README says so.
 */
class SecureKeyStore(
    private val context: Context,
    private val events: DiagnosticEventSink,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when a usable key is stored and can actually be decrypted. */
    fun hasAiApiKey(): Boolean = readAiApiKey()?.isNotEmpty() == true

    /** @return true when the value was encrypted and persisted. */
    fun storeAiApiKey(apiKey: String): Boolean {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            clearAiApiKey()
            return true
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                PAYLOAD_SEPARATOR +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            preferences.edit { putString(KEY_AI_API_KEY, payload) }
            events.info(EventCategory.AI, "AI provider key stored")
            true
        } catch (t: Throwable) {
            events.error(EventCategory.AI, "could not store the AI provider key", mapOf("error" to t.toString()))
            false
        }
    }

    /** @return the key, or null when absent or undecryptable (e.g. after a Keystore reset). */
    fun readAiApiKey(): String? {
        val payload = preferences.getString(KEY_AI_API_KEY, null) ?: return null
        val parts = payload.split(PAYLOAD_SEPARATOR)
        if (parts.size != 2) {
            events.warn(EventCategory.AI, "stored AI key payload is malformed; clearing it")
            clearAiApiKey()
            return null
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (t: Throwable) {
            // A Keystore key is invalidated by a factory reset, a lock-screen change on some
            // devices, or an app-data restore. Degrade to "no key" instead of crashing.
            events.warn(EventCategory.AI, "stored AI key could not be decrypted; clearing it", mapOf("error" to t.toString()))
            clearAiApiKey()
            null
        }
    }

    fun clearAiApiKey() {
        preferences.edit { remove(KEY_AI_API_KEY) }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val PREFS_NAME = "gameagent.secrets"
        const val KEY_AI_API_KEY = "ai.apiKey.encrypted"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "gameagent.ai.key.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PAYLOAD_SEPARATOR = ":"
    }
}
