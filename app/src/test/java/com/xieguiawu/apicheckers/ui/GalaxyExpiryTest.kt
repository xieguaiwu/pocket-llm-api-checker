package com.xieguiawu.apicheckers.ui

import com.xieguiawu.apicheckers.data.GalaxyInstance
import com.xieguiawu.apicheckers.ui.theme.Accent
import com.xieguiawu.apicheckers.ui.theme.Danger
import com.xieguiawu.apicheckers.ui.theme.TextSub
import com.xieguiawu.apicheckers.ui.theme.Warn
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 智星云到期文案/紧急色/最近到期纯函数测试（对应 Go render.galaxySpanShort /
 * galaxyExpiryText / galaxyNextExpiry）。时间信息恒显：过期不抹掉时间，
 * 而是「已到期 N」——与 §六「限流徽章与重置倒计时并存」同口径。
 */
class GalaxyExpiryTest {

    private val nowMs: Long = Instant.parse("2026-08-29T10:00:00Z").toEpochMilli()

    private fun dueIn(millis: Long): String =
        Instant.ofEpochMilli(nowMs + millis).toString()

    // ── 时长短语 ───────────────────────────────────────────

    @Test
    fun `时长短语分级`() {
        assertEquals("不足1分", galaxySpanShort(30_000))
        assertEquals("33分", galaxySpanShort(33 * 60_000L))
        assertEquals("1小时", galaxySpanShort(60 * 60_000L))
        assertEquals("1小时20分", galaxySpanShort(80 * 60_000L))
        assertEquals("2天", galaxySpanShort(48 * 60 * 60_000L))
        assertEquals("3天5小时", galaxySpanShort(77 * 60 * 60_000L))
    }

    // ── 到期文案 ───────────────────────────────────────────

    @Test
    fun `到期文案与颜色`() {
        assertEquals("无到期信息", galaxyExpiryText("", nowMs))
        assertEquals("到期时间未知", galaxyExpiryText("not-a-time", nowMs))
        // 已到期：保留时间信息，红色
        assertEquals("已到期 33分", galaxyExpiryText(dueIn(-33 * 60_000L), nowMs))
        assertEquals(Danger, galaxyExpiryColor(dueIn(-33 * 60_000L), nowMs))
        // 30 分钟内：红
        assertEquals("20分后到期", galaxyExpiryText(dueIn(20 * 60_000L), nowMs))
        assertEquals(Danger, galaxyExpiryColor(dueIn(20 * 60_000L), nowMs))
        // 2 小时内：黄
        assertEquals("1小时30分后到期", galaxyExpiryText(dueIn(90 * 60_000L), nowMs))
        assertEquals(Warn, galaxyExpiryColor(dueIn(90 * 60_000L), nowMs))
        // 其余：蓝
        assertEquals("10小时后到期", galaxyExpiryText(dueIn(10 * 60 * 60_000L), nowMs))
        assertEquals(Accent, galaxyExpiryColor(dueIn(10 * 60 * 60_000L), nowMs))
        // 无时间信息：灰
        assertEquals(TextSub, galaxyExpiryColor("", nowMs))
    }

    // ── 最近到期 ───────────────────────────────────────────

    @Test
    fun `最近到期取活跃实例里最早到期`() {
        val dueSoon = dueIn(30 * 60_000L)
        val dueLate = dueIn(5 * 60 * 60_000L)
        val instances = listOf(
            instance(status = 1, dueAt = dueLate),
            instance(status = 4, dueAt = dueSoon), // 启动中，活跃
            instance(status = 0, dueAt = dueIn(60_000L)), // 已结束：终态，跳过
            instance(status = 8, dueAt = ""),            // 无到期信息，跳过
        )
        assertEquals(dueSoon, galaxyNextExpiry(instances))
    }

    @Test
    fun `最近到期无可选时返回空`() {
        assertNull(galaxyNextExpiry(emptyList()))
        assertNull(galaxyNextExpiry(listOf(instance(status = 0, dueAt = dueIn(60_000L)))))
    }

    private fun instance(status: Int, dueAt: String) = GalaxyInstance(
        name = "n", status = status, statusText = galaxyStatusTextFor(status), dueAt = dueAt,
    )

    /** 测试内独立映射状态码 → 文案（不复用被测的 galaxyStatusText，避免自证）。 */
    private fun galaxyStatusTextFor(status: Int): String = when (status) {
        1 -> "运行中"
        4 -> "启动中"
        else -> "已结束"
    }
}
