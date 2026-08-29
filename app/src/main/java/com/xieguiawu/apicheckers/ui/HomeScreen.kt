package com.xieguiawu.apicheckers.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xieguiawu.apicheckers.AccountUi
import com.xieguiawu.apicheckers.AppViewModel
import com.xieguiawu.apicheckers.DeepSeekUi
import com.xieguiawu.apicheckers.GalaxyUi
import com.xieguiawu.apicheckers.QwenUi
import com.xieguiawu.apicheckers.data.GalaxyInstance
import com.xieguiawu.apicheckers.data.Parsers
import com.xieguiawu.apicheckers.data.galaxyStatusActive
import com.xieguiawu.apicheckers.ui.theme.Accent
import com.xieguiawu.apicheckers.ui.theme.Bg
import com.xieguiawu.apicheckers.ui.theme.Card
import com.xieguiawu.apicheckers.ui.theme.Danger
import com.xieguiawu.apicheckers.ui.theme.Divider
import com.xieguiawu.apicheckers.ui.theme.TextMain
import com.xieguiawu.apicheckers.ui.theme.TextSub
import com.xieguiawu.apicheckers.ui.theme.Warn
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

// ── 共享工具（HomeScreen / DetailScreen 共用） ─────────────────

/** 用量颜色规则：<70 蓝；70-89 黄；≥90 红；限流强制红 */
fun usageColor(percent: Int, rateLimited: Boolean = false): Color = when {
    rateLimited -> Danger
    percent >= 90 -> Danger
    percent >= 70 -> Warn
    else -> Accent
}

/** 极简用量条：Accent 进度 + Divider 轨道 */
@Composable
fun UsageBar(percent: Int, color: Color, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { percent.coerceIn(0, 100) / 100f },
        color = color,
        trackColor = Divider,
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
    )
}

/** 金额格式化：两位小数（Locale.US 保证小数点） */
fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

/** 货币符号映射：CNY→¥、USD→$、EUR→€，其他原样显示代码 */
fun currencySymbol(code: String): String = when (code.uppercase(Locale.US)) {
    "CNY", "RMB" -> "¥"
    "USD" -> "$"
    "EUR" -> "€"
    else -> "$code "
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** 时间戳 → 「HH:mm」本地时间 */
fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeFmt)

// ── 智星云到期倒计时（时间信息恒显，与 Go render.galaxySpanShort/galaxyExpiryText 同口径） ──

/** 时长短语：不足1分 / N分 / N小时M分 / N天M小时 */
fun galaxySpanShort(millis: Long): String {
    val m = millis / 60_000
    return when {
        millis < 60_000 -> "不足1分"
        m < 60 -> "${m}分"
        m < 48 * 60 -> {
            val h = m / 60
            val mm = m % 60
            if (mm == 0L) "${h}小时" else "${h}小时${mm}分"
        }
        else -> {
            val hTotal = m / 60
            val d = hTotal / 24
            val h = hTotal % 24
            if (h == 0L) "${d}天" else "${d}天${h}小时"
        }
    }
}

/**
 * 到期文案（纯函数，可单测）。过期不抹掉时间，而是「已到期 N」——
 * 时间信息恒显（§六「限流徽章与重置倒计时并存」同口径）。
 */
fun galaxyExpiryText(dueAt: String, nowMs: Long): String {
    if (dueAt.isBlank()) return "无到期信息"
    val t = runCatching { Instant.parse(dueAt) }.getOrNull() ?: return "到期时间未知"
    val d = Duration.between(Instant.ofEpochMilli(nowMs), t)
    return when {
        d.isNegative || d.isZero -> "已到期 " + galaxySpanShort(-d.toMillis())
        else -> galaxySpanShort(d.toMillis()) + "后到期"
    }
}

/** 到期紧急色：已过期/不足30分 红；不足2小时 黄；其余 蓝；无时间信息 灰。 */
fun galaxyExpiryColor(dueAt: String, nowMs: Long): Color {
    if (dueAt.isBlank()) return TextSub
    val t = runCatching { Instant.parse(dueAt) }.getOrNull() ?: return TextSub
    val d = Duration.between(Instant.ofEpochMilli(nowMs), t)
    return when {
        d.isNegative || d.isZero -> Danger
        d.toMinutes() < 30 -> Danger
        d.toMinutes() < 120 -> Warn
        else -> Accent
    }
}

/** 活跃实例里最早到期的那一个的到期时间（对应 Go galaxyNextExpiry）。 */
fun galaxyNextExpiry(instances: List<GalaxyInstance>): String? {
    var best: String? = null
    var bestT: Instant? = null
    for (it in instances) {
        if (!galaxyStatusActive(it.status) || it.dueAt.isBlank()) continue
        val t = runCatching { Instant.parse(it.dueAt) }.getOrNull() ?: continue
        if (best == null || t.isBefore(bestT)) {
            best = it.dueAt
            bestT = t
        }
    }
    return best
}

/** 到期倒计时文本：每 30s 重算保持新鲜（与状态徽章并存，绝不互相替代）。 */
@Composable
fun GalaxyCountdownText(dueAt: String, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    Text(
        galaxyExpiryText(dueAt, now),
        color = galaxyExpiryColor(dueAt, now),
        fontSize = 12.sp,
        modifier = modifier,
    )
}

