package com.xieguiawu.apicheckers.data

import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** DeepSeek 数据仓库：余额（官方 API）+ 消费明细（platform 页面 API） */
class DeepSeekRepo {
    /**
     * GET 请求执行：401/403 → 中文认证错误；其他非 2xx → 状态码 + 响应摘要。
     * [authErrorMsg] 用于区分「API Key 无效」与「平台 Token 失效」两种凭据。
     */
    private suspend fun get(
        url: String,
        token: String,
        extraHeaders: Map<String, String> = emptyMap(),
        authErrorMsg: String = "API Key 无效或已过期",
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
                .build()
            val resp = ApiClient.client.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            when {
                resp.code == 401 || resp.code == 403 -> error(authErrorMsg)
                !resp.isSuccessful -> error("HTTP ${resp.code}: ${body.take(200)}")
                else -> body
            }
        }
    }

    suspend fun balance(apiKey: String): Result<DeepSeekBalance> =
        get("https://api.deepseek.com/user/balance", apiKey)
            .mapCatching { Parsers.parseDeepSeekBalance(it).getOrThrow() }

    /** 拉本月 + 上月两个月消费，汇总算 today/7d/30d。跨年由 LocalDate.minusMonths(1) 自动处理 */
    suspend fun cost(platformToken: String): Result<DeepSeekCost> {
        val now = LocalDate.now()
        // (year/month 取自同一 LocalDate)：上月跨年时 year 必须取 minusMonths(1) 之后的 year
        val months = listOf(now, now.minusMonths(1))
        val dayMap = mutableMapOf<String, Double>()
        var tokenInvalid = false
        var lastError: String? = null
        for (d in months) {
            val url = "https://platform.deepseek.com/api/v0/usage/cost?month=${d.monthValue}&year=${d.year}"
            val r = get(
                url, platformToken,
                mapOf(
                    "Accept" to "application/json",
                    "x-app-version" to "1.0.0",
                    "Referer" to "https://platform.deepseek.com/usage",
                    "User-Agent" to ApiClient.UA,
                ),
                authErrorMsg = "平台 Token 已失效，请更新平台 Token",
            )
            val raw: String = if (r.isFailure) {
                val msg = r.exceptionOrNull()?.message.orEmpty()
                if (msg.contains("失效")) tokenInvalid = true
                lastError = msg
                continue
            } else r.getOrThrow()
            val parsed = Parsers.parseDeepSeekCost(raw)
            if (parsed.isFailure) {
                // code 40003 等业务错误也标记 token 失效
                val msg = parsed.exceptionOrNull()?.message.orEmpty()
                if (msg.contains("失效")) tokenInvalid = true
                lastError = msg
                continue
            }
            for (day in parsed.getOrThrow().days) {
                dayMap[day.date] = (dayMap[day.date] ?: 0.0) + day.total
            }
        }
        // 两个月都无数据（含非认证错误）：显式失败，不返回误导性的零数据
        if (dayMap.isEmpty()) {
            val msg = when {
                tokenInvalid -> "DeepSeek 平台登录已失效，请更新平台 Token"
                lastError != null -> "消费数据获取失败：$lastError"
                else -> "消费数据为空"
            }
            return Result.failure(Exception(msg))
        }
        return Result.success(Parsers.aggregateCost(dayMap, now))
    }
}

