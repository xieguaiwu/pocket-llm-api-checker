package com.xieguiawu.apicheckers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xieguiawu.apicheckers.data.DeepSeekAccount
import com.xieguiawu.apicheckers.data.DeepSeekBalance
import com.xieguiawu.apicheckers.data.DeepSeekCost
import com.xieguiawu.apicheckers.data.DeepSeekRepo
import com.xieguiawu.apicheckers.data.BaiAccount
import com.xieguiawu.apicheckers.data.BaiFreeFlashModels
import com.xieguiawu.apicheckers.data.BaiPoints
import com.xieguiawu.apicheckers.data.BaiPlan
import com.xieguiawu.apicheckers.data.BaiRepo
import com.xieguiawu.apicheckers.data.BaiUsageStats
import com.xieguiawu.apicheckers.data.LongCatAccount
import com.xieguiawu.apicheckers.data.LongCatAuthError
import com.xieguiawu.apicheckers.data.LongCatPlan
import com.xieguiawu.apicheckers.data.LongCatRepo
import com.xieguiawu.apicheckers.data.LongCatUsage
import com.xieguiawu.apicheckers.data.GalaxyAccount
import com.xieguiawu.apicheckers.data.GalaxyBalance
import com.xieguiawu.apicheckers.data.GalaxyCost
import com.xieguiawu.apicheckers.data.GalaxyInstance
import com.xieguiawu.apicheckers.data.GalaxyRepo
import com.xieguiawu.apicheckers.data.GalaxyStatusCount
import com.xieguiawu.apicheckers.data.GalaxyStatusDefault
import com.xieguiawu.apicheckers.data.GoUsage
import com.xieguiawu.apicheckers.data.OpenCodeRepo
import com.xieguiawu.apicheckers.data.QwenAccount
import com.xieguiawu.apicheckers.data.QwenPlan
import com.xieguiawu.apicheckers.data.QwenRepo
import com.xieguiawu.apicheckers.data.QwenUsage
import com.xieguiawu.apicheckers.data.SecureSettings
import com.xieguiawu.apicheckers.data.ZenBilling
import com.xieguiawu.apicheckers.data.Account
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── UI 状态模型 ────────────────────────────────────────────────

/** 单个 DeepSeek 账号的 UI 状态 */
data class DeepSeekUi(
    val account: DeepSeekAccount? = null,
    val balance: DeepSeekBalance? = null,
    val cost: DeepSeekCost? = null,
    val error: String? = null,
    val loading: Boolean = false,
) {
    val keyConfigured: Boolean get() = account?.apiKey?.isNotBlank() == true
}

/** 单个 OpenCode 账号的 UI 状态 */
data class AccountUi(
    val account: Account,
    val goUsage: GoUsage? = null,
    val zenBilling: ZenBilling? = null,
    val error: String? = null,
    val loading: Boolean = false,
)

/** 单个 Qwen Token Plan 账号的 UI 状态 */
data class QwenUi(
    val account: QwenAccount? = null,
    val plan: QwenPlan? = null,
    val usage: QwenUsage? = null,
    val error: String? = null,
    val loading: Boolean = false,
) {
    val keyConfigured: Boolean get() = account?.apiKey?.isNotBlank() == true
}

/** 单个智星云账号的 UI 状态。余额必需；统计/实例/消耗任一失败只影响该段（错误合并进 error）。 */
data class GalaxyUi(
    val account: GalaxyAccount? = null,
    val balance: GalaxyBalance? = null,
    val status: GalaxyStatusCount? = null,
    val instances: List<GalaxyInstance> = emptyList(),
    val cost: GalaxyCost? = null,
    val error: String? = null,
    val loading: Boolean = false,
) {
    val keyConfigured: Boolean get() = account?.keyConfigured == true

    /** 运行中/启动中/重启中实例的合计时价（元/时）——余额还能撑多久算得出来 */
    val hourlyCost: Double
        get() = instances.filter { it.status == 1 || it.status == 4 || it.status == 5 }
            .sumOf { it.totalCost }
}

