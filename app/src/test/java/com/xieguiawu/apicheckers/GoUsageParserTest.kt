package com.xieguiawu.apicheckers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Go Usage 官方 API 响应解析测试（fixture 为实测真实数据） */
class GoUsageParserTest {
    private val json = javaClass.classLoader!!.getResource("fixtures/go_usage.json")!!.readText()

    @Test
    fun `解析真实 go usage JSON`() {
        val usage = Parsers.parseGoUsage(json)
        assertEquals("ok", usage.rolling?.status)
        assertEquals(0, usage.rolling?.percent)
        assertEquals("2026-08-14T16:20:08.884Z", usage.rolling?.resetsAt)
        assertEquals(100, usage.monthly?.percent)
        assertEquals("rate-limited", usage.monthly?.status)
        assertEquals("2026-08-17T00:00:00.884Z", usage.weekly?.resetsAt)
        assertEquals(0, usage.weekly?.percent)
    }

    @Test
    fun `非法 JSON 抛异常`() {
        assertThrows(Exception::class.java) { Parsers.parseGoUsage("{bad") }
    }

    @Test
    fun `窗口缺失不崩溃`() {
        val u = Parsers.parseGoUsage("""{"usage":{"rolling":{"status":"ok","percent":1,"resetsAt":"x"}}}""")
        assertNull(u.weekly)
        assertNull(u.monthly)
        assertEquals(1, u.rolling?.percent)
    }
}