/** OpenCode 数据仓库：Go usage（官方 API）+ Zen billing（页面解析） */
class OpenCodeRepo {
    private suspend fun get(
        url: String,
        headers: Map<String, String>,
        authErrorMsg: String = "Go API Key 无效或已过期",
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                val resp = ApiClient.client.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 || resp.code == 403 -> error(authErrorMsg)
                    !resp.isSuccessful -> error("HTTP ${resp.code}: ${body.take(200)}")
                    else -> body
                }
            }
        }

    suspend fun goUsage(account: Account): Result<GoUsage> =
        get("https://opencode.ai/zen/go/v1/usage", mapOf("Authorization" to "Bearer ${account.goApiKey}"))
            .mapCatching { Parsers.parseGoUsage(it) }

    suspend fun zenBilling(account: Account): Result<ZenBilling> {
        if (!account.hasZen) return Result.failure(Exception("未配置 Workspace/Cookie"))
        return get(
            "https://opencode.ai/workspace/${account.workspaceId}/billing",
            mapOf(
                "Cookie" to "auth=${account.authCookie}",
                "User-Agent" to ApiClient.UA,
            ),
            authErrorMsg = "Cookie 已过期，请更新 Auth Cookie",
        ).mapCatching { Parsers.parseZenBilling(it).getOrThrow() }
    }
}

// ── Qwen Token Plan（阿里云百炼订阅）仓库 ──────────────────────

/** 控制台 RPC 的三个 zelda 接口名（internal 供同包测试断言） */
internal const val QWEN_API_USAGE = "zeldaHttp.apikeyMgr./tokenplan/personal/api/v2/usage"
internal const val QWEN_API_SUBSCRIPTION = "zeldaHttp.apikeyMgr./tokenplan/personal/api/v2/subscription"

/** 一套区域的端点与网关参数。 */
data class QwenEndpoints(
    val gateway: String,       // token-plan.<region>.maas.aliyuncs.com（模型清单，API Key 认证）
    val dashboard: String,     // 订阅页面 HTML（抓 SEC_TOKEN）
    val userInfo: String,      // sec_token 兜底端点
    val quota: String,         // <host>/data/api.json（用量 RPC，Cookie 认证）
    val action: String,        // BroadScopeAspnGateway / IntlBroadScopeAspnGateway
    val region: String,        // RPC region 参数
    val consoleSite: String,   // BAILIAN_ALIYUN / QWENCLOUD
    val domain: String,        // cornerstoneParam.domain
    val lang: String,          // xsp_lang
    val commodityCode: String, // 订阅接口的套餐商品码
    val origin: String,        // Origin 头
)

/**
 * 按区域返回端点。region 空串 → 中国大陆。
 * 国际端点形状来自上游公开资料，本机无国际 Cookie 未做实带验证（见 CONTEXT 遗留问题）。
 */
fun qwenEndpointsFor(region: String): Result<QwenEndpoints> {
    val r = normalizeQwenRegion(region).getOrElse { return Result.failure(it) }
    return Result.success(
        if (r == RegionQwenIntl) {
            QwenEndpoints(
                gateway = "https://token-plan.ap-southeast-1.maas.aliyuncs.com",
                dashboard = "https://home.qwencloud.com/billing/subscription/token-plan-individual",
                userInfo = "https://home.qwencloud.com/tool/user/info.json",
                quota = "https://cs-data.qwencloud.com/data/api.json",
                action = "IntlBroadScopeAspnGateway",
                region = RegionQwenIntl,
                consoleSite = "QWENCLOUD",
                domain = "home.qwencloud.com",
                lang = "en-US",
                commodityCode = "sfm_tokenplansolo_public_intl",
                origin = "https://home.qwencloud.com",
            )
        } else {
            QwenEndpoints(
                gateway = "https://token-plan.cn-beijing.maas.aliyuncs.com",
                dashboard = "https://bailian.console.aliyun.com/cn-beijing?tab=plan#/efm/subscription/token-plan/personal",
                userInfo = "",
                quota = "https://bailian-cs.console.aliyun.com/data/api.json",
                action = "BroadScopeAspnGateway",
                region = RegionQwenCN,
                consoleSite = "BAILIAN_ALIYUN",
                domain = "bailian.console.aliyun.com",
                lang = "zh-CN",
                commodityCode = "sfm_tokenplansolo_public_cn",
                origin = "https://bailian.console.aliyun.com",
            )
        },
    )
}

/**
 * Qwen Token Plan 仓库：模型清单（API Key）+ 配额窗口（控制台 Cookie）。
 * 测试可注入 client / endpoints / 重试次数与间隔 / 时钟。
 */
