package com.xieguiawu.apicheckers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zen billing 页面 SSR HTML 解析测试（fixture 来自 pi-go-bars testdata，MIT） */
class ZenBillingParserTest {
    private val html = javaClass.classLoader!!.getResource("fixtures/billing.html")!!.readText()

    @Test
    fun `解析真实 billing 页面`() {
        val b = Parsers.parseZenBilling(html).getOrThrow()
        // fixture 实测值：balance:1999960750 → $19.9996075；monthlyUsage:39250 → $0.0003925；monthlyLimit:50
        assertEquals(19.9996075, b.balanceUsd, 1e-6)
        assertEquals(0.0003925, b.monthlyUsageUsd, 1e-9)
        assertEquals(50.0, b.monthlyLimitUsd, 1e-6)
        // reload:!0 / reloadAmount:10 / reloadTrigger:5
        assertTrue(b.autoReload)
        assertEquals(10.0, b.reloadAmountUsd, 1e-6)
        assertEquals(5.0, b.reloadTriggerUsd, 1e-6)
    }

    @Test
    fun `无 customerID 返回会话过期错误`() {
        val r = Parsers.parseZenBilling("<html>login page</html>")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("会话"))
    }

    @Test
    fun `字段缺失容忍`() {
        val html2 = html.replace("monthlyUsage:39250", "monthlyUsage:null")
        val b = Parsers.parseZenBilling(html2).getOrThrow()
        assertEquals(0.0, b.monthlyUsageUsd, 1e-9)
        assertEquals(19.9996075, b.balanceUsd, 1e-6)
    }

    @Test
    fun `核心字段全缺失报页面结构变化`() {
        val html2 = html
            .replace("balance:1999960750", "balance:null")
            .replace("monthlyUsage:39250", "monthlyUsage:null")
            .replace("monthlyLimit:50", "monthlyLimit:null")
        val r = Parsers.parseZenBilling(html2)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("结构"))
    }
}
