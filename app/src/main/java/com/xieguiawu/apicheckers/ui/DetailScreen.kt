package com.xieguiawu.apicheckers.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xieguiawu.apicheckers.AccountUi
import com.xieguiawu.apicheckers.AppViewModel
import com.xieguiawu.apicheckers.QwenUi
import com.xieguiawu.apicheckers.data.GoWindow
import com.xieguiawu.apicheckers.data.Parsers
import com.xieguiawu.apicheckers.data.QwenWindow
import com.xieguiawu.apicheckers.data.qwenRegionDisplayName
import com.xieguiawu.apicheckers.ui.theme.Bg
import com.xieguiawu.apicheckers.ui.theme.Card
import com.xieguiawu.apicheckers.ui.theme.Danger
import com.xieguiawu.apicheckers.ui.theme.Divider
import com.xieguiawu.apicheckers.ui.theme.TextMain
import com.xieguiawu.apicheckers.ui.theme.TextSub
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

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
            CountdownText(w.resetsAt, modifier = Modifier.weight(1f))
            Text("${w.percent}%", color = color, fontSize = 13.sp)
            if (rateLimited) {
                Spacer(Modifier.width(6.dp))
                Text("已限流", color = Danger, fontSize = 12.sp)
            }
        }
        UsageBar(w.percent, color)
    }
}

/** 重置倒计时：>1h「4小时20分后重置」；<1h「52分钟后重置」；已过期「即将重置」。每 30s 重算保持新鲜 */
@Composable
private fun CountdownText(resetsAt: String, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    Text(countdownText(resetsAt, now), color = TextSub, fontSize = 12.sp, modifier = modifier)
}

private fun countdownText(resetsAt: String, nowMs: Long): String {
    val instant = runCatching { Instant.parse(resetsAt) }.getOrNull() ?: return "即将重置"
    val dur = Duration.between(Instant.ofEpochMilli(nowMs), instant)
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

// ── Qwen Token Plan 详情页 ─────────────────────────────────────

@Composable
fun QwenDetailScreen(vm: AppViewModel, id: String, onBack: () -> Unit) {
    val ui by vm.uiState.collectAsState()
    val q = ui.qwenList.firstOrNull { it.account?.id == id }

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
                q?.account?.name ?: "Qwen 详情",
                color = TextMain,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.refreshQwen(id) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = TextSub)
            }
        }
        if (q == null) {
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
                item(key = "qwen-plan") { QwenTokenPlanCard(q) }
                item(key = "qwen-models") { QwenModelsCard(q) }
                q.error?.let { err -> item(key = "error") { ErrorCard(err) } }
            }
        }
    }
}

/** Token Plan 卡片：档位 + 5小时/7天 配额窗口（窗口行尾倒计时恒显，已限流徽章与倒计时并存） */
@Composable
private fun QwenTokenPlanCard(q: QwenUi) {
    val acc = q.account ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Token Plan · 订阅（${qwenRegionDisplayName(acc.region)}）",
                color = TextMain,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (acc.apiKey.isBlank()) {
                Text("未配置 API Key，去设置添加", color = TextSub, fontSize = 14.sp)
                return@Column
            }
            val tier = Parsers.planDisplayName(q.usage?.planCode.orEmpty())
            if (tier.isNotEmpty()) {
                Text("套餐 $tier", color = TextMain, fontSize = 14.sp)
            }
            val u = q.usage
            when {
                q.loading && u == null -> Text("加载中…", color = TextSub, fontSize = 14.sp)
                u == null -> {
                    if (acc.hasCookie) {
                        Text("配额窗口 暂无数据", color = TextSub, fontSize = 14.sp)
                    } else {
                        Text("配额窗口 需控制台 Cookie", color = TextSub, fontSize = 14.sp)
                    }
                }
                else -> {
                    QwenWindowRow("5小时", u.fiveHour)
                    QwenWindowRow("7天", u.weekly)
                }
            }
        }
    }
}

/**
 * Qwen 配额窗口行：与 Go 三窗口 WindowRow 同布局——
 * 行尾重置倒计时恒显；配额用尽时「已限流」徽章与倒计时并存
 * （限流时限直接可见，见 ~/prompt_boilerplates/Coding/index.md §六）。
 */
@Composable
private fun QwenWindowRow(label: String, w: QwenWindow?) {
    if (w == null) return
    val color = usageColor(w.percent, w.exhausted)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextSub, fontSize = 13.sp, modifier = Modifier.width(100.dp))
            CountdownText(w.resetsAt, modifier = Modifier.weight(1f))
            Text("${w.percent}%", color = color, fontSize = 13.sp)
            if (w.exhausted) {
                Spacer(Modifier.width(6.dp))
                Text("已限流", color = Danger, fontSize = 12.sp)
            }
        }
        UsageBar(w.percent, color)
    }
}

/** 模型清单卡片：数量 + 可换行模型列表 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QwenModelsCard(q: QwenUi) {
    val models = q.plan?.models.orEmpty()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (models.isEmpty()) "模型清单" else "模型清单（${models.size} 个）",
                color = TextMain,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (models.isEmpty()) {
                if (q.account?.apiKey?.isNotBlank() == true) {
                    Text("加载中…", color = TextSub, fontSize = 14.sp)
                } else {
                    Text("未配置 API Key", color = TextSub, fontSize = 14.sp)
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    models.forEach { id ->
                        Text(
                            id,
                            color = TextMain,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .background(Divider, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