class QwenRepo(
    private val client: OkHttpClient = ApiClient.client,
    private val endpointsOverride: QwenEndpoints? = null,
    private val usageAttempts: Int = 0,
    private val usageRetryDelayMs: Long = 0L,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private fun endpoints(region: String): Result<QwenEndpoints> =
        endpointsOverride?.let { Result.success(it) } ?: qwenEndpointsFor(region)

    /**
     * 拉取套餐可用模型清单（API Key 认证）。
     * 401/403 → 区域绑定提示（实测：同一把 sk-sp- key 打错区域同样回 invalid_api_key）。
     */
    suspend fun plan(acc: QwenAccount): Result<QwenPlan> {
        val ep = endpoints(acc.region).getOrElse { return Result.failure(it) }
        if (acc.apiKey.isBlank()) return Result.failure(Exception("未配置 API Key"))
        val body = get(
            "${ep.gateway}/compatible-mode/v1/models",
            mapOf("Authorization" to "Bearer ${acc.apiKey}", "Accept" to "application/json"),
            authErrorMsg = "Qwen API Key 无效或已过期（订阅密钥与区域绑定，请核对区域设置）",
        ).getOrElse { return Result.failure(it) }
        return Parsers.parseQwenModels(body).map { QwenPlan(it) }
    }

    /** 拉配额窗口（5 小时 / 7 天）与套餐档位。未配 Cookie → 显式提示。 */
    suspend fun usage(acc: QwenAccount): Result<QwenUsage> {
        if (!acc.hasCookie) return Result.failure(Exception("未配置控制台 Cookie"))
        val ep = endpoints(acc.region).getOrElse { return Result.failure(it) }
        val cookie = normalizeCookieHeader(acc.consoleCookie)
        val secToken = resolveSECToken(ep, cookie)

        val usage = fetchUsage(ep, cookie, secToken).getOrElse { return Result.failure(it) }
        // 套餐档位 best-effort：登录失效向上抛出，其他失败只记空档位
        val sub = fetchSubscription(ep, cookie, secToken)
        val code = sub.getOrElse { e ->
            if (e.message?.contains("Cookie") == true) return Result.failure(e)
            ""
        }
        return Result.success(usage.copy(planCode = code))
    }

    /**
     * 拉窗口数据。网关偶发返回「200 Success 但无窗口字段」，
     * 因此重试（上游实现同策略），重试后仍空则抛出「暂不可用」。
     */
    private suspend fun fetchUsage(ep: QwenEndpoints, cookie: String, secToken: String): Result<QwenUsage> {
        val attempts = if (usageAttempts <= 0) 3 else usageAttempts
        val delayMs = if (usageRetryDelayMs <= 0) 400L else usageRetryDelayMs
        val nowDt = now()
        var lastErr: Throwable = Exception("Qwen 用量数据暂不可用")
        for (i in 0 until attempts) {
            if (i > 0) delay(delayMs)
            val body = rpc(ep, cookie, secToken, QWEN_API_USAGE, emptyMap())
            if (body.isFailure) {
                lastErr = body.exceptionOrNull() ?: lastErr
                continue
            }
            val u = Parsers.parseQwenUsage(body.getOrThrow(), nowDt)
            if (u.isFailure) {
                val e = u.exceptionOrNull() ?: lastErr
                lastErr = e
                // 认证类错误不重试（重试不会改变结果）
                if (e.message?.contains("Cookie") == true) return Result.failure(e)
                continue
            }
            return Result.success(u.getOrThrow())
        }
        return Result.failure(lastErr)
    }

    /** 拉套餐档位（lite/standard/pro/max）。 */
    private suspend fun fetchSubscription(ep: QwenEndpoints, cookie: String, secToken: String): Result<String> {
        val body = rpc(ep, cookie, secToken, QWEN_API_SUBSCRIPTION, mapOf("commodityCode" to ep.commodityCode))
            .getOrElse { return Result.failure(it) }
        return Parsers.parseQwenSubscription(body)
    }

    /**
     * 调用控制台网关：POST <quota>?action=…&product=sfm_bailian&api=…&_v=undefined
     * 表单体（application/x-www-form-urlencoded）携带 product/action/region/language/params/sec_token。
     * 网关特点：登录失效仍回 HTTP 200，错误在信封里（errorCode=BailianGateway.Login.NotLogined），
     * 因此非 2xx 之外的判错交给解析器。
     */
    private suspend fun rpc(
        ep: QwenEndpoints,
        cookie: String,
        secToken: String,
        api: String,
        dataParams: Map<String, String>,
    ): Result<String> {
        val params = qwenParamsJSON(ep, api, dataParams, cookie).getOrElse { return Result.failure(it) }
        return post(
            url = "${ep.quota}?action=${enc(ep.action)}&product=sfm_bailian&api=${enc(api)}&_v=undefined",
            headers = buildMap {
                put("Cookie", cookie)
                put("X-Requested-With", "XMLHttpRequest")
                put("User-Agent", ApiClient.BROWSER_UA)
                put("Origin", ep.origin)
                put("Referer", ep.dashboard)
                val csrf = cookieValue(cookie, "login_aliyunid_csrf").ifEmpty { cookieValue(cookie, "csrf") }
                if (csrf.isNotEmpty()) {
                    put("x-xsrf-token", csrf)
                    put("x-csrf-token", csrf)
                }
            },
            form = buildMap {
                put("product", "sfm_bailian")
                put("action", ep.action)
                put("region", ep.region)
                put("language", ep.lang)
                put("params", params)
                if (secToken.isNotEmpty()) put("sec_token", secToken)
            },
            authErrorMsg = "控制台 Cookie 已过期或无效，请更新控制台 Cookie",
        )
    }

    /**
     * 组装 params 字段 JSON。
     *
     * 关键约束：cornerstoneParam 不能硬编码 switchAgent——网关会把它绑定到某个具体
     * 账号的工作区，其他账号全部回 BailianGateway.Workspace.NotAuthorised；省略它
     * 使网关自行解析会话默认工作区。
     */
    private fun qwenParamsJSON(
        ep: QwenEndpoints,
        api: String,
        dataParams: Map<String, String>,
        cookie: String,
    ): Result<String> = runCatching {
        val cornerstone = buildJsonObject {
            put("feTraceId", qwenTraceID())
            put("feURL", ep.dashboard)
            put("protocol", "V2")
            put("console", "ONE_CONSOLE")
            put("productCode", "p_efm")
            put("switchUserType", 3)
            put("domain", ep.domain)
            put("consoleSite", ep.consoleSite)
            put("userNickName", "")
            put("userPrincipalName", "")
            put("xsp_lang", ep.lang)
            val anon = cookieValue(cookie, "cna")
            if (anon.isNotEmpty()) put("X-Anonymous-Id", anon)
        }
        val data = buildJsonObject {
            put("cornerstoneParam", cornerstone)
            for ((k, v) in dataParams) put(k, v)
        }
        val payload = buildJsonObject {
            put("Api", api)
            put("V", "1.0")
            put("Data", data)
        }
        payload.toString()
    }

    /**
     * 解析 sec_token，三级降级：Cookie 内 sec_token →
     * 控制台页面 HTML 抓取（需浏览器导航头，否则 shell 不渲染 SEC_TOKEN）→
     * /tool/user/info.json。全失败返回空串（部分账号网关接受无 token 请求）。
     */
    private suspend fun resolveSECToken(ep: QwenEndpoints, cookie: String): String {
        cookieValue(cookie, "sec_token").ifNotEmpty { return it }
        if (ep.dashboard.isNotEmpty()) {
            val html = getPage(ep.dashboard, cookie, ep.origin, navigate = true)
            if (html != null) {
                val t = Parsers.extractQwenSECToken(html)
                if (t.isNotEmpty()) return t
            }
        }
        if (ep.userInfo.isNotEmpty()) {
            val body = getPage(ep.userInfo, cookie, ep.origin, navigate = false)
            if (body != null) {
                val t = Parsers.extractQwenSECToken(body)
                if (t.isNotEmpty()) return t
                val info = runCatching {
                    val root = Json.parseToJsonElement(body)
                    root.jsonObject["data"]?.jsonObject?.get("secToken")?.jsonPrimitive?.contentOrNull
                }.getOrNull()?.trim().orEmpty()
                if (info.isNotEmpty()) return info
            }
        }
        return ""
    }

    /**
     * GET 页面（sec_token 专用）。navigate=true 时附加浏览器导航头：
     * OneConsole shell 只对同源文档导航服务端渲染 SEC_TOKEN。
     */
    private suspend fun getPage(rawUrl: String, cookie: String, origin: String, navigate: Boolean): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val headers = buildMap {
                    put("Cookie", cookie)
                    put("User-Agent", ApiClient.BROWSER_UA)
                    put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    if (navigate) {
                        put("Referer", "$origin/")
                        put("Sec-Fetch-Site", "same-origin")
                        put("Sec-Fetch-Mode", "navigate")
                        put("Sec-Fetch-Dest", "document")
                        put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    }
                }
                val req = Request.Builder().url(rawUrl).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.string()
            }.getOrNull()
        }

    /** GET 请求执行：401/403 → [authErrorMsg]；其他非 2xx → 状态码 + 响应摘要。 */
    private suspend fun get(
        url: String,
        headers: Map<String, String>,
        authErrorMsg: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            when {
                resp.code == 401 || resp.code == 403 -> error(authErrorMsg)
                !resp.isSuccessful -> error("HTTP ${resp.code}: ${body.take(200)}")
                else -> body
            }
        }
    }

    /** POST 表单请求执行：401/403 → [authErrorMsg]；其他非 2xx → 状态码 + 响应摘要。 */
    private suspend fun post(
        url: String,
        headers: Map<String, String>,
        form: Map<String, String>,
        authErrorMsg: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val fb = FormBody.Builder()
            form.forEach { (k, v) -> fb.add(k, v) }
            val req = Request.Builder().url(url)
                .header("Accept", "application/json, text/plain, */*")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(fb.build())
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            when {
                resp.code == 401 || resp.code == 403 -> error(authErrorMsg)
                !resp.isSuccessful -> error("HTTP ${resp.code}: ${body.take(200)}")
                else -> body
            }
        }
    }
}

