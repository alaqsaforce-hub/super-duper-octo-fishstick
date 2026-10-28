package com.voicedock.ai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys entered by the user, encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (the key never leaves secure hardware/OS storage). Values are never logged or shown again in full.
 */
object SecretStore {
    private const val ALIAS = "voicedock_secrets_v1"
    private const val FILE = "voicedock_secrets"
    const val GEMINI = "gemini_api_key"
    const val GITHUB = "github_token"
    const val IMAP = "imap_app_password"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    fun has(ctx: Context, name: String): Boolean = prefs(ctx).contains(name)

    fun put(ctx: Context, name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        val blob = Base64.getEncoder().encodeToString(c.iv + ct)
        prefs(ctx).edit().putString(name, blob).apply()
    }

    fun get(ctx: Context, name: String): String? {
        val blob = prefs(ctx).getString(name, null) ?: return null
        return runCatching {
            val raw = Base64.getDecoder().decode(blob)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(ctx: Context, name: String) { prefs(ctx).edit().remove(name).apply() }

    /** "AIza…x7Q" — for display only. */
    fun mask(v: String?): String = when {
        v == null -> ""
        v.length <= 8 -> "••••"
        else -> v.take(4) + "…" + v.takeLast(3)
    }
}
