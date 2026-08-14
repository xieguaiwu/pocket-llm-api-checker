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

class DeepSeekAggregationTest {
    @Test
    fun `30 天连续消费聚合不截断`() {
        val ref = java.time.LocalDate.of(2026, 8, 14)
        // 30 天每天 1 元（含 7 月与 8 月跨月）
        val map = (0 until 30).associate { ref.minusDays(it.toLong()).toString() to 1.0 }
        val c = Parsers.aggregateCost(map, ref)
        assertEquals(1.0, c.today, 1e-6)
        assertEquals(7.0, c.last7d, 1e-6)
        assertEquals(30.0, c.last30d, 1e-6)
        assertEquals("days 不应被截断到 7 条", 30L, c.days.size.toLong())
    }

    @Test
    fun `parseDeepSeekCost 返回全部天数`() {
        // 构造 20 天数据：7 月 26 日 - 8 月 14 日（跨月 JSON）
        val sb = StringBuilder("""{"code":0,"data":{"biz_data":[{"days":[""")
        val ref = java.time.LocalDate.of(2026, 8, 14)
        for (i in 19 downTo 0) {
            val d = ref.minusDays(i.toLong())
            if (i != 19) sb.append(",")
            sb.append("""{"date":"${d}","data":[{"model":"deepseek-chat","usage":[{"type":"input","amount":0.5}]}]}""")
        }
        sb.append("""]}]}}""")
        val c = Parsers.parseDeepSeekCost(sb.toString(), ref).getOrThrow()
        assertEquals("解析器应保留全部天数", 20L, c.days.size.toLong())
        assertEquals("每天 0.5 元 × 20 天", 10.0, c.last30d, 1e-6)
        assertEquals("每天 0.5 元 × 7 天", 3.5, c.last7d, 1e-6)
    }

    @Test
    fun `空消费返回空聚合不崩溃`() {
        val c = Parsers.aggregateCost(emptyMap(), java.time.LocalDate.of(2026, 8, 14))
        assertEquals(0.0, c.today, 1e-6)
        assertEquals(0.0, c.last30d, 1e-6)
        assertEquals(0, c.days.size)
    }
}
