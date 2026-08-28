package com.xieguiawu.apicheckers.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qwen Token Plan 解析器测试：逐条移植自 Go 姊妹项目 internal/parsers/parsers_test.go
 * 的 Qwen 段。fixture 为实测真实数据（2026-08-29 抓包）。
 */
class QwenParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    // 固定时钟（与 Go 测试一致：2026-08-29T01:00:00Z）
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 8, 29, 1, 0, 0, 0, ZoneId.of("UTC"))

    @Test
    fun `模型清单去空加排序`() {
        val ids = Parsers.parseQwenModels(fixture("qwen_models.json")).getOrThrow()
        assertEquals(listOf("deepseek-v4-flash-0731", "glm-5.2", "qwen3.8-flash", "qwen3.8-max"), ids)
    }

    @Test
    fun `空模型清单报错`() {
        val empty = Parsers.parseQwenModels("""{"data":[]}""")
        assertTrue("空模型清单应显式失败，不返回误导的空套餐", empty.isFailure)
        assertTrue(Parsers.parseQwenModels("not json").isFailure)
    }

    @Test
    fun `fixture 窗口解析`() {
        val u = Parsers.parseQwenUsage(fixture("qwen_usage.json"), now).getOrThrow()
        assertEquals(79, u.fiveHour?.percent)
        assertFalse(u.fiveHour!!.exhausted)
        assertEquals(45, u.weekly?.percent)
        assertFalse(u.weekly!!.exhausted)
        // 毫秒时间戳 → RFC3339（同一时刻，与时区无关）
        val parsed = Instant.parse(u.fiveHour!!.resetsAt)
        assertEquals(1786716480000L, parsed.toEpochMilli())
    }

    @Test
    fun `exhausted 判定`() {
        val raw = """{"data":{"DataV2":{"data":{"data":{"per5HourPercentage":1.0,"per5HourResetTime":1786716480000}}}}}"""
        val u = Parsers.parseQwenUsage(raw, ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.of("UTC"))).getOrThrow()
        assertEquals(100, u.fiveHour?.percent)
        assertTrue("比例 1.0 应判定用尽", u.fiveHour!!.exhausted)
    }

    // 防御性：若接口以百分数尺度返回（79.13），不得显示 7913% 或误判限流
    @Test
    fun `百分数尺度防御`() {
        val u = Parsers.parseQwenUsage(
            """{"per1WeekPercentage":79.13,"per1WeekResetTime":1786716480000}""",
            ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.of("UTC")),
        ).getOrThrow()
        assertEquals(79, u.weekly?.percent)
        assertFalse(u.weekly!!.exhausted)
        assertNull(u.fiveHour)
        // 单个窗口非空即成功
        assertTrue(
            Parsers.parseQwenUsage(
                """{"per1WeekPercentage":100}""",
                ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.of("UTC")),
            ).isSuccess,
        )
    }

    @Test
    fun `登录失效应映射为 Cookie 提示`() {
        val err = Parsers.parseQwenUsage(
            fixture("qwen_login_notlogined.json"),
            ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.of("UTC")),
        ).exceptionOrNull()?.message.orEmpty()
        assertTrue("登录失效应映射为 Cookie 提示，实得: $err", err.contains("Cookie"))
    }

    @Test
    fun `工作区未授权不是 Cookie 问题且保留 errorCode`() {
        val err = Parsers.parseQwenUsage(
            fixture("qwen_usage_notauthorised.json"),
            ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.of("UTC")),
        ).exceptionOrNull()?.message.orEmpty()
        assertTrue("工作区未授权不是 Cookie 问题，误报会误导用户换 Cookie: $err", !err.contains("Cookie"))
        assertTrue("错误应保留原始 errorCode: $err", err.contains("NotAuthorised"))
    }

    @Test
    fun `空信封报暂不可用以触发重试`() {
        val err = Parsers.parseQwenUsage(
            fixture("qwen_usage_empty.json"),
            ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.of("UTC")),
        ).exceptionOrNull()?.message.orEmpty()
        assertTrue("空信封应报「暂不可用」以触发重试: $err", err.contains("暂不可用"))
    }

    @Test
    fun `订阅档位归一化小写`() {
        val code = Parsers.parseQwenSubscription(fixture("qwen_subscription.json")).getOrThrow()
        assertEquals("lite", code)
        // 缺档位 best-effort 空串
        val missing = Parsers.parseQwenSubscription("""{"data":{"success":true}}""").getOrThrow()
        assertEquals("", missing)
        // 登录失效需上报（不能吞成空档位）
        val loginErr = Parsers.parseQwenSubscription(fixture("qwen_login_notlogined.json"))
        assertTrue(loginErr.isFailure)
        assertTrue(loginErr.exceptionOrNull()?.message?.contains("Cookie") == true)
    }

    @Test
    fun `planDisplayName 映射`() {
        val cases = mapOf(
            "lite" to "Lite",
            "STANDARD" to "Standard",
            "pro" to "Pro",
            "max" to "Max",
            "solo-x" to "solo-x",
            "" to "",
        )
        for ((input, want) in cases) {
            assertEquals("planDisplayName($input)", want, Parsers.planDisplayName(input))
        }
    }

    @Test
    fun `SEC_TOKEN 提取`() {
        val html = """<script>window.ALIYUN_CONSOLE_CONFIG = { SEC_TOKEN: "IlXr3OdGabc", OTHER: 1 };</script>"""
        assertEquals("IlXr3OdGabc", Parsers.extractQwenSECToken(html))
        assertEquals("", Parsers.extractQwenSECToken("<html>no token</html>"))
        // JSON 形态由仓库层单独处理（正则只认 HTML 内联形式）
        assertEquals("", Parsers.extractQwenSECToken("""{ "secToken":"from-json" }"""))
    }

    @Test
    fun `qwenPercent 边界表`() {
        val cases = listOf(
            Triple(0.0, 0, false),
            Triple(0.7913113, 79, false),
            Triple(0.999, 99, false),
            Triple(1.0, 100, true),
            Triple(1.4, 100, true),   // 比例域超额（≤2 视为比例 140%）→ 已限流
            Triple(2.5, 2, false),    // >2 才当百分数尺度（int 截断）
            Triple(79.13, 79, false),
            Triple(100.0, 100, true),
            Triple(120.0, 100, true),
            Triple(-0.1, 0, false),
        )
        for ((input, wantPct, wantLimit) in cases) {
            val (pct, limited) = Parsers.qwenPercent(input)
            assertEquals("qwenPercent($input) 百分比", wantPct, pct)
            assertEquals("qwenPercent($input) 限流", wantLimit, limited)
        }
    }

    @Test
    fun `内嵌 JSON 字符串展开查找`() {
        // BFS 展开：目标键深埋在「JSON 字符串」层里（实测信封形状）
        val raw = """{"data":{"success":true,"DataV2":{"data":{"code":"SUCCESS","data":"{\"per5HourPercentage\":0.5,\"per5HourResetTime\":\"x\"}"}}}}"""
        val u = Parsers.parseQwenUsage(raw, now).getOrThrow()
        assertEquals(50, u.fiveHour?.percent)
    }
}
