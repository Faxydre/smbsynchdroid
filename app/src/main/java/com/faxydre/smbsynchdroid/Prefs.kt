package com.faxydre.smbsynchdroid

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Rangement des photos sur le NAS. */
enum class Layout(val label: String) {
    FLAT("Tout dans un seul dossier"),
    YEAR("Année  (2026\\)"),
    YEAR_MONTH("Année / Mois  (2026\\11\\)"),
    YEAR_MONTH_DAY("Année / Mois / Jour  (2026\\11\\23\\)"),
}

data class SmbSettings(
    val host: String,
    val share: String,
    val remoteDir: String,
    val username: String,
    val password: String,
    val domain: String,
    val sourceDir: String,
    val layout: Layout,
    val wifiOnly: Boolean,
    val enabled: Boolean,
) {
    val isConfigured: Boolean get() = host.isNotBlank() && share.isNotBlank()
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): SmbSettings = SmbSettings(
        host = sp.getString("host", "")!!,
        share = sp.getString("share", "")!!,
        remoteDir = sp.getString("remoteDir", "")!!,
        username = sp.getString("username", "")!!,
        password = Crypto.decrypt(sp.getString("password", "")!!),
        domain = sp.getString("domain", "")!!,
        sourceDir = sp.getString("sourceDir", DEFAULT_SOURCE)!!,
        layout = runCatching { Layout.valueOf(sp.getString("layout", Layout.YEAR_MONTH.name)!!) }
            .getOrDefault(Layout.YEAR_MONTH),
        wifiOnly = sp.getBoolean("wifiOnly", true),
        enabled = sp.getBoolean("enabled", true),
    )

    fun save(s: SmbSettings) {
        sp.edit()
            .putString("host", s.host)
            .putString("share", s.share)
            .putString("remoteDir", s.remoteDir)
            .putString("username", s.username)
            .putString("password", Crypto.encrypt(s.password))
            .putString("domain", s.domain)
            .putString("sourceDir", s.sourceDir)
            .putString("layout", s.layout.name)
            .putBoolean("wifiOnly", s.wifiOnly)
            .putBoolean("enabled", s.enabled)
            .apply()
    }

    /** DATE_ADDED (secondes) de la dernière photo traitée ; -1 = jamais initialisé. */
    var watermark: Long
        get() = sp.getLong("watermark", -1L)
        set(v) {
            sp.edit().putLong("watermark", v).apply()
        }

    @Synchronized
    fun advanceWatermark(v: Long) {
        if (v > watermark) watermark = v
    }

    fun recordRun(message: String, ok: Boolean) {
        sp.edit()
            .putLong("lastRun", System.currentTimeMillis())
            .putString("lastMsg", message)
            .putBoolean("lastOk", ok)
            .apply()
    }

    val lastRun: Long get() = sp.getLong("lastRun", 0L)
    val lastMessage: String get() = sp.getString("lastMsg", "")!!
    val lastOk: Boolean get() = sp.getBoolean("lastOk", true)

    fun addSent(n: Int) {
        sp.edit().putLong("totalSent", totalSent + n).apply()
    }

    val totalSent: Long get() = sp.getLong("totalSent", 0L)

    companion object {
        const val DEFAULT_SOURCE = "DCIM/Camera"
    }
}

/** Chiffrement du mot de passe avec une clé du Keystore Android (jamais exportable). */
object Crypto {
    private const val ALIAS = "smbsynchdroid_password"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val (iv, ct) = stored.split(":", limit = 2)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
