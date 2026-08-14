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
import com.xieguiawu.apicheckers.ui.theme.Accent
import com.xieguiawu.apicheckers.ui.theme.Bg
import com.xieguiawu.apicheckers.ui.theme.Card
import com.xieguiawu.apicheckers.ui.theme.Danger
import com.xieguiawu.apicheckers.ui.theme.Divider
import com.xieguiawu.apicheckers.ui.theme.Ok
import com.xieguiawu.apicheckers.ui.theme.TextMain
import com.xieguiawu.apicheckers.ui.theme.TextSub
import com.xieguiawu.apicheckers.ui.theme.Warn
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

// ── 总览页 ─────────────────────────────────────────────────────

@Composable
fun HomeScreen(vm: AppViewModel, onOpenAccount: (String) -> Unit, onOpenSettings: () -> Unit) {
    val ui by vm.uiState.collectAsState()

    // 每 5 分钟自动刷新
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60 * 1000L)
            vm.refreshAll()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .safeDrawingPadding()
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
            item(key = "deepseek") { DeepSeekCard(ui.deepSeek) }
            if (ui.accounts.isEmpty()) {
                item(key = "empty") { EmptyAccountsCard() }
            }
            items(ui.accounts, key = { it.account.id }) { acc ->
                AccountCard(acc, onClick = { onOpenAccount(acc.account.id) })
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

/** DeepSeek 卡片：状态点 + 余额大字 + 充值/赠送 + 消费明细 */
@Composable
private fun DeepSeekCard(ds: DeepSeekUi) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    "DeepSeek",
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
                        val windows = listOf("R" to go.rolling, "W" to go.weekly, "M" to go.monthly)
                            .filter { it.second != null }
                        windows.forEachIndexed { i, (label, w) ->
                            if (i > 0) Text(" · ", color = TextSub, fontSize = 13.sp)
                            Text(
                                "$label ${w!!.percent}%",
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
