package com.xieguiawu.apicheckers.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QwenAccount 序列化往返 + 区域归一化测试（对应 Go models 与 account 存储契约） */
class QwenAccountTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `序列化往返保留全部字段`() {
        val acc = QwenAccount(
            id = "id-1",
            name = "测试账号",
            apiKey = "sk-sp-test-123",
            consoleCookie = "Cookie: a=1; b=2",
            region = "ap-southeast-1",
        )
        val encoded = json.encodeToString(acc)
        val decoded = json.decodeFromString<QwenAccount>(encoded)
        assertEquals(acc, decoded)
        // 存储字段名与 Go 侧 JSON tag 一致（camelCase）
        assertTrue(encoded.contains("\"apiKey\""))
        assertTrue(encoded.contains("\"consoleCookie\""))
    }

    @Test
    fun `旧版本缺省字段反序列化不崩溃`() {
        // 旧版本没有 region/consoleCookie 字段 → 回落默认值（中国大陆 + 无 Cookie）
        val decoded = json.decodeFromString<QwenAccount>("""{"id":"a","name":"n","apiKey":"k"}""")
        assertEquals(RegionQwenCN, decoded.region)
        assertFalse(decoded.hasCookie)
    }

    @Test
    fun `hasCookie 空白语义`() {
        assertFalse(QwenAccount("a", "n", "k", consoleCookie = "").hasCookie)
        assertFalse(QwenAccount("a", "n", "k", consoleCookie = "   ").hasCookie)
        assertTrue(QwenAccount("a", "n", "k", consoleCookie = "cna=x").hasCookie)
    }

    @Test
    fun `区域归一化别名`() {
        val cnAliases = listOf("", "cn", "cn-beijing", "CN-BEIJING", "domestic", "beijing", "china", " Beijing ")
        for (s in cnAliases) {
            assertEquals("normalizeQwenRegion($s)", RegionQwenCN, normalizeQwenRegion(s).getOrThrow())
        }
        val intlAliases = listOf("intl", "INTL", "international", "singapore", "ap-southeast-1", "sg")
        for (s in intlAliases) {
            assertEquals("normalizeQwenRegion($s)", RegionQwenIntl, normalizeQwenRegion(s).getOrThrow())
        }
    }

    @Test
    fun `未知区域报错`() {
        val r = normalizeQwenRegion("mars")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()?.message?.contains("Qwen 区域不支持") == true)
    }

    @Test
    fun `账号区域回落与展示名`() {
        // 非法/空值回落中国大陆
        assertEquals(RegionQwenCN, QwenAccount("a", "n", "k", region = "mars").qwenRegion)
        assertEquals(RegionQwenCN, QwenAccount("a", "n", "k", region = "").qwenRegion)
        assertEquals(RegionQwenIntl, QwenAccount("a", "n", "k", region = "sg").qwenRegion)
        // 展示名
        assertEquals("中国大陆（北京）", qwenRegionDisplayName(""))
        assertEquals("中国大陆（北京）", qwenRegionDisplayName("cn-beijing"))
        assertEquals("中国大陆（北京）", qwenRegionDisplayName("mars")) // 未知回落
        assertEquals("国际（新加坡）", qwenRegionDisplayName("intl"))
    }
}
