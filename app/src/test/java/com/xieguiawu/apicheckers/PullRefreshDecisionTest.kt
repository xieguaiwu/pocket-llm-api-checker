package com.xieguiawu.apicheckers

import com.xieguiawu.apicheckers.ui.shouldAutoRefreshWhileHeld
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRefreshDecisionTest {

    @Test
    fun `保持超过 2_5 秒且达到阈值触发刷新`() {
        assertTrue(shouldAutoRefreshWhileHeld(idleMs = 3000, pullOffset = 120f, thresholdPx = 100f, isRefreshing = false))
    }

    @Test
    fun `保持不足 2_5 秒不触发`() {
        assertFalse(shouldAutoRefreshWhileHeld(idleMs = 2000, pullOffset = 120f, thresholdPx = 100f, isRefreshing = false))
    }

    @Test
    fun `保持超过 2_5 秒但未达阈值不触发`() {
        assertFalse(shouldAutoRefreshWhileHeld(idleMs = 3000, pullOffset = 50f, thresholdPx = 100f, isRefreshing = false))
    }

    @Test
    fun `刷新中不触发`() {
        assertFalse(shouldAutoRefreshWhileHeld(idleMs = 5000, pullOffset = 200f, thresholdPx = 100f, isRefreshing = true))
    }

    @Test
    fun `边界值恰好达到保持时长触发`() {
        assertTrue(shouldAutoRefreshWhileHeld(idleMs = 2500, pullOffset = 100f, thresholdPx = 100f, isRefreshing = false))
    }

    @Test
    fun `下拉偏移为零不触发`() {
        assertFalse(shouldAutoRefreshWhileHeld(idleMs = 3000, pullOffset = 0f, thresholdPx = 100f, isRefreshing = false))
    }
}
