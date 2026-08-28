package com.xieguiawu.apicheckers.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Qwen Token Plan（阿里云百炼订阅）区域常量 ─────────────────

const val RegionQwenCN = "cn-beijing"   // 中国大陆（北京）：网关 token-plan.cn-beijing.maas.aliyuncs.com
const val RegionQwenIntl = "ap-southeast-1" // 国际（新加坡）：网关 token-plan.ap-southeast-1.maas.aliyuncs.com

/**
 * 归一化区域取值。空串 → 默认中国大陆。
 * 别名：cn/domestic/beijing/china → cn-beijing；intl/singapore/international → ap-southeast-1。
 */
fun normalizeQwenRegion(s: String): Result<String> = runCatching {
    when (s.trim().lowercase()) {
        "", "cn", "cn-beijing", "domestic", "beijing", "china" -> RegionQwenCN
        "intl", "international", "singapore", "ap-southeast-1", "sg" -> RegionQwenIntl
        else -> error("Qwen 区域不支持：$s（可用值 cn-beijing / ap-southeast-1）")
    }
}

/** 区域展示名（设置页与详情页共用） */
fun qwenRegionDisplayName(region: String): String =
    if (normalizeQwenRegion(region).getOrNull() == RegionQwenIntl) "国际（新加坡）" else "中国大陆（北京）"

// ── OpenCode Go usage（官方 API） ──────────────────────────────

@Serializable
data class GoWindow(val status: String = "", val percent: Int = 0, val resetsAt: String = "")

@Serializable
data class GoUsagePayload(val usage: GoUsage)

@Serializable
data class GoUsage(
    val rolling: GoWindow? = null,
    val weekly: GoWindow? = null,
    val monthly: GoWindow? = null,
)

// ── OpenCode Zen billing（页面解析） ───────────────────────────

data class ZenBilling(
    val balanceUsd: Double,
    val monthlyUsageUsd: Double,
    val monthlyLimitUsd: Double,
    val autoReload: Boolean,
    val reloadAmountUsd: Double,
    val reloadTriggerUsd: Double,
)

// ── DeepSeek 余额 ──────────────────────────────────────────────

/** API 原始响应 DTO（金额为字符串，直接对应 JSON） */
@Serializable
data class DeepSeekBalancePayload(
    val is_available: Boolean = false,
    val balance_infos: List<DeepSeekBalanceInfoPayload> = emptyList(),
)

@Serializable
data class DeepSeekBalanceInfoPayload(
    val currency: String = "",
    @SerialName("total_balance") val totalBalance: String = "0",
    @SerialName("granted_balance") val grantedBalance: String = "0",
    @SerialName("topped_up_balance") val toppedUpBalance: String = "0",
)

/** 域模型：金额转为 Double，供 UI 直接使用 */
data class DeepSeekBalanceInfo(
    val currency: String,
    val totalBalance: Double,
    val grantedBalance: Double,
    val toppedUpBalance: Double,
)

data class DeepSeekBalance(val isAvailable: Boolean, val infos: List<DeepSeekBalanceInfo>)

// ── DeepSeek 消费明细 ──────────────────────────────────────────

data class DeepSeekCostDay(val date: String, val total: Double)

data class DeepSeekCost(
    val today: Double,
    val last7d: Double,
    val last30d: Double,
    val days: List<DeepSeekCostDay>,
)

// ── 账号 ───────────────────────────────────────────────────────

/** DeepSeek 账号（支持多个 API key，各自查看余额/消费） */
@Serializable
data class DeepSeekAccount(
    val id: String,
    val name: String,
    val apiKey: String,
    val platformToken: String = "",
) { val hasToken: Boolean get() = platformToken.isNotBlank() }

@Serializable
data class Account(
    val id: String,
    val name: String,
    val goApiKey: String,
    val workspaceId: String = "",
    val authCookie: String = "",
) {
    /** 只有同时配置了 workspace 与 cookie 才展示 Zen plan */
    val hasZen: Boolean get() = workspaceId.isNotBlank() && authCookie.isNotBlank()
}

// ── Qwen Token Plan（阿里云百炼订阅） ──────────────────────────

/**
 * Qwen 账号。apiKey 为 Token Plan 订阅密钥（sk-sp- 前缀）；
 * consoleCookie 可选：阿里云百炼控制台 Cookie，缺失时只能显示套餐模型清单，
 * 无法显示配额窗口（用量接口只认控制台会话，实测 API Key 返回
 * BailianGateway.Login.NotLogined）。
 */
@Serializable
data class QwenAccount(
    val id: String,
    val name: String,
    val apiKey: String,
    val consoleCookie: String = "",
    val region: String = RegionQwenCN,
) {
    /** 是否配置了控制台 Cookie */
    val hasCookie: Boolean get() = consoleCookie.isNotBlank()

    /** 归一化后的区域（非法/空值回落中国大陆） */
    val qwenRegion: String get() = normalizeQwenRegion(region).getOrDefault(RegionQwenCN)
}

/**
 * 订阅滚动窗口（5 小时 / 7 天）。
 * Percent 由接口返回的比例值（0-1）截断取整；ResetsAt 为 RFC3339；
 * Exhausted 由原始比例 ≥ 1 推导（官方规则：窗口内配额用尽则暂停服务）。
 */
data class QwenWindow(val percent: Int, val resetsAt: String, val exhausted: Boolean)

/** 控制台用量接口结果（Cookie 认证）。窗口可能缺失（null）。 */
data class QwenUsage(
    val planCode: String = "",
    val fiveHour: QwenWindow? = null,
    val weekly: QwenWindow? = null,
)

/** 网关模型清单（API Key 认证） */
data class QwenPlan(val models: List<String>)

/** 模型清单 API 响应 DTO（兼容 OpenAI /v1/models 形状） */
@Serializable
data class QwenModelsPayload(val data: List<QwenModelItem> = emptyList())

@Serializable
data class QwenModelItem(val id: String = "")
