package com.itsaky.androidide.plugins.aicore

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM encryption for sensitive settings (currently the Gemini API key),
 * keyed by a hardware-backed Android Keystore secret. Only ciphertext is
 * written to SharedPreferences, so a copied prefs file (root, `adb backup`,
 * forensic dump) is useless without this device's Keystore.
 *
 * The alias and transform below are mirrored verbatim in ai-assistant's
 * `SecureApiKeyStore` so a key written there can be decrypted here — both
 * plugins run in the host app's process (same UID) and therefore share one
 * Android Keystore. Keep the two copies in sync.
 */
object SecureApiKeyStore {
    // Drift in the constants below fails ai-core's verifySecureApiKeyStoreParity build task.
    private const val TAG = "SecureApiKeyStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "cotg_ai_gemini_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** Marks a stored value as ciphertext; anything without it is treated as legacy plaintext. */
    const val ENC_PREFIX = "enc:v1:"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
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
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Encrypt [plain] into a self-describing string: [ENC_PREFIX] + base64(iv | ciphertext).
     *
     * The key is not auth-bound, so a credential change does not invalidate it; an alias an
     * OEM Keystore drops anyway is regenerated once before retrying.
     *
     * @param plain the value to encrypt
     * @throws GeneralSecurityException on any other Keystore/cipher failure, so the caller can
     *   inform the user instead of crashing the IDE on Save
     */
    @Throws(GeneralSecurityException::class)
    fun encrypt(plain: String): String {
        return try {
            encryptWith(getOrCreateKey(), plain)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "Keystore key invalidated; regenerating and retrying encrypt", e)
            deleteKey()
            encryptWith(getOrCreateKey(), plain)
        }
    }

    /**
     * Return the plaintext for a stored value, handling both formats transparently:
     * an [ENC_PREFIX] value is decrypted; anything else is returned unchanged as
     * legacy plaintext (use [readAndMigrate] to upgrade it in place). Returns
     * null if a ciphertext value can't be decrypted — e.g. the Keystore key was
     * lost or invalidated — in which case the user must re-enter the key.
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
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt stored API key", e)
            null
        }
    }

    /**
     * Read [key] from [prefs], upgrading a legacy plaintext value to ciphertext in place.
     *
     * Keys written before this store existed are still plaintext on disk, and [decrypt] alone
     * hands them back unchanged forever — so an install that configured its key earlier would
     * never actually gain encryption. Re-encrypting on the first read closes that gap without
     * making the user re-enter the key.
     *
     * The value is trimmed on migration, so the stored, displayed and sent forms all agree.
     *
     * Keystore IPC + AES/GCM, so call this off the main thread.
     *
     * @return the trimmed plaintext value, or null when nothing is stored or decryption failed.
     */
    fun readAndMigrate(prefs: SharedPreferences?, key: String): String? {
        val stored = prefs?.getString(key, null) ?: return null
        if (stored.startsWith(ENC_PREFIX)) return decrypt(stored)
        val plain = stored.trim()
        if (plain.isEmpty()) return plain
        try {
            prefs.edit().putString(key, encrypt(plain)).apply()
            Log.i(TAG, "Upgraded legacy plaintext value for '$key' to ciphertext")
        } catch (e: Exception) {
            Log.w(TAG, "Could not upgrade legacy plaintext value for '$key' to ciphertext", e)
        }
        return plain
    }
}
