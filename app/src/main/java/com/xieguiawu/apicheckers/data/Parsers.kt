package com.xieguiawu.apicheckers.data

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 三个外部数据源的解析器（纯 JVM，可单测） */
object Parsers {
    private val json = Json { ignoreUnknownKeys = true }

    // ── OpenCode Go usage：官方 API JSON ────────────────────────

    fun parseGoUsage(raw: String): GoUsage {
        val payload = json.decodeFromString<GoUsagePayload>(raw)
        return payload.usage
    }

    // ── OpenCode Zen billing：SSR HTML 正则解析 ─────────────────
    // 算法移植自 MIT 项目 4cya/pi-go-bars core.ts（已授权复用）：
    // 1) 以 customerID:"cus_ 为锚点；2) 向前找对象起始 {；3) 深度计数取匹配 }；
    // 4) 对象内按字段正则逐个匹配（字段顺序可变）

    private val RE_BALANCE = Regex("(?:^|,)balance:(-?\\d+(?:\\.\\d+)?)")
    private val RE_MONTHLY_USAGE = Regex("monthlyUsage:(-?\\d+(?:\\.\\d+)?)")
    private val RE_MONTHLY_LIMIT = Regex("monthlyLimit:(-?\\d+(?:\\.\\d+)?)")
    private val RE_RELOAD = Regex("reload:(!0|!1|true|false|null)")
    private val RE_RELOAD_AMOUNT = Regex("reloadAmount:(-?\\d+(?:\\.\\d+)?)")
    private val RE_RELOAD_TRIGGER = Regex("reloadTrigger:(-?\\d+(?:\\.\\d+)?)")

    fun parseZenBilling(html: String): Result<ZenBilling> = runCatching {
        val start = html.indexOf("customerID:\"cus_")
        if (start == -1) error("会话已过期，请更新 Cookie")
        // 从锚点向前找对象起始 {：跳过字符串字面量（字符串内可能含 { 字符）
        var braceStart = -1
        var inStr = false
        var esc = false
        for (i in start - 1 downTo 0) {
            val c = html[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> { braceStart = i; break }
            }
        }
        if (braceStart == -1) error("账单页面结构异常")
        // 深度计数到匹配 }：同样跳过字符串字面量
        var depth = 0
        var end = -1
        inStr = false; esc = false
        for (i in braceStart until html.length) {
            val c = html[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
        }
        if (end == -1) error("账单页面结构异常")
        val obj = html.substring(braceStart, end + 1)
        fun num(re: Regex): Double? = re.find(obj)?.groupValues?.get(1)?.toDoubleOrNull()
        val balance = num(RE_BALANCE)
        val monthlyUsage = num(RE_MONTHLY_USAGE)
        val monthlyLimit = num(RE_MONTHLY_LIMIT)
        if (balance == null && monthlyUsage == null && monthlyLimit == null) {
            error("账单页面结构已变化，请更新应用")
        }
        ZenBilling(
            balanceUsd = (balance ?: 0.0) / 1e8,          // microcents → USD
            monthlyUsageUsd = (monthlyUsage ?: 0.0) / 1e8, // microcents → USD
            monthlyLimitUsd = monthlyLimit ?: 0.0,         // 整 USD
            autoReload = when (RE_RELOAD.find(obj)?.groupValues?.get(1)) {
                "!0", "true" -> true
                else -> false
            },
            reloadAmountUsd = num(RE_RELOAD_AMOUNT) ?: 0.0,
            reloadTriggerUsd = num(RE_RELOAD_TRIGGER) ?: 0.0,
        )
    }

    // ── DeepSeek 余额：官方 API JSON ───────────────────────────

    fun parseDeepSeekBalance(raw: String): Result<DeepSeekBalance> = runCatching {
        val p = json.decodeFromString<DeepSeekBalancePayload>(raw)
        DeepSeekBalance(
            isAvailable = p.is_available,
            infos = p.balance_infos.map {
                DeepSeekBalanceInfo(
                    currency = it.currency,
                    totalBalance = it.totalBalance.toDoubleOrNull() ?: 0.0,
                    grantedBalance = it.grantedBalance.toDoubleOrNull() ?: 0.0,
                    toppedUpBalance = it.toppedUpBalance.toDoubleOrNull() ?: 0.0,
                )
            },
        )
    }

    // ── DeepSeek 消费明细：platform 页面 API JSON ──────────────

    /**
     * @param refDate 计算 today/7d/30d 的参考日期（默认当天；测试可传入固定日期保证断言确定）
     */
    fun parseDeepSeekCost(raw: String, refDate: LocalDate = LocalDate.now()): Result<DeepSeekCost> = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        val code = root["code"]?.jsonPrimitive?.intOrNull
        if (code == 40003) error("DeepSeek 平台登录已失效，请更新平台 Token")
        if (code != null && code != 0) error("DeepSeek 平台接口错误（code=$code）")
        val days = mutableListOf<DeepSeekCostDay>()
        root["data"]?.jsonObject?.get("biz_data")?.jsonArray?.forEach { biz ->
            biz.jsonObject["days"]?.jsonArray?.forEach { dayEl ->
                val day = dayEl.jsonObject
                val date = day["date"]?.jsonPrimitive?.content ?: return@forEach
                var total = 0.0
                day["data"]?.jsonArray?.forEach { modelEl ->
                    modelEl.jsonObject["usage"]?.jsonArray?.forEach { u ->
                        val amt = u.jsonObject["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        total += amt
                    }
                }
                days.add(DeepSeekCostDay(date, total))
            }
        }
        // 今天/近7天/近30天：以 refDate 为基准，date 字符串直接比较（服务器已按天聚合）
        aggregateCost(days.associate { it.date to it.total }, refDate)
    }

    /**
     * 按参考日期聚合消费：today/7d/30d + 全部天。
     * 纯函数，供 parseDeepSeekCost 与仓库聚合复用（跨月、超 7 天数据不截断）。
     */
    fun aggregateCost(dayMap: Map<String, Double>, refDate: LocalDate = LocalDate.now()): DeepSeekCost {
        var today = 0.0
        var d7 = 0.0
        var d30 = 0.0
        for (i in 0 until 30) {
            val key = refDate.minusDays(i.toLong()).toString()
            val v = dayMap[key] ?: 0.0
            if (i == 0) today = v
            if (i < 7) d7 += v
            d30 += v
        }
        val days = dayMap.entries.sortedByDescending { it.key }.map { DeepSeekCostDay(it.key, it.value) }
        return DeepSeekCost(today, d7, d30, days)
    }
}