/** 单个白B.AI 账号的 UI 状态（同 Go BaiResult：两路独立可缺一路，stats 可选）。 */
data class BaiUi(
    val account: BaiAccount? = null,
    val plan: BaiPlan? = null,
    val points: BaiPoints? = null,
    val stats: BaiUsageStats? = null,
    val error: String? = null,
    val loading: Boolean = false,
) {
    val keyConfigured: Boolean get() = account?.keyConfigured == true
}

/**
 * 单个 LongCat 账号的 UI 状态（同 Go LongCatResult：plan/usage 两路独立）。
 * usage.balanceOK=null 表示未探活；探活 402（余额不足）不是 error。
 */
data class LongCatUi(
    val account: LongCatAccount? = null,
    val plan: LongCatPlan? = null,
    val usage: LongCatUsage? = null,
    val error: String? = null,
    val loading: Boolean = false,
) {
    val keyConfigured: Boolean get() = account?.keyConfigured == true
}

/** 全局 UI 状态 */
data class UiState(
    val deepSeekList: List<DeepSeekUi> = emptyList(),
    val accounts: List<AccountUi> = emptyList(),
    val qwenList: List<QwenUi> = emptyList(),
    val galaxyList: List<GalaxyUi> = emptyList(),
    val baiList: List<BaiUi> = emptyList(),
    val longCatList: List<LongCatUi> = emptyList(),
    val refreshing: Boolean = false,
    val lastUpdated: Long = 0L,
)

