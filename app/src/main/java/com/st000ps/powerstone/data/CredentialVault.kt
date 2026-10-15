package com.st000ps.powerstone.data

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
 * "Stay signed in": the account's email and password, kept on this phone only, encrypted with an
 * AES key that lives in the Android Keystore (it never leaves the secure hardware and can't be
 * copied off the phone). Used to sign back in quietly whenever the site's session ends.
 * Cleared on sign-out. Nothing here is in the app's code or its backups.
 */
class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun save(email: String, password: String) {
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val sealed = cipher.doFinal("$email\n$password".toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("data", Base64.encodeToString(sealed, Base64.NO_WRAP))
                .apply()
        }
    }

    /** The stored email and password, or null (none saved, or the key was lost with a phone reset). */
    fun load(): Pair<String, String>? = runCatching {
        val iv = Base64.decode(prefs.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val data = Base64.decode(prefs.getString("data", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val text = String(cipher.doFinal(data), Charsets.UTF_8)
        text.substringBefore('\n') to text.substringAfter('\n')
    }.getOrNull()

    val hasCredentials get() = prefs.contains("data")

    fun clear() {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private companion object {
        const val ALIAS = "powerstone_vault"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
