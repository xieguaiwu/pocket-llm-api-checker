package com.xieguiawu.apicheckers.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xieguiawu.apicheckers.ui.theme.Accent
import com.xieguiawu.apicheckers.ui.theme.TextSub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

// ── 纯决策逻辑（可 JVM 单测）─────────────────────────────────

/**
 * 保持自动刷新判定（轮询真实生产逻辑）：
 * 手指按住不动（静止 ≥ [holdMs]）且下拉偏移 ≥ 阈值且未在刷新中 → 应自动触发刷新。
 * 松手判定由 pointerInput 的 up 事件负责，不在此函数内。
 */
fun shouldAutoRefreshWhileHeld(
    idleMs: Long,
    pullOffset: Float,
    thresholdPx: Float,
    isRefreshing: Boolean,
    holdMs: Long = 2500,
): Boolean = !isRefreshing && idleMs >= holdMs && pullOffset >= thresholdPx

// ── 下拉刷新容器 ──────────────────────────────────────────────

/**
 * 下拉刷新容器：包裹可滚动内容（LazyColumn 等）。
 * - 下拉超过阈值（默认 70dp）→ 显示指示器
 * - 保持下拉不动 2.5 秒 → 自动触发刷新（无需松手）
 * - 松手（静止 500ms）且达到阈值 → 触发刷新
 * - 松手未达阈值 → 平滑复位
 */
@Composable
fun PullRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 70.dp.toPx() }
    val maxPullPx = with(density) { 140.dp.toPx() }
    var pullOffset by remember { mutableStateOf(0f) }
    var lastDragAt by remember { mutableLongStateOf(0L) }
    val refreshing by rememberUpdatedState(isRefreshing)
    val refreshAction by rememberUpdatedState(onRefresh)
    val scope = rememberCoroutineScope()

    // 下拉位移动画复位（120ms 线性衰减）
    fun resetPull() {
        scope.launch {
            val start = pullOffset
            val steps = 12
            for (i in 1..steps) {
                pullOffset = start * (1f - i / steps.toFloat())
                delay(10)
            }
            pullOffset = 0f
        }
    }

    // 松手判定（pointerInput up 事件 / onPostFling 调用）：达阈值触发刷新，未达阈值复位
    fun onDragEnded() {
        if (refreshing) return
        if (pullOffset >= thresholdPx) refreshAction()
        else if (pullOffset > 0f) resetPull()
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                // 手指下拉（dy>0）且未到最大下拉距离
                if (dy > 0 && source == NestedScrollSource.UserInput && pullOffset < maxPullPx) {
                    val consumed = min(dy, maxPullPx - pullOffset)
                    pullOffset += consumed
                    lastDragAt = android.os.SystemClock.uptimeMillis()
                    return Offset(0f, consumed)
                }
                // 上滑且指示器未复位：消费上滑、立即复位指示器（内容不跳动）
                if (dy < 0 && pullOffset > 0f) {
                    val consumed = min(-dy, pullOffset)
                    pullOffset -= consumed
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // 有速度的松手（fling 结束）：立即判定刷新/复位
                if (pullOffset > 0f) onDragEnded()
                return Velocity.Zero
            }
        }
    }

    // 轮询监控：检测松手 / 保持自动刷新
    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            if (pullOffset <= 0f) continue
            val idle = android.os.SystemClock.uptimeMillis() - lastDragAt
            // 保持触发：手指按住不动 ≥ 2.5s 且达阈值（无需松手）。
            // 松手判定由下方 pointerInput 的 up 事件即时处理，此处不重复。
            if (shouldAutoRefreshWhileHeld(idle, pullOffset, thresholdPx, refreshing)) {
                refreshAction()
            }
        }
    }

    // 刷新完成后自动复位
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing && pullOffset > 0f) {
            delay(300) // 等状态渲染稳定
            if (pullOffset > 0f) resetPull()
        }
    }

    Box(
        modifier = modifier
            .nestedScroll(connection)
            .pointerInput(Unit) {
                // 松手检测：观察手指抬起（不消费事件，不影响 LazyColumn 滚动）
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.all { it.changedToUp() }) {
                            if (pullOffset > 0f) onDragEnded()
                            break
                        }
                    } while (true)
                }
            },
    ) {
        content()

        val show = pullOffset > 0f || refreshing
        AnimatedVisibility(
            visible = show,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                modifier = Modifier
                    .offset(y = with(density) { (pullOffset * 0.5f).toDp() })
                    .padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = Accent,
                    strokeWidth = 2.dp,
                    progress = { if (refreshing) 0.75f else 0f },
                )
                Text(
                    if (refreshing) "刷新中…" else "下拉刷新 · 保持 2.5 秒自动刷新",
                    color = TextSub,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
