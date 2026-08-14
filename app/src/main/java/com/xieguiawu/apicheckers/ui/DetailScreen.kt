package com.xieguiawu.apicheckers.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xieguiawu.apicheckers.AccountUi
import com.xieguiawu.apicheckers.AppViewModel
import com.xieguiawu.apicheckers.data.GoWindow
import com.xieguiawu.apicheckers.ui.theme.Bg
import com.xieguiawu.apicheckers.ui.theme.Card
import com.xieguiawu.apicheckers.ui.theme.Danger
import com.xieguiawu.apicheckers.ui.theme.TextMain
import com.xieguiawu.apicheckers.ui.theme.TextSub
import java.time.Duration
import java.time.Instant

// ── 账号详情页 ─────────────────────────────────────────────────

@Composable
fun DetailScreen(vm: AppViewModel, id: String, onBack: () -> Unit) {
    val ui by vm.uiState.collectAsState()
    val accUi = ui.accounts.firstOrNull { it.account.id == id }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp),
    ) {
        // 顶栏：返回 + 账号名 + 手动刷新
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextMain)
            }
            Text(
                accUi?.account?.name ?: "账号详情",
                color = TextMain,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.refreshAccount(id) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = TextSub)
            }
        }
        if (accUi == null) {
            Text(
                "账号不存在或已被删除",
                color = TextSub,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "go") { GoPlanCard(accUi) }
                item(key = "zen") { ZenPlanCard(accUi) }
                accUi.error?.let { err -> item(key = "error") { ErrorCard(err) } }
            }
        }
    }
}

/** Go Plan 卡片：三个窗口各自用量条 + 百分比 + 重置倒计时 */
@Composable
private fun GoPlanCard(acc: AccountUi) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Go Plan · 订阅", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val go = acc.goUsage
            when {
                acc.loading && go == null -> Text("加载中…", color = TextSub, fontSize = 14.sp)
                go == null -> Text("暂无数据", color = TextSub, fontSize = 14.sp)
                else -> {
                    WindowRow("Rolling 5h", go.rolling)
                    WindowRow("Weekly 7d", go.weekly)
                    WindowRow("Monthly 30d", go.monthly)
                }
            }
        }
    }
}

@Composable
private fun WindowRow(label: String, w: GoWindow?) {
    if (w == null) return
    val rateLimited = w.status == "rate-limited"
    val color = usageColor(w.percent, rateLimited)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextSub, fontSize = 13.sp, modifier = Modifier.width(100.dp))
            Text(countdownText(w.resetsAt), color = TextSub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${w.percent}%", color = color, fontSize = 13.sp)
            if (rateLimited) {
                Spacer(Modifier.width(6.dp))
                Text("已限流", color = Danger, fontSize = 12.sp)
            }
        }
        UsageBar(w.percent, color)
    }
}

/** 重置倒计时：>1h「4小时20分后重置」；<1h「52分钟后重置」；已过期「即将重置」 */
private fun countdownText(resetsAt: String): String {
    val instant = runCatching { Instant.parse(resetsAt) }.getOrNull() ?: return "即将重置"
    val dur = Duration.between(Instant.now(), instant)
    if (dur.isNegative || dur.isZero) return "即将重置"
    val mins = dur.toMinutes()
    return when {
        mins < 1 -> "即将重置"
        mins < 60 -> "${mins}分钟后重置"
        else -> {
            val h = mins / 60
            val m = mins % 60
            if (m == 0L) "${h}小时后重置" else "${h}小时${m}分后重置"
        }
    }
}

/** Zen Plan 卡片：余额大字 + 本月用量条 + 自动充值状态 */
@Composable
private fun ZenPlanCard(acc: AccountUi) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Zen Plan · 按量", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (!acc.account.hasZen) {
                Text(
                    "未配置 Workspace ID / Cookie，去设置添加以查看 Zen",
                    color = TextSub,
                    fontSize = 14.sp,
                )
                return@Column
            }
            val z = acc.zenBilling
            when {
                acc.loading && z == null -> Text("加载中…", color = TextSub, fontSize = 14.sp)
                z == null -> Text("暂无数据", color = TextSub, fontSize = 14.sp)
                else -> {
                    Text("$${fmt(z.balanceUsd)}", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "本月 $${fmt(z.monthlyUsageUsd)} / $${fmt(z.monthlyLimitUsd)}",
                        color = TextSub,
                        fontSize = 13.sp,
                    )
                    if (z.monthlyLimitUsd > 0) {
                        val pct = (z.monthlyUsageUsd / z.monthlyLimitUsd * 100).toInt()
                        UsageBar(pct, usageColor(pct))
                    }
                    Text(
                        if (z.autoReload)
                            "自动充值 开 · 低于 $${fmt(z.reloadTriggerUsd)} 充 $${fmt(z.reloadAmountUsd)}"
                        else "自动充值 关",
                        color = TextSub,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

/** 错误提示卡：error 非空时显示，否则整卡隐藏 */
@Composable
private fun ErrorCard(msg: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(msg, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
    }
}