// ── 总览页 ─────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenAccount: (String) -> Unit,
    onOpenQwen: (String) -> Unit,
    onOpenGalaxy: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ui by vm.uiState.collectAsState()

    // 每 5 分钟自动刷新
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60 * 1000L)
            vm.refreshAll()
        }
    }

    PullRefreshContainer(
        isRefreshing = ui.refreshing,
        onRefresh = { vm.refreshAll() },
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
        // 顶栏：标题 + 更新时间 + 刷新/设置按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("API Checkers", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (ui.lastUpdated > 0) "更新于 ${formatTime(ui.lastUpdated)}" else "尚未更新",
                    color = TextSub,
                    fontSize = 12.sp,
                )
            }
            IconButton(onClick = { vm.refreshAll() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = TextSub)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置", tint = TextSub)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (ui.deepSeekList.isEmpty()) {
                item(key = "deepseek-empty") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Card),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            "未配置 DeepSeek API Key，点击「设置」添加",
                            color = TextSub,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            items(ui.deepSeekList, key = { it.account?.id ?: "ds-none" }) { ds ->
                DeepSeekCard(ds, onClick = onOpenSettings)
            }
            if (ui.accounts.isEmpty()) {
                item(key = "empty") { EmptyAccountsCard() }
            }
            items(ui.accounts, key = { it.account.id }) { acc ->
                AccountCard(acc, onClick = { onOpenAccount(acc.account.id) })
            }
            if (ui.qwenList.isEmpty()) {
                item(key = "qwen-empty") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Card),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            "暂无 Qwen Token Plan 账号，点击下方「添加账号」配置",
                            color = TextSub,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            items(ui.qwenList, key = { it.account?.id ?: "qwen-none" }) { q ->
                QwenCard(q, onClick = { q.account?.let { onOpenQwen(it.id) } })
            }
            if (ui.galaxyList.isEmpty()) {
                item(key = "galaxy-empty") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Card),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            "暂无智星云账号，点击下方「添加账号」配置",
                            color = TextSub,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            items(ui.galaxyList, key = { it.account?.id ?: "galaxy-none" }) { g ->
                GalaxyCard(g, onClick = { g.account?.let { onOpenGalaxy(it.id) } })
            }
            item(key = "add") {
                TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Accent)
                    Spacer(Modifier.width(4.dp))
                    Text("添加账号", color = Accent, fontSize = 14.sp)
                }
            }
        }
    }
    }
}

/** DeepSeek 卡片：状态点 + 余额大字 + 充值/赠送 + 消费明细 */
@Composable
private fun DeepSeekCard(ds: DeepSeekUi, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot = when {
                    ds.balance != null -> Accent
                    ds.error != null -> Danger
                    else -> TextSub
                }
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(
                    ds.account?.name ?: "DeepSeek",
                    color = TextMain,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            when {
                !ds.keyConfigured -> Text("点击右上角设置添加 API Key", color = TextSub, fontSize = 14.sp)
                else -> {
                    val info = ds.balance?.infos?.firstOrNull()
                    if (info != null) {
                        Text(
                            currencySymbol(info.currency) + fmt(info.totalBalance),
                            color = TextMain,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "充值 ${currencySymbol(info.currency)}${fmt(info.toppedUpBalance)} · " +
                                "赠送 ${currencySymbol(info.currency)}${fmt(info.grantedBalance)}",
                            color = TextSub,
                            fontSize = 13.sp,
                        )
                    } else if (ds.error == null) {
                        Text("加载中…", color = TextSub, fontSize = 14.sp)
                    }
                    ds.cost?.let {
                        Text(
                            "今 ¥${fmt(it.today)} · 7日 ¥${fmt(it.last7d)} · 30日 ¥${fmt(it.last30d)}",
                            color = TextSub,
                            fontSize = 13.sp,
                        )
                    }
                    ds.error?.let { Text(it, color = Danger, fontSize = 13.sp) }
                }
            }
        }
    }
}

