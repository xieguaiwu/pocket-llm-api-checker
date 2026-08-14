package com.xieguiawu.apicheckers.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DeepSeek 余额与消费明细解析测试 */
class DeepSeekParserTest {
    @Test
    fun `解析余额`() {
        val json = javaClass.classLoader!!.getResource("fixtures/deepseek_balance.json")!!.readText()
        val b = Parsers.parseDeepSeekBalance(json).getOrThrow()
        assertTrue(b.isAvailable)
        assertEquals("CNY", b.infos[0].currency)
        assertEquals(120.0, b.infos[0].totalBalance, 1e-6)
        assertEquals(0.0, b.infos[0].grantedBalance, 1e-6)
        assertEquals(120.0, b.infos[0].toppedUpBalance, 1e-6)
    }

    @Test
    fun `解析消费明细`() {
        val json = javaClass.classLoader!!.getResource("fixtures/deepseek_cost.json")!!.readText()
        // fixture 日期固定为 2026-08-14/13，用固定参考日断言，保证测试在任何日期运行都稳定
        val c = Parsers.parseDeepSeekCost(json, LocalDate.of(2026, 8, 14)).getOrThrow()
        assertEquals(4.5, c.today, 1e-6)   // 1.5+2.5+0.5
        assertEquals(4.8, c.last7d, 1e-6)  // +0.3
        assertEquals(4.8, c.last30d, 1e-6)
        assertEquals(2, c.days.size)
        assertEquals("2026-08-14", c.days[0].date)
        assertEquals(4.5, c.days[0].total, 1e-6)
        assertEquals("2026-08-13", c.days[1].date)
        assertEquals(0.3, c.days[1].total, 1e-6)
    }

    @Test
    fun `code 40003 报 token 失效`() {
        val r = Parsers.parseDeepSeekCost("""{"code":40003}""")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("失效"))
    }

    @Test
    fun `余额响应不可用时 isAvailable 为 false`() {
        val b = Parsers.parseDeepSeekBalance("""{"is_available":false,"balance_infos":[]}""").getOrThrow()
        assertTrue(!b.isAvailable)
        assertTrue(b.infos.isEmpty())
    }
}
