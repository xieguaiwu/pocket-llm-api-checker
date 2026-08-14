package com.xieguiawu.apicheckers

import com.xieguiawu.apicheckers.data.AesGcmCipher
import com.xieguiawu.apicheckers.data.AesGcmCrypto
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 加密往返测试：用固定 key 的 FakeCipher（javax.crypto，与 AndroidKeystoreCipher
 * 走同一 AesGcmCrypto 代码路径）验证「加密→解密=原文」与「篡改密文抛异常」。
 * 不触碰 Android Keystore API（AndroidKeystoreCipher 的真机集成留待安装冒烟验证）。
 */
class CryptoTest {

    /** 固定 256-bit key 的模拟实现：逻辑与生产 AndroidKeystoreCipher 一致，仅 key 来源不同 */
    private class FakeCipher(key: SecretKey) : AesGcmCipher {
        constructor() : this(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))

        private val key = key

        override fun encrypt(plain: String): String = AesGcmCrypto.encryptWith(key, plain)

        override fun decrypt(cipherText: String): String = AesGcmCrypto.decryptWith(key, cipherText)
    }

    @Test
    fun `加密解密往返`() {
        val cipher = FakeCipher()
        // 两个实例共享同一固定 key，模拟「重启后同一 Keystore key 解密」
        val cipher2 = FakeCipher()
        val original = "sk-test-12345-abcdef"
        val enc = cipher.encrypt(original)
        assertNotEquals(original, enc) // 密文不等于明文
        assertTrue(enc.isNotBlank())
        assertEquals(original, cipher.decrypt(enc))
        assertEquals(original, cipher2.decrypt(enc)) // 跨实例解密（同 key）
    }

    @Test
    fun `中文与特殊字符往返`() {
        val cipher = FakeCipher()
        val original = "账号① Fe26.2***abc cookie-value=xyz&中文字符"
        val enc = cipher.encrypt(original)
        assertEquals(original, cipher.decrypt(enc))
    }

    @Test
    fun `篡改密文抛异常`() {
        val cipher = FakeCipher()
        val enc = cipher.encrypt("sensitive-data")
        val tampered = StringBuilder(enc).apply {
            val idx = length / 2
            setCharAt(idx, if (this[idx] == 'A') 'B' else 'A')
        }.toString()
        assertThrows(Exception::class.java) { cipher.decrypt(tampered) }
    }

    @Test
    fun `错误密文抛异常`() {
        val cipher = FakeCipher()
        assertThrows(Exception::class.java) { cipher.decrypt("not-a-valid-base64!!") }
        assertThrows(Exception::class.java) { cipher.decrypt("") }
    }
}
