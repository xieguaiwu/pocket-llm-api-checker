package com.xieguiawu.apicheckers.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
