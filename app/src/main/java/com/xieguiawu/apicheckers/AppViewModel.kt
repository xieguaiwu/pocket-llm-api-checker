package com.xieguiawu.apicheckers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xieguiawu.apicheckers.data.DeepSeekBalance
import com.xieguiawu.apicheckers.data.DeepSeekCost
import com.xieguiawu.apicheckers.data.DeepSeekRepo
import com.xieguiawu.apicheckers.data.GoUsage
import com.xieguiawu.apicheckers.data.OpenCodeRepo
import com.xieguiawu.apicheckers.data.SecureSettings
import com.xieguiawu.apicheckers.data.ZenBilling
import com.xieguiawu.apicheckers.data.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── UI 状态模型 ────────────────────────────────────────────────

/** DeepSeek 区块 UI 状态 */
data class DeepSeekUi(
    val keyConfigured: Boolean = false,
    val balance: DeepSeekBalance? = null,
    val cost: DeepSeekCost? = null,
    val error: String? = null,
)

/** 单个 OpenCode 账号的 UI 状态 */
data class AccountUi(
    val account: Account,
    val goUsage: GoUsage? = null,
    val zenBilling: ZenBilling? = null,
    val error: String? = null,
    val loading: Boolean = false,
)

/** 全局 UI 状态 */
data class UiState(
    val deepSeek: DeepSeekUi = DeepSeekUi(),
    val accounts: List<AccountUi> = emptyList(),
    val refreshing: Boolean = false,
    val lastUpdated: Long = 0L,
)

// ── ViewModel ──────────────────────────────────────────────────

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val deepSeekRepo = DeepSeekRepo()
    private val openCodeRepo = OpenCodeRepo()
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
        val dk = SecureSettings.getDeepSeekKey()
        val accounts = SecureSettings.getAccounts().map { AccountUi(it) }
        _ui.update { it.copy(deepSeek = DeepSeekUi(keyConfigured = dk.isNotBlank()), accounts = accounts) }
    }

    /** 刷新全部数据（DeepSeek + 所有账号，并行）。重入保护：刷新中忽略再次触发 */
    fun refreshAll() {
        if (_ui.value.refreshing) return
        viewModelScope.launch {
            _ui.update { it.copy(refreshing = true) }
            // 以本地存储为准重建账号列表（设置页新增/删除后立即生效）；
            // 保留旧数据避免刷新期间界面闪断（P2-15）
            val fresh = SecureSettings.getAccounts()
            _ui.update { st ->
                val merged = fresh.map { acc ->
                    st.accounts.firstOrNull { it.account.id == acc.id }?.copy(account = acc) ?: AccountUi(acc)
                }
                st.copy(accounts = merged)
            }
            refreshDeepSeekNow()
            // 并行刷新所有账号（3 账号 × 2 请求 ≈ 1 次网络延迟，而非串行 6 次）
            kotlinx.coroutines.coroutineScope {
                fresh.forEach { launch { refreshAccountNow(it.id) } }
            }
            val now = System.currentTimeMillis()
            _ui.update { it.copy(refreshing = false, lastUpdated = now) }
            SecureSettings.setLastUpdate("all", now)
        }
    }

    /** 刷新 DeepSeek 余额 + 消费明细 */
    fun refreshDeepSeek() {
        viewModelScope.launch { refreshDeepSeekNow() }
    }

    private suspend fun refreshDeepSeekNow() {
        val key = SecureSettings.getDeepSeekKey()
        if (key.isBlank()) {
            _ui.update { it.copy(deepSeek = DeepSeekUi(keyConfigured = false, error = "未配置 DeepSeek API Key")) }
            return
        }
        _ui.update { it.copy(deepSeek = it.deepSeek.copy(keyConfigured = true, error = null)) }
        val bal = deepSeekRepo.balance(key)
        val token = SecureSettings.getPlatformToken()
        val cost = if (token.isNotBlank()) deepSeekRepo.cost(token) else null
        _ui.update {
            it.copy(
                deepSeek = DeepSeekUi(
                    keyConfigured = true,
                    balance = bal.getOrNull(),
                    cost = cost?.getOrNull(),
                    error = listOfNotNull(bal.exceptionOrNull()?.message, cost?.exceptionOrNull()?.message)
                        .joinToString("\n").ifEmpty { null },
                ),
            )
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
}
