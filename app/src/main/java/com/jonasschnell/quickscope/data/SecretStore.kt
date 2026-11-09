package com.jonasschnell.quickscope.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jonasschnell.quickscope.logic.packSecret
import com.jonasschnell.quickscope.logic.unpackSecret
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Geheimnisse (Sitzungs-Token) verschluesselt ablegen: AES-GCM mit einem Schluessel im Android Keystore,
 * der Wert steht nur als Base64 aus IV und Chiffretext in den Preferences. Laesst sich nicht verschluesseln,
 * wird nichts im Klartext geschrieben (der Wert bleibt dann nur im Speicher).
 */
class SecretStore(private val prefs: SharedPreferences) {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
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

    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val packed = packSecret(cipher.iv, cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
            prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            AppLog.w("SecretStore", "Verschluesseln nicht moeglich", e)
            prefs.edit().remove(name).apply() // nie im Klartext ablegen
        }
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val (iv, ct) = unpackSecret(Base64.decode(stored, Base64.NO_WRAP)) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLog.w("SecretStore", "Entschluesseln nicht moeglich", e)
            null
        }
    }

    private companion object {
        const val ALIAS = "quickscope_secrets"
    }
}