/** OpenCode 账号卡片：名称 + Go rolling 用量条 + 三窗口摘要 + Zen 余额 */
@Composable
private fun AccountCard(acc: AccountUi, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(acc.account.name, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val go = acc.goUsage
            when {
                acc.loading && go == null -> Text("加载中…", color = TextSub, fontSize = 14.sp)
                go != null -> {
                    go.rolling?.let { rolling ->
                        val color = usageColor(rolling.percent, rolling.status == "rate-limited")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Go", color = TextSub, fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                            UsageBar(rolling.percent, color, Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text("${rolling.percent}%", color = color, fontSize = 13.sp)
                        }
                    }
                    Row {
                        val windows = listOfNotNull(
                            go.rolling?.let { "R" to it },
                            go.weekly?.let { "W" to it },
                            go.monthly?.let { "M" to it },
                        )
                        windows.forEachIndexed { i, (label, w) ->
                            if (i > 0) Text(" · ", color = TextSub, fontSize = 13.sp)
                            Text(
                                "$label ${w.percent}%",
                                color = usageColor(w.percent, w.status == "rate-limited"),
                                fontSize = 13.sp,
                            )
                        }
                        acc.zenBilling?.let {
                            Text(" · Zen $${fmt(it.balanceUsd)}", color = TextSub, fontSize = 13.sp)
                        }
                    }
                }
                acc.error != null -> Text(acc.error!!, color = Danger, fontSize = 13.sp)
                else -> Text("暂无数据", color = TextSub, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun EmptyAccountsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            "暂无 OpenCode 账号，点击下方「添加账号」配置",
            color = TextSub,
            fontSize = 14.sp,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** Qwen Token Plan 卡片：状态点 + 名称 + 配额窗口（5小时/7天）+ 套餐/模型摘要 */
@Composable
private fun QwenCard(q: QwenUi, onClick: () -> Unit) {
    val acc = q.account ?: return
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot = when {
                    q.plan != null || q.usage != null -> Accent
                    q.error != null -> Danger
                    else -> TextSub
                }
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(
                    acc.name,
                    color = TextMain,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            when {
                !q.keyConfigured -> Text("未配置 API Key，点击右上角设置添加", color = TextSub, fontSize = 14.sp)
                else -> {
                    q.usage?.let { u ->
                        listOfNotNull(u.fiveHour?.let { "5小时" to it }, u.weekly?.let { "7天" to it }).forEach { (label, w) ->
                            val color = usageColor(w.percent, w.exhausted)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label, color = TextSub, fontSize = 13.sp, modifier = Modifier.width(48.dp))
                                UsageBar(w.percent, color, Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Text("${w.percent}%", color = color, fontSize = 13.sp)
                                if (w.exhausted) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("已限流", color = Danger, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    qwenPlanSummary(q)?.let { Text(it, color = TextSub, fontSize = 13.sp) }
                    if (!acc.hasCookie) {
                        Text("未配置控制台 Cookie，仅显示模型清单", color = TextSub, fontSize = 12.sp)
                    }
                    q.error?.let { Text(it, color = Danger, fontSize = 13.sp) }
                }
            }
        }
    }
}

/** 套餐/模型摘要：套餐 Lite · 模型 4 个 / 模型 4 个 / 套餐 Lite（与 Go 侧 planSummary 同语义） */
private fun qwenPlanSummary(q: QwenUi): String? {
    val plan = Parsers.planDisplayName(q.usage?.planCode.orEmpty())
    val count = q.plan?.models?.size ?: 0
    return when {
        plan.isNotEmpty() && count > 0 -> "套餐 $plan · 模型 $count 个"
        count > 0 -> "模型 $count 个"
        plan.isNotEmpty() -> "套餐 $plan"
        else -> null
    }
}

/** 余额颜色：≤0 红；<50 黄；其余蓝（与 Go writeGalaxyOverview 同阈值）。 */
fun galaxyBalanceColor(money: Double): Color = when {
    money <= 0 -> Danger
    money < 50 -> Warn
    else -> Accent
}

/**
 * 智星云卡片：余额大字 + 「运行中 N · 磁盘保留 N · 启动错误 N」+ 最近到期倒计时。
 * 错误红字可见；倒计时与异常状态并存（时间信息恒显）。
 */
@Composable
private fun GalaxyCard(g: GalaxyUi, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot = when {
                    g.balance != null || g.status != null -> Accent
                    g.error != null -> Danger
                    else -> TextSub
                }
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(
                    g.account?.name ?: "智星云",
                    color = TextMain,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            when {
                !g.keyConfigured -> {
                    Text("未配置 AccessKey/SecretKey，点击右上角设置添加", color = TextSub, fontSize = 14.sp)
                    g.error?.let { Text(it, color = Danger, fontSize = 13.sp) }
                }
                else -> {
                    val bal = g.balance
                    if (bal != null) {
                        Text(
                            "¥${fmt(bal.money)}",
                            color = galaxyBalanceColor(bal.money),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (bal.powerMoney > 0) {
                            Text("算力券 ¥${fmt(bal.powerMoney)}", color = TextSub, fontSize = 13.sp)
                        }
                    } else if (g.error == null) {
                        Text("加载中…", color = TextSub, fontSize = 14.sp)
                    }
                    g.status?.let { s ->
                        val createErrorColor = if (s.createError > 0) Danger else TextSub
                        Row {
                            Text("运行中 ${s.running}", color = TextSub, fontSize = 13.sp)
                            if (s.keeppedDisk > 0) { // 对齐 Go：0 时不占屏宽
                                Text(" · 磁盘保留 ${s.keeppedDisk}", color = TextSub, fontSize = 13.sp)
                            }
                            if (s.createError > 0) {
                                Text(" · 启动错误 ${s.createError}", color = createErrorColor, fontSize = 13.sp)
                            }
                        }
                    }
                    galaxyNextExpiry(g.instances)?.let { dueAt ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("最近 ", color = TextSub, fontSize = 12.sp)
                            GalaxyCountdownText(dueAt)
                        }
                    }
                    g.error?.let { Text(it, color = Danger, fontSize = 13.sp) }
                }
            }
        }
    }
}
