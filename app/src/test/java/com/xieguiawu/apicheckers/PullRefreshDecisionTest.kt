package com.xieguiawu.apicheckers

import com.xieguiawu.apicheckers.ui.PullAction
import com.xieguiawu.apicheckers.ui.decidePullAction
import org.junit.Assert.assertEquals
import org.junit.Test

class PullRefreshDecisionTest {

    @Test
    fun `手指正在拖动时无动作`() {
        val a = decidePullAction(idleMs = 100, pullOffset = 120f, thresholdPx = 100f, isRefreshing = false)
        assertEquals(PullAction.None, a)
    }

    @Test
    fun `保持超过 2_5 秒且达到阈值触发刷新`() {
        val a = decidePullAction(idleMs = 3000, pullOffset = 120f, thresholdPx = 100f, isRefreshing = false)
        assertEquals(PullAction.Refresh, a)
    }

    @Test
    fun `保持超过 2_5 秒但未达阈值仅复位`() {
        val a = decidePullAction(idleMs = 3000, pullOffset = 50f, thresholdPx = 100f, isRefreshing = false)
        assertEquals(PullAction.Reset, a)
    }

    @Test
    fun `松手且达到阈值触发刷新`() {
        val a = decidePullAction(idleMs = 800, pullOffset = 120f, thresholdPx = 100f, isRefreshing = false)
        assertEquals(PullAction.Refresh, a)
    }

    @Test
    fun `松手未达阈值仅复位`() {
        val a = decidePullAction(idleMs = 800, pullOffset = 30f, thresholdPx = 100f, isRefreshing = false)
        assertEquals(PullAction.Reset, a)
    }

    @Test
    fun `刷新中一律无动作`() {
        val a = decidePullAction(idleMs = 5000, pullOffset = 200f, thresholdPx = 100f, isRefreshing = true)
        assertEquals(PullAction.None, a)
    }

    @Test
    fun `边界值恰好等于阈值判定为刷新`() {
        val a = decidePullAction(idleMs = 800, pullOffset = 100f, thresholdPx = 100f, isRefreshing = false)
        assertEquals(PullAction.Refresh, a)
    }
}
