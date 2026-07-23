package com.itsaky.androidide.plugins.aiassistant.security

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
 * The alias and transform below are mirrored verbatim in ai-core's
 * `SecureApiKeyStore` so a key written here can be decrypted there — both
 * plugins run in the host app's process (same UID) and therefore share one
 * Android Keystore. Keep the two copies in sync.
 */
object SecureApiKeyStore {
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
     * If the Keystore key has been permanently invalidated (e.g. the lock-screen credentials
     * changed, or the entry is corrupt) the stale alias is dropped and a fresh key generated
     * once before retrying. Any other Keystore/cipher failure is surfaced as a
     * [GeneralSecurityException] so the caller can inform the user instead of crashing — the
     * previous version let these propagate uncaught and take the IDE down on Save.
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
     * legacy plaintext (it gets migrated to ciphertext on the next save). Returns
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
}
