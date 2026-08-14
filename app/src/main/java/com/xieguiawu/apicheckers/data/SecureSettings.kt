package com.xieguiawu.apicheckers.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ── 加密抽象层 ─────────────────────────────────────────────────
// Android Keystore 的 AndroidKeyStore 提供者只在 Android 运行时可用，
// 因此把加解密逻辑拆成接口 + 纯 JVM 共享实现，单测用固定 key 验证算法本身。

interface AesGcmCipher {
    /** 加密：输出 Base64(IV || 密文 || tag) */
    fun encrypt(plain: String): String

    /** 解密：输入 Base64(IV || 密文 || tag)；密文被篡改时抛异常 */
    fun decrypt(cipherText: String): String
}

/** AES-256-GCM 共享逻辑（仅依赖 javax.crypto，纯 JVM 可测）。IV 12 字节前置，tag 128 bit */
object AesGcmCrypto {
    const val IV_SIZE = 12
    const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encryptWith(key: SecretKey, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Base64.getEncoder().encodeToString(out)
    }

    fun decryptWith(key: SecretKey, cipherText: String): String {
        val raw = Base64.getDecoder().decode(cipherText)
        if (raw.size < IV_SIZE + TAG_BITS / 8) throw IllegalArgumentException("密文格式无效")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_SIZE))
        val pt = cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE)
        return String(pt, Charsets.UTF_8)
    }
}

/** Android Keystore 实现：AES-256-GCM，key 不可导出，alias `api_checkers_master` */
class AndroidKeystoreCipher : AesGcmCipher {
    companion object {
        private const val ALIAS = "api_checkers_master"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: String): String = AesGcmCrypto.encryptWith(getOrCreateKey(), plain)

    override fun decrypt(cipherText: String): String = AesGcmCrypto.decryptWith(getOrCreateKey(), cipherText)
}

// ── 安全设置存储 ───────────────────────────────────────────────

/** 凭据安全存储：Keystore AES-GCM 加密后落 SharedPreferences；Keystore 失败时明文兜底（个人工具，避免锁死） */
object SecureSettings {
    private const val PREFS = "api_checkers_settings"
    private lateinit var prefs: SharedPreferences
    private var cipher: AesGcmCipher? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cipher = runCatching { AndroidKeystoreCipher() }.getOrNull()
    }

    /** 安全告警（加密失败 / 解密失败时置位），设置页展示提醒用户 */
    var securityWarning: String? = null
        private set

    private fun enc(v: String): String {
        if (v.isBlank()) return v
        val c = cipher ?: return v
        return runCatching { c.encrypt(v) }.getOrElse { e ->
            securityWarning = "加密失败，凭据将以明文存储（${e.message}）"
            v
        }
    }

    /**
     * 解密：格式无效（非 Base64/太短）→ 历史明文数据，原样返回；
     * 其他失败（密钥损坏等）→ 返回原文避免锁死，但置位 securityWarning 提示用户重新输入。
     */
    private fun dec(v: String): String {
        if (v.isBlank()) return ""
        val c = cipher ?: return v
        return runCatching { c.decrypt(v) }.getOrElse { e ->
            if (e is IllegalArgumentException) v
            else {
                securityWarning = "凭据解密失败（${e.message}），请重新输入"
                v
            }
        }
    }

    // DeepSeek 凭据
    fun getDeepSeekKey(): String = dec(prefs.getString("deepseek_key", "") ?: "")
    fun setDeepSeekKey(v: String) { prefs.edit().putString("deepseek_key", enc(v)).apply() }

    fun getPlatformToken(): String = dec(prefs.getString("platform_token", "") ?: "")
    fun setPlatformToken(v: String) { prefs.edit().putString("platform_token", enc(v)).apply() }

    // OpenCode 账号（整体 JSON 加密存储）
    fun getAccounts(): List<Account> {
        val raw = prefs.getString("accounts_json", "[]") ?: "[]"
        return runCatching { json.decodeFromString<List<Account>>(dec(raw)) }.getOrDefault(emptyList())
    }

    fun saveAccount(a: Account) {
        val list = getAccounts().toMutableList()
        val idx = list.indexOfFirst { it.id == a.id }
        if (idx >= 0) list[idx] = a else list.add(a)
        prefs.edit().putString("accounts_json", enc(json.encodeToString(list))).apply()
    }

    fun deleteAccount(id: String) {
        val list = getAccounts().filterNot { it.id == id }
        prefs.edit().putString("accounts_json", enc(json.encodeToString(list))).apply()
    }

    // 最近更新时间
    fun lastUpdate(key: String): Long = prefs.getLong("last_update_$key", 0L)
    fun setLastUpdate(key: String, t: Long) { prefs.edit().putLong("last_update_$key", t).apply() }
}
