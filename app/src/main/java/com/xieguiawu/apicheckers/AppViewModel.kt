package com.xieguiawu.apicheckers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xieguiawu.apicheckers.data.DeepSeekAccount
import com.xieguiawu.apicheckers.data.DeepSeekBalance
import com.xieguiawu.apicheckers.data.DeepSeekCost
import com.xieguiawu.apicheckers.data.DeepSeekRepo
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

/** 全局 UI 状态 */
data class UiState(
    val deepSeekList: List<DeepSeekUi> = emptyList(),
    val accounts: List<AccountUi> = emptyList(),
    val qwenList: List<QwenUi> = emptyList(),
    val galaxyList: List<GalaxyUi> = emptyList(),
    val refreshing: Boolean = false,
    val lastUpdated: Long = 0L,
)

// ── ViewModel ──────────────────────────────────────────────────

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val deepSeekRepo = DeepSeekRepo()
    private val openCodeRepo = OpenCodeRepo()
    private val qwenRepo = QwenRepo()
    private val galaxyRepo = GalaxyRepo()
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
        _ui.update {
            it.copy(
                deepSeekList = dsAccounts,
                accounts = accounts,
                qwenList = qwenAccounts,
                galaxyList = galaxyAccounts,
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
                st.copy(deepSeekList = dsMerged, accounts = merged, qwenList = qwenMerged, galaxyList = galaxyMerged)
            }
            // 并行刷新所有 DeepSeek / OpenCode / Qwen / 智星云账号
            kotlinx.coroutines.coroutineScope {
                freshDs.forEach { launch { refreshDeepSeekNow(it.id) } }
                fresh.forEach { launch { refreshAccountNow(it.id) } }
                freshQwen.forEach { launch { refreshQwenNow(it.id) } }
                freshGalaxy.forEach { launch { refreshGalaxyNow(it.id) } }
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