/**
 * 允许用户粘贴完整 `Cookie: xxx` 头，并保证单行：
 * 先压平全部空白为单个空格，再剥掉大小写不敏感的 `Cookie:` 前缀
 * （`cookie=x` 形式的合法 Cookie——名为 cookie——不受影响）。
 */
internal fun normalizeCookieHeader(s: String): String {
    val collapsed = s.trim().split(Regex("\\s+")).joinToString(" ")
    if (collapsed.length >= 7 && collapsed.substring(0, 7).equals("cookie:", ignoreCase = true)) {
        return collapsed.substring(7).trim()
    }
    return collapsed
}

/** 从 Cookie 头取指定名（不存在返回空串） */
internal fun cookieValue(header: String, name: String): String =
    header.split(";").map { it.trim() }.firstNotNullOfOrNull { part ->
        val kv = part.split("=", limit = 2)
        if (kv.size == 2 && kv[0] == name) kv[1].trim() else null
    }.orEmpty()

/** 生成 36 字符小写 UUIDv4（feTraceId，仅用于链路跟踪） */
internal fun qwenTraceID(): String = runCatching { UUID.randomUUID().toString() }
    .getOrDefault("00000000-0000-4000-8000-000000000000")

/** URL 参数编码（表单参数转义） */
private fun enc(v: String): String = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

/** String 扩展：非空时执行 [block] 并返回其值 */
private inline fun <T> String.ifNotEmpty(block: (String) -> T): T? = if (isNotEmpty()) block(this) else null