// ── ViewModel ──────────────────────────────────────────────────

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val deepSeekRepo = DeepSeekRepo()
    private val openCodeRepo = OpenCodeRepo()
    private val qwenRepo = QwenRepo()
    private val galaxyRepo = GalaxyRepo()
    private val baiRepo = BaiRepo()
    private val longCatRepo = LongCatRepo()
    private val _ui = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _ui.asStateFlow()

    init {
        // MainActivity.onCreate 已先调用；此处幂等兜底，保证 ViewModel 单测/预览可用
        SecureSettings.init(app)
        loadFromCache()
        refreshAll()
    }

    /** 从本地存储恢复界面骨架（不发起网络） */
    private fun loadFromCache() {
        val dsAccounts = SecureSettings.getDeepSeekAccounts().map { DeepSeekUi(account = it) }
        val accounts = SecureSettings.getAccounts().map { AccountUi(it) }
        val qwenAccounts = SecureSettings.getQwenAccounts().map { QwenUi(account = it) }
        val galaxyAccounts = SecureSettings.getGalaxyAccounts().map { GalaxyUi(account = it) }
        val baiAccounts = SecureSettings.getBaiAccounts().map { BaiUi(account = it) }
        val longCatAccounts = SecureSettings.getLongCatAccounts().map { LongCatUi(account = it) }
        _ui.update {
            it.copy(
                deepSeekList = dsAccounts,
                accounts = accounts,
                qwenList = qwenAccounts,
                galaxyList = galaxyAccounts,
                baiList = baiAccounts,
                longCatList = longCatAccounts,
            )
        }
    }

    /** 刷新全部数据（DeepSeek + OpenCode + Qwen + 智星云，并行）。重入保护：刷新中忽略再次触发 */
    fun refreshAll() {
        if (_ui.value.refreshing) return
        viewModelScope.launch {
            _ui.update { it.copy(refreshing = true) }
            // 以本地存储为准重建账号列表（设置页新增/删除后立即生效）；
            // 保留旧数据避免刷新期间界面闪断（P2-15）
            val freshDs = SecureSettings.getDeepSeekAccounts()
            val fresh = SecureSettings.getAccounts()
            val freshQwen = SecureSettings.getQwenAccounts()
            val freshGalaxy = SecureSettings.getGalaxyAccounts()
            val freshBai = SecureSettings.getBaiAccounts()
            val freshLongCat = SecureSettings.getLongCatAccounts()
            _ui.update { st ->
                val dsMerged = freshDs.map { acc ->
                    st.deepSeekList.firstOrNull { it.account?.id == acc.id }?.copy(account = acc) ?: DeepSeekUi(account = acc)
                }
                val merged = fresh.map { acc ->
                    st.accounts.firstOrNull { it.account.id == acc.id }?.copy(account = acc) ?: AccountUi(acc)
                }
                val qwenMerged = freshQwen.map { acc ->
                    st.qwenList.firstOrNull { it.account?.id == acc.id }?.copy(account = acc) ?: QwenUi(account = acc)
                }
                val galaxyMerged = freshGalaxy.map { acc ->
                    st.galaxyList.firstOrNull { it.account?.id == acc.id }?.copy(account = acc) ?: GalaxyUi(account = acc)
                }
                val baiMerged = freshBai.map { acc ->
                    st.baiList.firstOrNull { it.account?.id == acc.id }?.copy(account = acc) ?: BaiUi(account = acc)
                }
                val longCatMerged = freshLongCat.map { acc ->
                    st.longCatList.firstOrNull { it.account?.id == acc.id }?.copy(account = acc) ?: LongCatUi(account = acc)
                }
                st.copy(deepSeekList = dsMerged, accounts = merged, qwenList = qwenMerged, galaxyList = galaxyMerged, baiList = baiMerged, longCatList = longCatMerged)
            }
            // 并行刷新所有 DeepSeek / OpenCode / Qwen / 智星云 / LongCat 账号
            kotlinx.coroutines.coroutineScope {
                freshDs.forEach { launch { refreshDeepSeekNow(it.id) } }
                fresh.forEach { launch { refreshAccountNow(it.id) } }
                freshQwen.forEach { launch { refreshQwenNow(it.id) } }
                freshGalaxy.forEach { launch { refreshGalaxyNow(it.id) } }
                freshBai.forEach { launch { refreshBaiNow(it.id) } }
                freshLongCat.forEach { launch { refreshLongCatNow(it.id) } }
            }
            val now = System.currentTimeMillis()
            _ui.update { it.copy(refreshing = false, lastUpdated = now) }
            SecureSettings.setLastUpdate("all", now)
        }
    }

    /** 刷新全部 DeepSeek 账号：先重建列表（添加/删除后生效），再并行刷新 */
    fun refreshDeepSeek() {
        viewModelScope.launch {
            val freshDs = SecureSettings.getDeepSeekAccounts()
            _ui.update { st ->
                val dsMerged = freshDs.map { acc ->
                    st.deepSeekList.firstOrNull { it.account?.id == acc.id }?.copy(account = acc) ?: DeepSeekUi(account = acc)
                }
                st.copy(deepSeekList = dsMerged)
            }
            kotlinx.coroutines.coroutineScope {
                freshDs.forEach { launch { refreshDeepSeekNow(it.id) } }
            }
        }
    }

    private suspend fun refreshDeepSeekNow(id: String) {
        val acc = SecureSettings.getDeepSeekAccounts().firstOrNull { it.id == id } ?: return
        _ui.update { st ->
            st.copy(deepSeekList = st.deepSeekList.map {
                if (it.account?.id == id) it.copy(loading = true, error = null) else it
            })
        }
        val bal = deepSeekRepo.balance(acc.apiKey)
        val cost = if (acc.hasToken) deepSeekRepo.cost(acc.platformToken) else null
        _ui.update { st ->
            st.copy(deepSeekList = st.deepSeekList.map {
                if (it.account?.id == id) DeepSeekUi(
                    account = acc,
                    balance = bal.getOrNull(),
                    cost = cost?.getOrNull(),
                    error = listOfNotNull(bal.exceptionOrNull()?.message, cost?.exceptionOrNull()?.message)
                        .joinToString("\n").ifEmpty { null },
                    loading = false,
                ) else it
            })
        }
    }

    /** 刷新单个账号的 Go usage + Zen billing */
    fun refreshAccount(id: String) {
        viewModelScope.launch { refreshAccountNow(id) }
    }

    private suspend fun refreshAccountNow(id: String) {
        val acc = SecureSettings.getAccounts().firstOrNull { it.id == id } ?: return
        _ui.update { st ->
            st.copy(accounts = st.accounts.map {
                if (it.account.id == id) it.copy(loading = true, error = null) else it
            })
        }
        val go = openCodeRepo.goUsage(acc)
        val zen = if (acc.hasZen) openCodeRepo.zenBilling(acc) else null
        val error = listOfNotNull(go.exceptionOrNull()?.message, zen?.exceptionOrNull()?.message)
            .joinToString("\n").ifEmpty { null }
        _ui.update { st ->
            st.copy(accounts = st.accounts.map {
                if (it.account.id == id) AccountUi(acc, go.getOrNull(), zen?.getOrNull(), error, loading = false)
                else it
            })
        }
    }

    /** 刷新单个 Qwen 账号：模型清单（API Key）+ 配额窗口（配了 Cookie 才拉）。 */
    fun refreshQwen(id: String) {
        viewModelScope.launch { refreshQwenNow(id) }
    }

    /**
     * 与 Go 侧 refreshQwen 同语义：plan 失败与 usage 失败合并透出；
     * 无 Cookie 不算 error（UI 灰字提示）。部分失败时既保留已成功数据又透出 error。
     */
    private suspend fun refreshQwenNow(id: String) {
        val acc = SecureSettings.getQwenAccounts().firstOrNull { it.id == id } ?: return
        _ui.update { st ->
            st.copy(qwenList = st.qwenList.map {
                if (it.account?.id == id) it.copy(loading = true, error = null) else it
            })
        }
        val plan = qwenRepo.plan(acc)
        val usage = if (acc.hasCookie) qwenRepo.usage(acc) else null
        val error = listOfNotNull(plan.exceptionOrNull()?.message, usage?.exceptionOrNull()?.message)
            .joinToString("\n").ifEmpty { null }
        _ui.update { st ->
            st.copy(qwenList = st.qwenList.map {
                if (it.account?.id == id) QwenUi(
                    account = acc,
                    plan = plan.getOrNull(),
                    usage = usage?.getOrNull(),
                    error = error,
                    loading = false,
                ) else it
            })
        }
    }

    /**
     * 刷新单个智星云账号：余额/统计/实例/消耗四类并行（同 Go refreshGalaxy）。
     * 任一路失败不中断其余路；部分失败时既保留已成功数据又透出 error。
     */
    fun refreshGalaxy(id: String) {
        viewModelScope.launch { refreshGalaxyNow(id) }
    }

    private suspend fun refreshGalaxyNow(id: String) {
        val acc = SecureSettings.getGalaxyAccounts().firstOrNull { it.id == id } ?: return
        _ui.update { st ->
            st.copy(galaxyList = st.galaxyList.map {
                if (it.account?.id == id) it.copy(loading = true, error = null) else it
            })
        }
        val r = kotlinx.coroutines.coroutineScope {
            val bal = async { galaxyRepo.balance(acc) }
            val cnt = async { galaxyRepo.statusCount(acc) }
            val inst = async { galaxyRepo.instances(acc, GalaxyStatusDefault, GalaxyInstanceLimit) }
            val cost = async { galaxyRepo.cost(acc) }
            GalaxyRefreshResults(bal.await(), cnt.await(), inst.await(), cost.await())
        }
        val error = listOfNotNull(
            r.balance.exceptionOrNull()?.message,
            r.status.exceptionOrNull()?.message,
            r.instances.exceptionOrNull()?.message,
            r.cost.exceptionOrNull()?.message,
        ).joinToString("\n").ifEmpty { null }
        _ui.update { st ->
            st.copy(galaxyList = st.galaxyList.map {
                if (it.account?.id == id) GalaxyUi(
                    account = acc,
                    balance = r.balance.getOrNull(),
                    status = r.status.getOrNull(),
                    instances = r.instances.getOrNull() ?: emptyList(),
                    cost = r.cost.getOrNull(),
                    error = error,
                    loading = false,
                ) else it
            })
        }
    }

    /** 刷新单个白B.AI 账号：模型清单+探活（推理面）与积分额度（控制台）并行，同 Go refreshBai。 */
    fun refreshBai(id: String) {
        viewModelScope.launch { refreshBaiNow(id) }
    }

    private suspend fun refreshBaiNow(id: String) {
        val acc = SecureSettings.getBaiAccounts().firstOrNull { it.id == id } ?: return
        _ui.update { st ->
            st.copy(baiList = st.baiList.map {
                if (it.account?.id == id) it.copy(loading = true, error = null) else it
            })
        }
        val r = kotlinx.coroutines.coroutineScope {
            val plan = async { baiRepo.models(acc.apiKey) }
            val points = async { baiRepo.points(acc.apiKey) }
            plan.await() to points.await()
        }
        var (planR, pointsR) = r
        // 免费通道运行时探活：清单拉成功后探盯梢清单内存在的模型（缺失项不浪费请求）。
        // 探活失败不抖掉清单（Go refreshBai 同口径）。§12 自查：实现必须有调用点。
        var statsR: Result<BaiUsageStats>? = null
        planR.getOrNull()?.let { plan ->
            val present = BaiFreeFlashModels.filterNot { it in plan.missingFreeFlash() }
            val probes = baiRepo.probeFreeFlash(acc.apiKey, present).getOrNull()
            if (probes != null) planR = Result.success(plan.copy(probes = probes))
            // 用量分析 best-effort：失败只影响 stats 段（UI 不显示该卡），不进 error
            statsR = baiRepo.stats(acc.apiKey)
        }
        val error = listOfNotNull(
            planR.exceptionOrNull()?.message,
            pointsR.exceptionOrNull()?.message,
        ).distinct().joinToString("\n").ifEmpty { null }
        _ui.update { st ->
            st.copy(baiList = st.baiList.map {
                if (it.account?.id == id) BaiUi(
                    account = acc,
                    plan = planR.getOrNull(),
                    points = pointsR.getOrNull(),
                    stats = statsR?.getOrNull(),
                    error = error,
                    loading = false,
                ) else it
            })
        }
    }

    /** 刷新单个 LongCat 账号：模型清单 + 余额探活并行，同 Go refreshLongCat。 */
    fun refreshLongCat(id: String) {
        viewModelScope.launch { refreshLongCatNow(id) }
    }

    /**
     * 两路独立：清单失败不抖掉探活结果，探活失败不抖掉清单。
     * 探活 402（余额不足）是正常态而非错误；错误合并口径：认证错误最优先，清单错误次之
     * （非认证探活错误与 Go 同口径静默忽略）。usage 非空 ⇔ 探活路有结论（成功或认证失败）。
     */
    private suspend fun refreshLongCatNow(id: String) {
        val acc = SecureSettings.getLongCatAccounts().firstOrNull { it.id == id } ?: return
        _ui.update { st ->
            st.copy(longCatList = st.longCatList.map {
                if (it.account?.id == id) it.copy(loading = true, error = null) else it
            })
        }
        val r = kotlinx.coroutines.coroutineScope {
            val plan = async { longCatRepo.models(acc.apiKey) }
            val probe = async { longCatRepo.probeBalance(acc.apiKey) }
            plan.await() to probe.await()
        }
        val (planR, probeR) = r
        val probeOk = probeR.getOrNull()
        val probeAuthFailed = probeR.exceptionOrNull()?.message == LongCatAuthError
        val usage = when {
            probeOk != null -> LongCatUsage(balanceOK = probeOk)
            probeAuthFailed -> LongCatUsage(balanceOK = null)
            else -> null
        }
        val error = listOfNotNull(
            if (probeAuthFailed) LongCatAuthError else null,
            planR.exceptionOrNull()?.message,
        ).firstOrNull()
        _ui.update { st ->
            st.copy(longCatList = st.longCatList.map {
                if (it.account?.id == id) LongCatUi(
                    account = acc,
                    plan = planR.getOrNull(),
                    usage = usage,
                    error = error,
                    loading = false,
                ) else it
            })
        }
    }

    companion object {
        /** 单次刷新展示的活跃实例上限（防止大账号拉穿，同 Go GalaxyInstanceLimit） */
        const val GalaxyInstanceLimit = 20
    }
}

/** 智星云四路并行拉取的结果集合。 */
private data class GalaxyRefreshResults(
    val balance: Result<GalaxyBalance>,
    val status: Result<GalaxyStatusCount>,
    val instances: Result<List<GalaxyInstance>>,
    val cost: Result<GalaxyCost>,
)
