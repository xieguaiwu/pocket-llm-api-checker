package com.xieguiawu.apicheckers.data

import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
