package com.xieguiawu.apicheckers.data

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

    // ── Qwen Token Plan：网关模型清单（API Key 认证） ──────────

    /** 解析 GET /compatible-mode/v1/models 响应，返回去空+去重+排序后的模型 id 列表。空清单视为失败。 */
    fun parseQwenModels(raw: String): Result<List<String>> = runCatching {
        val payload = try {
            json.decodeFromString<QwenModelsPayload>(raw)
        } catch (e: Exception) {
            error("Qwen 模型清单 JSON 解析失败: ${e.message}")
        }
        val seen = mutableSetOf<String>()
        val models = mutableListOf<String>()
        for (m in payload.data) {
            val id = m.id.trim()
            if (id.isEmpty() || !seen.add(id)) continue
            models.add(id)
        }
        if (models.isEmpty()) error("未获取到 Qwen 可用模型")
        models.sorted()
    }

    // ── Qwen Token Plan：控制台 RPC（Cookie 认证） ─────────────
    //
    // 控制台网关信封形如 {code, data:{DataV2:{ret, data:{code, data:{...}}}}, successResponse}，
    // 目标负载深度嵌套且部分层以「JSON 字符串」形式内嵌，因此解析器做两件事：
    //  1. 先判错信封（data.errorCode 非空）；
    //  2. BFS 遍历对象/数组（含展开形如 JSON 的字符串值），取第一个包含目标键的对象。
    //
    // 响应形状实测来源：百炼控制台 token-plan/personal/api/v2/usage（2026-08-29 抓包）。

    /** 内嵌 JSON 展开的最大深度（防御无限嵌套） */
    private const val QWEN_WALK_MAX_DEPTH = 12

    /** BFS 查找含任一目标键的对象（内嵌 JSON 字符串会被展开后继续遍历）。 */
    internal fun qwenFindObject(node: JsonElement, wants: Set<String>, depth: Int = 0): JsonObject? {
        if (depth > QWEN_WALK_MAX_DEPTH) return null
        return when (node) {
            is JsonObject -> {
                for (want in wants) if (want in node) return node
                for ((_, child) in node) {
                    qwenFindObject(child, wants, depth + 1)?.let { return it }
                }
                null
            }
            is JsonArray -> {
                for (child in node) {
                    qwenFindObject(child, wants, depth + 1)?.let { return it }
                }
                null
            }
            else -> {
                val s = (node as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
                if (s.length >= 2 && (s.startsWith("{") || s.startsWith("["))) {
                    val inner = runCatching { json.parseToJsonElement(s) }.getOrNull() ?: return null
                    qwenFindObject(inner, wants, depth + 1)
                } else null
            }
        }
    }

    /**
     * 判错信封：返回可读错误（无错则 null）。
     * 登录类错误（NotLogined / NeedLogin）映射为 Cookie 过期提示，与 Zen billing 同语义。
     */
    private fun qwenErrorOf(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val data = root["data"] as? JsonObject ?: return null
        val code = data["errorCode"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val msg = data["errorMsg"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (code.isEmpty() && msg.isEmpty()) return null
        val effective = code.ifEmpty { msg }
        val low = (code + " " + msg).lowercase()
        if (low.contains("notlogined") || low.contains("needlogin") ||
            low.contains("login") || low.contains("unauthor")
        ) {
            return "控制台 Cookie 已过期或无效，请更新控制台 Cookie"
        }
        return "Qwen 控制台接口错误：$effective"
    }

    /** 宽容取数（数字或数字字符串）。 */
    internal fun qwenNumber(v: JsonElement?): Double? = when (v) {
        is JsonPrimitive -> v.doubleOrNull ?: v.content.trim().toDoubleOrNull()
        else -> null
    }

    /** 比例值 → 窗口。契约上接口返回 0-1 比例；>2 视为已是百分数尺度（防御性处理）。 */
    private fun qwenRatioToWindow(ratio: Double, resetsAt: JsonElement?, now: ZonedDateTime): QwenWindow {
        val (percent, exhausted) = qwenPercent(ratio)
        return QwenWindow(percent = percent, resetsAt = qwenResetTime(resetsAt, now), exhausted = exhausted)
    }

    /**
     * 拆分「百分比 + 是否用尽」。
     * 接口契约为 0-1 比例。取值域划分：
     *  - ≤ 2：比例域。>1 为超额（配额用尽后网关仍可能给到 1.0x），一律上限 100% + 已限流；
     *  - > 2：不可能是比例，视为已是百分数尺度（防御：避免显示 7913% 与误判限流）。
     */
    fun qwenPercent(ratio: Double): Pair<Int, Boolean> {
        if (ratio > 2) return clampPercent(ratio.toInt()) to (ratio >= 100)
        return clampPercent((ratio * 100).toInt()) to (ratio >= 1)
    }

    /** 把百分比限到 0-100（用量条上限） */
    private fun clampPercent(p: Int): Int = p.coerceIn(0, 100)

    /**
     * 重置时间 → RFC3339。数字按 Unix 毫秒（同一时刻，与 now 时区无关）；字符串原样传递
     * （解析失败时渲染层降级为「即将重置」）。
     */
    private fun qwenResetTime(v: JsonElement?, now: ZonedDateTime): String {
        val num = qwenNumber(v)
        if (num != null) {
            return Instant.ofEpochMilli(num.toLong()).atZone(now.zone)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }
        return v?.jsonPrimitive?.content?.trim().orEmpty()
    }

    /** 解析 tokenplan/personal/api/v2/usage 响应。两个窗口都缺失 → 报错（仓库层会重试）。 */
    fun parseQwenUsage(raw: String, now: ZonedDateTime): Result<QwenUsage> = runCatching {
        qwenErrorOf(raw)?.let { error(it) }
        val node = runCatching { json.parseToJsonElement(raw) }
            .getOrElse { error("Qwen 用量 JSON 解析失败: ${it.message}") }
        val obj = qwenFindObject(node, setOf("per5HourPercentage", "per1WeekPercentage"))
            ?: error("Qwen 用量数据暂不可用")
        var fiveHour: QwenWindow? = null
        var weekly: QwenWindow? = null
        qwenNumber(obj["per5HourPercentage"])?.let { fiveHour = qwenRatioToWindow(it, obj["per5HourResetTime"], now) }
        qwenNumber(obj["per1WeekPercentage"])?.let { weekly = qwenRatioToWindow(it, obj["per1WeekResetTime"], now) }
        if (fiveHour == null && weekly == null) error("Qwen 用量数据暂不可用")
        QwenUsage(fiveHour = fiveHour, weekly = weekly)
    }

    /**
     * 解析 tokenplan/personal/api/v2/subscription 响应，取套餐档位（lite/standard/pro/max）。
     * 找不到档位不是错误（best-effort，返回空串）。
     */
    fun parseQwenSubscription(raw: String): Result<String> = runCatching {
        qwenErrorOf(raw)?.let { error(it) }
        val node = runCatching { json.parseToJsonElement(raw) }
            .getOrElse { error("Qwen 订阅 JSON 解析失败: ${it.message}") }
        val keys = listOf("specCode", "spec_code", "planName", "plan_name", "planCode", "plan_code")
        val obj = qwenFindObject(node, keys.toSet()) ?: return@runCatching ""
        for (k in keys) {
            val s = obj[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (s.isNotEmpty()) return@runCatching s.lowercase()
        }
        ""
    }

    /** 套餐档位 → 展示名（未知档位原样输出）。 */
    fun planDisplayName(code: String): String = when (code.trim().lowercase()) {
        "" -> ""
        "lite" -> "Lite"
        "standard" -> "Standard"
        "pro" -> "Pro"
        "max" -> "Max"
        else -> code
    }

    /** 从控制台 HTML 提取 SEC_TOKEN（window.ALIYUN_CONSOLE_CONFIG 内）。 */
    private val RE_QWEN_SEC_TOKEN = Regex("SEC_TOKEN\\s*[:=]\\s*\"([^\"]+)\"")

    /** 提取 sec_token；找不到返回空串（网关对部分账号接受无 token 请求）。 */
    fun extractQwenSECToken(html: String): String =
        RE_QWEN_SEC_TOKEN.find(html)?.groupValues?.get(1)?.trim().orEmpty()
}
