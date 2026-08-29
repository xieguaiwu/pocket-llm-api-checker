package com.xieguiawu.apicheckers.data

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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

// ── 智星云 AI Galaxy（OpenAPI v2）签名与解析 ──────────────────
// 契约与实测取证见 Go 仓库 docs/plans/2026-08-29-ai-galaxy-provider.md。

/**
 * 待签名字符串：参数名字典序升序、跳过空值、排除 sign/secret 两个键。
 * 与官方 Golang 参考实现逐条对齐。
 */
fun galaxyStringToSign(params: Map<String, String>): String =
    // ⚠️ sorted() 是 UTF-16 码元序；与 Go sort.Strings（字节序）等价的前提是
    // 参数名恒为 ASCII（当前键集 apikey/timestamp/nonce/page/page_size/
    // status_type 全是 ASCII）。未来新增非 ASCII 参数名时须先对齐排序规则。
    params.keys
        .filter { it != "sign" && it != "secret" && params[it]?.isNotEmpty() == true }
        .sorted()
        .joinToString("&") { "$it=${params[it]}" }

/**
 * 签名：md5(stringToSign + "&secret=" + SecretKey) 小写 hex。
 * secret 为空时不拼尾缀（对齐官方参考实现的 if secret != "" 分支）。
 */
fun galaxySign(params: Map<String, String>, secret: String): String {
    var s = galaxyStringToSign(params)
    if (secret.isNotEmpty()) s += "&secret=$secret"
    return MessageDigest.getInstance("MD5")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

// ── 实例状态 ───────────────────────────────────────────────

private val galaxyStatusTexts = mapOf(
    -2 to "已退费",
    -1 to "启动错误",
    0 to "已结束",
    1 to "运行中",
    4 to "启动中",
    5 to "重启中",
    7 to "重启失败",
    8 to "磁盘保留",
)

/** 平台实例状态码 → 中文（文档「获取自主实例详情」的状态常量表），未知码回「未知(N)」。 */
fun galaxyStatusText(status: Int): String = galaxyStatusTexts[status] ?: "未知($status)"

/** 是否仍占用资源（需要用户关注的状态）。已结束/已退费是终态，展示时降级为灰。 */
fun galaxyStatusActive(status: Int): Boolean = status in setOf(-1, 1, 4, 5, 7, 8)

/**
 * 到期时刻（Unix 秒）。平台同响应里带回 ServerTime，用 due-serverTime 差值折算
 * 可完全规避本机时钟偏移（本机偏移一分钟就会把「33分后到期」显示成「已到期」）。
 */
fun galaxyDeadlineUnix(due: Long, serverTime: Long, now: ZonedDateTime): Long {
    if (due <= 0) return 0
    return if (serverTime > 0) now.toEpochSecond() + (due - serverTime) else due
}

/** Unix 秒 → 指定时区 RFC3339；0/负数 → 空串（无该时间）。 */
fun galaxyRFC3339(unix: Long, zone: ZoneId): String {
    if (unix <= 0) return ""
    return Instant.ofEpochSecond(unix).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

/** 手机号脱敏：183****2433。非 11 位号码只保留前 3 位。 */
fun maskPhone(s: String): String {
    val r = s.trim().toCharArray()
    if (r.isEmpty()) return ""
    return if (r.size <= 7) String(r, 0, 1) + "****"
    else String(r, 0, 3) + "****" + String(r, r.size - 4, 4)
}

/** 宽容数值解析：数字 / 数字字符串均可；缺失/非法 → null。 */
private fun galaxyNumber(v: JsonPrimitive?): Double? =
    v?.doubleOrNull ?: v?.content?.trim()?.toDoubleOrNull()

/** 宽容取整：与 Go rawInt 同语义（"1" 与 1 都接受），缺失/非法 → 0。 */
private fun galaxyNumInt(v: JsonPrimitive?): Int = (galaxyNumber(v) ?: 0.0).toInt()

/** 宽容布尔：只认 JSON 布尔。字符串 "true"/"false"/数字/缺失一律 false
 * （对齐 Go rawBool 口径——否则 has_more:"false" 会让 Android 继续翻页而 Go 停住）。 */
private fun galaxyRawBool(v: JsonPrimitive?): Boolean =
    if (v != null && !v.isString) v.booleanOrNull ?: false else false

// ── 主账户信息 ─────────────────────────────────────────────

@Serializable
private data class GalaxyBalancePayload(
    @SerialName("Name") val name: String? = null,
    @SerialName("Phone") val phone: String? = null,
    @SerialName("Money") val money: JsonPrimitive? = null,
    @SerialName("PowerMoney") val powerMoney: JsonPrimitive? = null,
    @SerialName("CreditMoneyQuota") val creditMoneyQuota: JsonPrimitive? = null,
    @SerialName("CustomDiscount") val customDiscount: JsonPrimitive? = null,
    @SerialName("VipLevel") val vipLevel: JsonPrimitive? = null,
    @SerialName("Last_login_time") val lastLoginTime: JsonPrimitive? = null,
)

/**
 * 解析 /account/get_main_account_info 的 data 节点。
 * 数值一律宽容解析（平台偶发把金额序列化成字符串）。
 * 最后登录时间按 [zone] 格式化（对齐 Go time.Local 口径）。
 */
fun parseGalaxyBalance(raw: String, zone: ZoneId = ZoneId.systemDefault()): Result<GalaxyBalance> = runCatching {
    val p = galaxyJson.decodeFromString<GalaxyBalancePayload>(raw)
    if (p.money == null) error("智星云账户响应缺少 Money 字段")
    val bal = GalaxyBalance(
        name = p.name.orEmpty().trim(),
        phone = maskPhone(p.phone.orEmpty()),
        money = galaxyNumber(p.money) ?: 0.0,
        powerMoney = galaxyNumber(p.powerMoney) ?: 0.0,
        creditMoneyQuota = galaxyNumber(p.creditMoneyQuota) ?: 0.0,
        customDiscount = galaxyNumber(p.customDiscount) ?: 0.0,
        vipLevel = (galaxyNumber(p.vipLevel) ?: 0.0).toInt(),
    )
    val lastLogin = galaxyNumLong(p.lastLoginTime)
    if (lastLogin > 0) bal.copy(lastLoginAt = galaxyRFC3339(lastLogin, zone)) else bal
}

// ── 实例状态统计 ───────────────────────────────────────────

@Serializable
private data class GalaxyStatusCountPayload(
    @SerialName("statusAll") val all: JsonPrimitive? = null,
    @SerialName("statusRunning") val running: JsonPrimitive? = null,
    @SerialName("statusKeeppedDisk") val keeppedDisk: JsonPrimitive? = null,
    @SerialName("statusCreateError") val createError: JsonPrimitive? = null,
    @SerialName("statusRunningError") val runningError: JsonPrimitive? = null,
)

/**
 * 解析 /instance/get_instance_status_count 的 data 节点。
 * statusDefault 刻意不取：实测与列表条数不一致（契约 §2.4）。
 */
fun parseGalaxyStatusCount(raw: String): Result<GalaxyStatusCount> = runCatching {
    val p = galaxyJson.decodeFromString<GalaxyStatusCountPayload>(raw)
    if (p.all == null && p.running == null) error("智星云实例统计响应为空")
    GalaxyStatusCount(
        all = galaxyNumInt(p.all),
        running = galaxyNumInt(p.running),
        keeppedDisk = galaxyNumInt(p.keeppedDisk),
        createError = galaxyNumInt(p.createError),
        runningError = galaxyNumInt(p.runningError),
    )
}

// ── 实例列表 ───────────────────────────────────────────────
//
// 🔴 白名单解码：响应含 Init_passwd / LastInitPasswd / RdpPasswd / VncPasswd
// 明文口令，这里只声明要用的字段，其余被 kotlinx ignoreUnknownKeys 直接丢弃——
// 口令不可能经数据类、序列化、UI 或日志外泄。

@Serializable
private data class GalaxyInstanceListPayload(
    val list: List<GalaxyInstanceItemPayload> = emptyList(),
    @SerialName("total_count") val totalCount: JsonPrimitive? = null,
    @SerialName("has_more") val hasMore: JsonPrimitive? = null,
)

@Serializable
private data class GalaxyInstanceItemPayload(
    @SerialName("Container_name") val containerName: String? = null,
    @SerialName("Note") val note: String? = null,
    @SerialName("Status") val status: JsonPrimitive? = null,
    @SerialName("IsAbnormal") val isAbnormal: JsonPrimitive? = null,
    @SerialName("Gpu_type") val gpuType: String? = null,
    @SerialName("Gpu_num") val gpuNum: JsonPrimitive? = null,
    @SerialName("Cpu_num") val cpuNum: JsonPrimitive? = null,
    @SerialName("Memory") val memory: JsonPrimitive? = null,
    @SerialName("District") val district: String? = null,
    @SerialName("Host") val host: String? = null,
    @SerialName("Url") val url: String? = null,
    @SerialName("SshPort") val sshPort: JsonPrimitive? = null,
    @SerialName("Image") val image: String? = null,
    @SerialName("ContainerType") val containerType: String? = null,
    @SerialName("Due_time") val dueTime: JsonPrimitive? = null,
    @SerialName("DiskReleaseTime") val diskReleaseTime: JsonPrimitive? = null,
    @SerialName("ServerTime") val serverTime: JsonPrimitive? = null,
    @SerialName("Total_cost") val totalCost: JsonPrimitive? = null,
    @SerialName("PayTypeFirst") val payTypeFirst: String? = null,
    @SerialName("Ctime") val ctime: JsonPrimitive? = null,
    @SerialName("InstanceAutorenew") val autorenew: GalaxyAutorenewPayload? = null,
)

@Serializable
private data class GalaxyAutorenewPayload(
    @SerialName("SubscribeStatus") val subscribeStatus: JsonPrimitive? = null,
    @SerialName("CancelSubscribeAt") val cancelSubscribeAt: JsonPrimitive? = null,
)

/** 实例列表单页解析结果（仓库层按 hasMore 翻页）。 */
data class GalaxyInstancesPage(val instances: List<GalaxyInstance>, val total: Int, val hasMore: Boolean)

/**
 * 解析 /instance/get_instance_list 的 data 节点。
 * now 用于 ServerTime 时钟折算到期时刻（测试传固定值保证确定性）。
 */
fun parseGalaxyInstances(raw: String, now: ZonedDateTime): Result<GalaxyInstancesPage> = runCatching {
    val p = galaxyJson.decodeFromString<GalaxyInstanceListPayload>(raw)
    val zone = now.zone
    val out = p.list.map { it ->
        val status = galaxyNumInt(it.status)
        val due = galaxyNumLong(it.dueTime)
        val server = galaxyNumLong(it.serverTime)
        val autorenew = it.autorenew
        val cancelled = autorenew?.cancelSubscribeAt?.contentOrNull != null
        GalaxyInstance(
            name = it.containerName.orEmpty().trim(),
            note = it.note.orEmpty().trim(),
            status = status,
            statusText = galaxyStatusText(status),
            abnormal = galaxyNumInt(it.isAbnormal) != 0,
            gpuType = it.gpuType.orEmpty().trim(),
            gpuNum = galaxyNumInt(it.gpuNum),
            cpuNum = galaxyNumInt(it.cpuNum),
            memoryGb = galaxyNumInt(it.memory),
            district = it.district.orEmpty().trim(),
            host = it.host.orEmpty().trim(),
            sshHost = it.url.orEmpty().trim(),
            sshPort = galaxyNumInt(it.sshPort),
            image = it.image.orEmpty().trim(),
            kind = it.containerType.orEmpty().trim(),
            totalCost = galaxyNumber(it.totalCost) ?: 0.0,
            payType = it.payTypeFirst.orEmpty().trim(),
            dueAt = galaxyRFC3339(galaxyDeadlineUnix(due, server, now), zone),
            diskReleaseAt = galaxyRFC3339(galaxyNumLong(it.diskReleaseTime), zone),
            createdAt = galaxyRFC3339(galaxyNumLong(it.ctime), zone),
            autoRenew = autorenew != null && galaxyNumInt(autorenew.subscribeStatus) == 1 && !cancelled,
        )
    }
    GalaxyInstancesPage(
        instances = out,
        total = galaxyNumInt(p.totalCount),
        hasMore = galaxyRawBool(p.hasMore),
    )
}

// ── 余额变更明细 ───────────────────────────────────────────

@Serializable
private data class GalaxyChangesPayload(
    val list: List<GalaxyChangeItemPayload> = emptyList(),
    @SerialName("has_more") val hasMore: JsonPrimitive? = null,
)

@Serializable
private data class GalaxyChangeItemPayload(
    @SerialName("CreateTime") val createTime: String? = null,
    @SerialName("Remark") val remark: String? = null,
    @SerialName("DiffMoney") val diffMoney: JsonPrimitive? = null,
    @SerialName("DiffPower") val diffPower: JsonPrimitive? = null,
    @SerialName("MoneyLeft") val moneyLeft: JsonPrimitive? = null,
)

/** 单条余额变更（内部结构，保留 ZonedDateTime 供聚合）。 */
data class GalaxyChange(
    val at: ZonedDateTime,
    val remark: String,
    val spent: Double, // 正数＝扣费，负数＝返还
    val left: Double,
)

/** 余额变更单页解析结果。 */
data class GalaxyChangesPage(val changes: List<GalaxyChange>, val hasMore: Boolean)

private val galaxyCreateTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** 解析 Unix 秒（数值字符串与数字均可），缺失/非法 → 0。 */
private fun galaxyNumLong(v: JsonPrimitive?): Long = (galaxyNumber(v) ?: 0.0).toLong()

/**
 * 解析 /billing/get_balance_change_list 的 data 节点。
 * 消费额 = −(ΔMoney + ΔPower)：实测「复制启动」DiffMoney=-0.155 + DiffPower=-0.17
 * 合计 0.325，与该实例 Total_cost 精确吻合（两种资金融合计费）。
 * CreateTime 按 [zone] 解析（对齐 Go time.ParseInLocation(…, time.Local)）。
 */
fun parseGalaxyChanges(raw: String, zone: ZoneId = ZoneId.systemDefault()): Result<GalaxyChangesPage> = runCatching {
    val p = galaxyJson.decodeFromString<GalaxyChangesPayload>(raw)
    val out = p.list.mapNotNull { it ->
        val t = runCatching {
            LocalDateTime.parse(it.createTime.orEmpty().trim(), galaxyCreateTimeFmt).atZone(zone)
        }.getOrNull() ?: return@mapNotNull null // 单条时间格式异常不致命：跳过该条
        GalaxyChange(
            at = t,
            remark = it.remark.orEmpty().trim(),
            spent = -((galaxyNumber(it.diffMoney) ?: 0.0) + (galaxyNumber(it.diffPower) ?: 0.0)),
            left = galaxyNumber(it.moneyLeft) ?: 0.0,
        )
    }
    GalaxyChangesPage(changes = out, hasMore = galaxyRawBool(p.hasMore))
}

/**
 * 聚合今日 / 近 7 天净消耗（近 7 天含今日，与 DeepSeek 侧 aggregateCost 同一口径）。
 * 明细按时间倒序返回，所以只要看到一条早于窗口下界的记录，该窗口就取完了；
 * 否则只能给下限（*Partial=true，渲染层加「≥」）。
 */
fun aggregateGalaxyCost(changes: List<GalaxyChange>, hasMore: Boolean, now: ZonedDateTime): GalaxyCost {
    val ref = now.toLocalDate()
    val day7 = ref.minusDays(6)
    var today = 0.0
    var week = 0.0
    var oldest: LocalDate = ref.plusDays(1) // 哨兵：比今日更晚，保证有数据时会被下调
    for (c in changes) {
        val day = c.at.toLocalDate()
        if (day.isBefore(oldest)) oldest = day
        if (c.spent < 0) continue // 纯返还（充值/退款）不计入消耗
        if (!day.isBefore(ref)) today += c.spent
        if (!day.isBefore(day7)) week += c.spent
    }
    var todayPartial = false
    var weekPartial = false
    if (hasMore) {
        // 还能往前翻：只有已经看到窗口下界之前的记录，才能断定窗口取完
        todayPartial = !oldest.isBefore(ref)
        weekPartial = !oldest.isBefore(day7)
    }
    return GalaxyCost(
        today = today,
        last7d = week,
        todayPartial = todayPartial,
        weekPartial = weekPartial,
        entries = changes.take(5).map {
            GalaxyCostEntry(
                time = it.at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                remark = it.remark,
                spent = it.spent,
                left = it.left,
            )
        },
    )
}

/** galaxy 解析共用 Json：ignoreUnknownKeys（口令等未知字段直接丢弃）。 */
private val galaxyJson = Json { ignoreUnknownKeys = true }
