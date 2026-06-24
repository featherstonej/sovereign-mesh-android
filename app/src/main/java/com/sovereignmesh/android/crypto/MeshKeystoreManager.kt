package com.sovereignmesh.android.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MeshKeystoreManager(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "SovereignMeshDbMasterKey"
        private const val PREFS_NAME = "com.sovereignmesh.android.secure_prefs"
        private const val KEY_ENCRYPTED_PASSCODE = "encrypted_db_passcode"
        private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    init {
        ensureMasterKeyExists()
    }

    /**
     * Retrieves the database passcode, generating and storing a secure random one if it doesn't exist.
     */
    fun getOrCreateDatabasePasscode(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedPasscode = prefs.getString(KEY_ENCRYPTED_PASSCODE, null)

        if (encryptedPasscode != null) {
            try {
                return decrypt(encryptedPasscode)
            } catch (e: Exception) {
                // If decryption fails (e.g. keystore cleared), we must handle it.
                // We'll generate a new one, meaning the database will be reset.
            }
        }

        // Generate a new secure random passcode (32 characters)
        val randomBytes = ByteArray(24)
        SecureRandom().nextBytes(randomBytes)
        val newPasscode = Base64.encodeToString(randomBytes, Base64.NO_WRAP)

        val encrypted = encrypt(newPasscode)
        prefs.edit().putString(KEY_ENCRYPTED_PASSCODE, encrypted).apply()

        return newPasscode
    }

    private fun ensureMasterKeyExists() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv

        // Output format: IV_Base64:Ciphertext_Base64
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ciphertextBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)

        return "$ivBase64:$ciphertextBase64"
    }

    private fun decrypt(encryptedData: String): String {
        val parts = encryptedData.split(":")
        if (parts.size != 2) throw IllegalArgumentException("Malformed encrypted passcode data")

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

        val decryptedBytes = cipher.doFinal(ciphertext)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}
