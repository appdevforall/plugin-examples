package com.itsaky.androidide.plugins.aiagentmcp.security

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "$LOG_PREFIX.SecureTokenStore"

/**
 * AES/GCM encryption for the bearer tokens of configured MCP servers, keyed by a hardware-backed
 * Android Keystore secret. Only ciphertext is written to SharedPreferences, so a copied prefs file
 * (root, `adb backup`, forensic dump) is useless without this device's Keystore.
 *
 * The [ALIAS] must stay stable across releases — a token encrypted under one alias cannot be read
 * under another — and is deliberately this plugin's own: every plugin runs in the host's process
 * and UID and therefore shares one Keystore, so a shared alias would let this plugin's recovery
 * path destroy a backend plugin's stored key as a side effect.
 */
object SecureTokenStore {

    /**
     * What was found under a preference key.
     *
     * Three outcomes rather than a nullable String: "nothing stored" and "stored but no longer
     * readable on this device" lead to opposite advice, and collapsing them is what tells a user
     * their token was refused when it was never sent.
     */
    sealed interface Stored {

        /** Nothing is stored under the key. */
        data object Absent : Stored

        /** The stored value, decrypted. */
        data class Value(val plain: String) : Stored

        /** Something is stored, but this device's Keystore can no longer open it. */
        data object Unreadable : Stored
    }

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "cotg_ai_mcp_token_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** Marks a stored value as ciphertext; anything without it is treated as legacy plaintext. */
    const val ENC_PREFIX = "enc:v1:"

    /**
     * Encrypts [plain] into a self-describing string: [ENC_PREFIX] + base64(iv | ciphertext).
     *
     * The key is not auth-bound, so a credential change does not invalidate it; an alias an OEM
     * Keystore drops anyway is regenerated once before retrying.
     *
     * @param plain the value to encrypt.
     * @return the ciphertext to store.
     * @throws GeneralSecurityException on any other Keystore or cipher failure, so the caller can
     *   tell the user instead of crashing the IDE on Save.
     */
    @Throws(GeneralSecurityException::class)
    fun encrypt(plain: String): String = try {
        encryptWith(getOrCreateKey(), plain)
    } catch (e: KeyPermanentlyInvalidatedException) {
        Log.w(TAG, "Keystore key invalidated; regenerating and retrying encrypt", e)
        deleteKey()
        encryptWith(getOrCreateKey(), plain)
    }

    /**
     * Reads a stored value back.
     * @param stored the stored string, ciphertext or legacy plaintext.
     * @return the plaintext, or null when a ciphertext value cannot be decrypted — the Keystore key
     *   was lost, and the user has to enter the token again.
     */
    fun decrypt(stored: String?): String? {
        if (stored == null) return null
        if (!stored.startsWith(ENC_PREFIX)) return stored
        return try {
            val combined = Base64.decode(stored.removePrefix(ENC_PREFIX), Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, IV_LEN)
            val ciphertext = combined.copyOfRange(IV_LEN, combined.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            // Zeroed once the String is built; see the note in encryptWith about the String.
            val plainBytes = cipher.doFinal(ciphertext)
            try {
                String(plainBytes, Charsets.UTF_8)
            } finally {
                plainBytes.fill(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt a stored MCP token", e)
            null
        }
    }

    /**
     * Stores [plain] under [key], encrypted; an empty value removes the entry instead.
     *
     * Keystore IPC plus AES/GCM, so call this off the main thread.
     *
     * @param prefs where to store it.
     * @param key the preference key.
     * @param plain the token, or empty to forget it.
     * @return true when the value was stored (or removed), false when encryption failed.
     */
    fun write(prefs: SharedPreferences?, key: String, plain: String): Boolean {
        val editor = prefs?.edit() ?: return false
        if (plain.isBlank()) {
            editor.remove(key).apply()
            return true
        }
        return try {
            editor.putString(key, encrypt(plain)).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not encrypt a token for '$key'", e)
            false
        }
    }

    /**
     * Reads [key] from [prefs], upgrading a legacy plaintext value to ciphertext in place.
     *
     * @param prefs where the value lives.
     * @param key the preference key.
     * @return what was found: nothing, the plaintext, or a value that cannot be decrypted here.
     */
    fun readAndMigrate(prefs: SharedPreferences?, key: String): Stored {
        val stored = prefs?.getString(key, null) ?: return Stored.Absent
        if (stored.startsWith(ENC_PREFIX)) {
            // A lost Keystore alias — restore onto new hardware, an OEM reset, a re-enrolled screen
            // lock — is not the same as an absent token, and must not be reported as one.
            return decrypt(stored)?.let(Stored::Value) ?: Stored.Unreadable
        }
        val plain = stored.trim()
        if (plain.isEmpty()) return Stored.Value(plain)
        try {
            prefs.edit().putString(key, encrypt(plain)).apply()
            Log.i(TAG, "Upgraded a legacy plaintext token to ciphertext")
        } catch (e: Exception) {
            Log.w(TAG, "Could not upgrade a legacy plaintext token to ciphertext", e)
        }
        return Stored.Value(plain)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun deleteKey() {
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete Keystore alias $ALIAS", e)
        }
    }

    private fun encryptWith(key: SecretKey, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        // Zeroed straight after the cipher reads it. The String itself cannot be: every API this
        // token passes through — SharedPreferences, JSONObject, setRequestProperty — takes one, so
        // a CharArray here would only move the immutable copy one frame away.
        val plainBytes = plain.toByteArray(Charsets.UTF_8)
        val ciphertext = try {
            cipher.doFinal(plainBytes)
        } finally {
            plainBytes.fill(0)
        }
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }
}
