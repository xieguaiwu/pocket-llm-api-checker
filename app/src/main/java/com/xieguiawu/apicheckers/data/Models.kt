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

// ── 智星云 AI Galaxy（GPU 算力云） ────────────────────────────
//
// 序列化名与 Go 姊妹项目 models.go 的 json tag 一一对应（snake_case），
// 便于共享 fixture 与 --json 输出对照。

/**
 * 智星云账号。凭据 = 控制台「开放API → AccessKey管理」创建的 AccessKey/SecretKey
 * （需先完成实名认证）；两者缺一不可，故无 HasXxx 可选分支。
 */
@Serializable
data class GalaxyAccount(
    val id: String,
    val name: String,
    val accessKey: String,
    val secretKey: String,
) {
    /** AccessKey 与 SecretKey 均已配置才可调用 OpenAPI */
    val keyConfigured: Boolean get() = accessKey.isNotBlank() && secretKey.isNotBlank()

    /** 🔴 防未来调试性日志泄密：toString 不得带出 SecretKey */
    override fun toString(): String =
        "GalaxyAccount(id=$id, name=$name, accessKey=$accessKey, secretKey=****)"
}

/**
 * 粘贴清理：首尾空白 + 可选 `ak=` 前缀（契约 §四「均可选粘贴 ak=/sk= 前缀清理」）。
 * 真实 AccessKey 不含等号，剥离不会误伤。
 */
fun normalizeGalaxyAccessKey(s: String): String = s.trim().removePrefixIgnoreCase("ak=")

/** 粘贴清理：首尾空白 + 可选 `sk=` 前缀。 */
fun normalizeGalaxySecretKey(s: String): String = s.trim().removePrefixIgnoreCase("sk=")

private fun String.removePrefixIgnoreCase(prefix: String): String =
    if (length >= prefix.length && regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
        substring(prefix.length)
    } else {
        this
    }

/**
 * 主账户余额。三项金额语义不同、平台各自扣费，不互相折算：
 *   - money            现金余额（充值所得，元）
 *   - powerMoney       算力券（活动/退租返还，只能抵扣实例费用）
 *   - creditMoneyQuota 信用额度（余额≤0 时可透支的上限）
 */
@Serializable
data class GalaxyBalance(
    val name: String = "",
    val phone: String = "", // 已脱敏（183****2433）
    val money: Double = 0.0,
    @SerialName("power_money") val powerMoney: Double = 0.0,
    @SerialName("credit_money_quota") val creditMoneyQuota: Double = 0.0,
    @SerialName("vip_level") val vipLevel: Int = 0,
    @SerialName("custom_discount") val customDiscount: Double = 0.0,
    @SerialName("last_login_at") val lastLoginAt: String = "", // RFC3339，空串表示无记录
)

/** 实例状态统计。刻意不含 statusDefault：实测统计端点与列表条数不一致（契约 §2.4）。 */
@Serializable
data class GalaxyStatusCount(
    val all: Int = 0,
    val running: Int = 0,
    @SerialName("keepped_disk") val keeppedDisk: Int = 0,
    @SerialName("create_error") val createError: Int = 0,
    @SerialName("running_error") val runningError: Int = 0,
)

/**
 * 云主机实例。字段是显式白名单——接口响应里含 Init_passwd / LastInitPasswd /
 * RdpPasswd / VncPasswd 明文口令，任何一层都不允许透传（解析层直接丢弃）。
 */
@Serializable
data class GalaxyInstance(
    val name: String = "", // Container_name（平台侧唯一名）
    val note: String = "",
    val status: Int = 0,
    @SerialName("status_text") val statusText: String = "",
    val abnormal: Boolean = false,
    @SerialName("gpu_type") val gpuType: String = "",
    @SerialName("gpu_num") val gpuNum: Int = 0,
    @SerialName("cpu_num") val cpuNum: Int = 0,
    @SerialName("memory_gb") val memoryGb: Int = 0,
    val district: String = "",
    val host: String = "", // 平台内部机名（如 lyg2030）
    @SerialName("ssh_host") val sshHost: String = "",
    @SerialName("ssh_port") val sshPort: Int = 0,
    val image: String = "",
    val kind: String = "", // kvm / docker
    @SerialName("due_at") val dueAt: String = "", // RFC3339（已按 ServerTime 折算）
    @SerialName("disk_release_at") val diskReleaseAt: String = "",
    @SerialName("total_cost") val totalCost: Double = 0.0, // 小时单价（元/时）
    @SerialName("pay_type") val payType: String = "",   // money / power
    @SerialName("auto_renew") val autoRenew: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
)

/**
 * 近期消耗（余额变更明细聚合）。净消耗 = −(ΔMoney+ΔPower)，净额为负的返还/充值不计入。
 * 两个 *Partial 分别标记「今日」「近 7 天」窗口是否已翻完明细——明细按时间倒序，
 * 只要取到早于窗口下界的一条，该窗口数值即为精确值。
 */
@Serializable
data class GalaxyCost(
    val today: Double = 0.0,
    val last7d: Double = 0.0,
    @SerialName("today_partial") val todayPartial: Boolean = false,
    @SerialName("week_partial") val weekPartial: Boolean = false,
    val entries: List<GalaxyCostEntry> = emptyList(),
)

/** 单条余额变更（只留展示需要的四项）。 */
@Serializable
data class GalaxyCostEntry(
    val time: String = "", // RFC3339
    val remark: String = "",
    val spent: Double = 0.0, // 正数＝扣费，负数＝返还
    val left: Double = 0.0,  // 变更后现金余额
)
