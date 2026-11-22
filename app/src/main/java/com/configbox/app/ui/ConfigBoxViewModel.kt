package com.configbox.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.configbox.app.data.ProfileRepository
import com.configbox.app.engine.ConfigEngine
import com.configbox.app.engine.ProxyUriParser
import com.configbox.app.model.NodeSource
import com.configbox.app.model.ProfileMeta
import com.configbox.app.model.ProxyNode
import com.configbox.app.model.RelayGroup
import com.configbox.app.model.RuleEntry
import com.configbox.app.model.SplitProfile
import com.configbox.app.server.LocalSubServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConfigBoxUiState(
    val profiles: List<ProfileMeta> = emptyList(),
    val activeProfile: SplitProfile? = null,
    val compiledYaml: String = "",
    val isLoading: Boolean = false,
    val serverRunning: Boolean = false,
    val serverPort: Int = 18080,
    val snackbarMessage: String? = null
)

class ConfigBoxViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProfileRepository(application)

    private val localServer = LocalSubServer(port = 18080) { profileId ->
        val profile = repository.loadProfile(profileId) ?: return@LocalSubServer null
        val yaml = ConfigEngine.compileConfig(profile)
        profile.meta.name to yaml
    }

    private val _uiState = MutableStateFlow(ConfigBoxUiState())
    val uiState: StateFlow<ConfigBoxUiState> = _uiState.asStateFlow()

    init {
        loadAllProfiles()
    }

    override fun onCleared() {
        localServer.stop()
        super.onCleared()
    }

    fun clearMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun postMessage(msg: String) {
        _uiState.update { it.copy(snackbarMessage = msg) }
    }

    fun loadAllProfiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val list = repository.listProfiles()
            val currentId = _uiState.value.activeProfile?.meta?.id ?: list.firstOrNull()?.id
            val loaded = currentId?.let { repository.loadProfile(it) }
            val compiled = loaded?.let {
                withContext(Dispatchers.Default) { ConfigEngine.compileConfig(it) }
            }.orEmpty()
            _uiState.update {
                it.copy(
                    profiles = list,
                    activeProfile = loaded,
                    compiledYaml = compiled,
                    isLoading = false
                )
            }
        }
    }

    fun selectProfile(profileId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val loaded = repository.loadProfile(profileId)
            val compiled = loaded?.let {
                withContext(Dispatchers.Default) { ConfigEngine.compileConfig(it) }
            }.orEmpty()
            _uiState.update {
                it.copy(
                    activeProfile = loaded,
                    compiledYaml = compiled,
                    isLoading = false
                )
            }
        }
    }

    fun importFromYaml(name: String, rawYaml: String, subscriptionUrl: String = "") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                val split = withContext(Dispatchers.Default) {
                    ConfigEngine.splitConfig(rawYaml, name, subscriptionUrl)
                }
                val saved = repository.saveProfile(split)
                val list = repository.listProfiles()
                val compiled = withContext(Dispatchers.Default) {
                    ConfigEngine.compileConfig(saved)
                }
                _uiState.update {
                    it.copy(
                        profiles = list,
                        activeProfile = saved,
                        compiledYaml = compiled,
                        isLoading = false,
                        snackbarMessage = "已自动拆分：${saved.proxies.size} 个节点 · ${saved.proxyGroups.size} 个策略组 · ${saved.rules.size} 条规则"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, snackbarMessage = "导入失败：${err.message}")
                }
            }
        }
    }

    fun importFromUrl(name: String, url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                val rawYaml = repository.fetchSubscriptionYaml(url)
                val split = withContext(Dispatchers.Default) {
                    ConfigEngine.splitConfig(rawYaml, name.ifBlank { "订阅配置" }, url)
                }
                val saved = repository.saveProfile(split)
                val list = repository.listProfiles()
                val compiled = withContext(Dispatchers.Default) {
                    ConfigEngine.compileConfig(saved)
                }
                _uiState.update {
                    it.copy(
                        profiles = list,
                        activeProfile = saved,
                        compiledYaml = compiled,
                        isLoading = false,
                        snackbarMessage = "订阅已拉取并拆分：${saved.proxies.size} 个节点 · ${saved.rules.size} 条规则"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, snackbarMessage = "订阅拉取失败：${err.message}")
                }
            }
        }
    }

    fun importDemoProfile() {
        importFromYaml(
            name = "示例配置 (含家宽落地)",
            rawYaml = ConfigEngine.SAMPLE_CLASH_YAML
        )
    }

    fun refreshSubscriptionNodes(profileId: String) {
        viewModelScope.launch {
            val target = repository.loadProfile(profileId) ?: return@launch
            if (target.meta.subscriptionUrl.isBlank()) {
                postMessage("该配置未绑定远程订阅链接")
                return@launch
            }
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                val freshYaml = repository.fetchSubscriptionYaml(target.meta.subscriptionUrl)
                val refreshed = withContext(Dispatchers.Default) {
                    ConfigEngine.refreshSubscriptionNodes(target, freshYaml)
                }
                val saved = repository.saveProfile(refreshed)
                val list = repository.listProfiles()
                val isActive = _uiState.value.activeProfile?.meta?.id == saved.meta.id
                val compiled = if (isActive) {
                    withContext(Dispatchers.Default) { ConfigEngine.compileConfig(saved) }
                } else {
                    _uiState.value.compiledYaml
                }
                _uiState.update {
                    it.copy(
                        profiles = list,
                        activeProfile = if (isActive) saved else it.activeProfile,
                        compiledYaml = compiled,
                        isLoading = false,
                        snackbarMessage = "已仅更新订阅节点，自定义节点、规则与中转组均已保留"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, snackbarMessage = "更新失败：${err.message}")
                }
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            repository.deleteProfile(profileId)
            val list = repository.listProfiles()
            val next = if (_uiState.value.activeProfile?.meta?.id == profileId) {
                list.firstOrNull()?.let { repository.loadProfile(it.id) }
            } else {
                _uiState.value.activeProfile
            }
            val compiled = next?.let {
                withContext(Dispatchers.Default) { ConfigEngine.compileConfig(it) }
            }.orEmpty()
            _uiState.update {
                it.copy(
                    profiles = list,
                    activeProfile = next,
                    compiledYaml = compiled,
                    snackbarMessage = "已删除配置"
                )
            }
        }
    }

    // --- Node Operations ---

    fun addNodeFromUri(uriText: String) {
        val profile = _uiState.value.activeProfile ?: return
        runCatching {
            val node = ProxyUriParser.parse(uriText)
            mutateActiveProfile(profile.copy(proxies = listOf(node) + profile.proxies), "已添加节点：${node.name}")
        }.onFailure { err ->
            postMessage("URI 解析失败：${err.message}")
        }
    }

    fun addNodeFromYaml(yamlText: String) {
        val profile = _uiState.value.activeProfile ?: return
        runCatching {
            val node = ConfigEngine.parseNodeFromYaml(yamlText, NodeSource.CUSTOM)
            mutateActiveProfile(profile.copy(proxies = listOf(node) + profile.proxies), "已添加节点：${node.name}")
        }.onFailure { err ->
            postMessage("YAML 解析失败：${err.message}")
        }
    }

    fun updateNodeBasic(
        nodeId: String,
        newName: String,
        newServer: String,
        newPort: Int,
        newUdp: Boolean,
        newDialerProxy: String?
    ) {
        val profile = _uiState.value.activeProfile ?: return
        val updatedList = profile.proxies.map { node ->
            if (node.id == nodeId) {
                ConfigEngine.updateNodeBasicFields(node, newName, newServer, newPort, newUdp, newDialerProxy)
            } else {
                node
            }
        }
        mutateActiveProfile(profile.copy(proxies = updatedList), "节点已更新")
    }

    fun updateNodeRawYaml(nodeId: String, newRawYaml: String) {
        val profile = _uiState.value.activeProfile ?: return
        val existing = profile.proxies.firstOrNull { it.id == nodeId } ?: return
        runCatching {
            val parsed = ConfigEngine.parseNodeFromYaml(newRawYaml, existing.source).copy(id = nodeId)
            val updatedList = profile.proxies.map { if (it.id == nodeId) parsed else it }
            mutateActiveProfile(profile.copy(proxies = updatedList), "节点 YAML 已保存")
        }.onFailure { err ->
            postMessage("YAML 格式有误：${err.message}")
        }
    }

    fun deleteNode(nodeId: String) {
        val profile = _uiState.value.activeProfile ?: return
        mutateActiveProfile(
            profile.copy(proxies = profile.proxies.filterNot { it.id == nodeId }),
            "已删除节点"
        )
    }

    // --- Rule Operations ---

    fun addRule(rule: RuleEntry) {
        val profile = _uiState.value.activeProfile ?: return
        mutateActiveProfile(profile.copy(rules = listOf(rule) + profile.rules), "已添加规则")
    }

    fun updateRule(rule: RuleEntry) {
        val profile = _uiState.value.activeProfile ?: return
        val updated = profile.rules.map { if (it.id == rule.id) rule else it }
        mutateActiveProfile(profile.copy(rules = updated), "规则已更新")
    }

    fun toggleRule(ruleId: String) {
        val profile = _uiState.value.activeProfile ?: return
        val updated = profile.rules.map { if (it.id == ruleId) it.copy(enabled = !it.enabled) else it }
        mutateActiveProfile(profile.copy(rules = updated), null)
    }

    fun moveRule(ruleId: String, delta: Int) {
        val profile = _uiState.value.activeProfile ?: return
        val list = profile.rules.toMutableList()
        val idx = list.indexOfFirst { it.id == ruleId }
        val targetIdx = idx + delta
        if (idx < 0 || targetIdx !in list.indices) return
        val item = list.removeAt(idx)
        list.add(targetIdx, item)
        mutateActiveProfile(profile.copy(rules = list), null)
    }

    fun deleteRule(ruleId: String) {
        val profile = _uiState.value.activeProfile ?: return
        mutateActiveProfile(profile.copy(rules = profile.rules.filterNot { it.id == ruleId }), "已删除规则")
    }

    // --- Relay Group (`dialer-proxy`) Operations ---

    fun upsertRelayGroup(relay: RelayGroup) {
        val profile = _uiState.value.activeProfile ?: return
        val exists = profile.relayGroups.any { it.id == relay.id }
        val updated = if (exists) {
            profile.relayGroups.map { if (it.id == relay.id) relay else it }
        } else {
            listOf(relay) + profile.relayGroups
        }
        mutateActiveProfile(
            profile.copy(relayGroups = updated),
            if (exists) "中转组已更新" else "已创建 dialer-proxy 中转组：${relay.name}"
        )
    }

    fun toggleRelayGroup(relayId: String) {
        val profile = _uiState.value.activeProfile ?: return
        val updated = profile.relayGroups.map { if (it.id == relayId) it.copy(enabled = !it.enabled) else it }
        mutateActiveProfile(profile.copy(relayGroups = updated), null)
    }

    fun deleteRelayGroup(relayId: String) {
        val profile = _uiState.value.activeProfile ?: return
        mutateActiveProfile(
            profile.copy(relayGroups = profile.relayGroups.filterNot { it.id == relayId }),
            "已删除中转组"
        )
    }

    fun previewRelayYaml(relay: RelayGroup): String {
        val profile = _uiState.value.activeProfile ?: return ""
        return ConfigEngine.previewRelayGroupYaml(profile, relay)
    }

    // --- Local Subscription Server ---

    fun toggleLocalServer() {
        if (localServer.isRunning) {
            localServer.stop()
            _uiState.update { it.copy(serverRunning = false, snackbarMessage = "本地订阅服务已停止") }
        } else {
            localServer.start(viewModelScope)
                .onSuccess { port ->
                    _uiState.update {
                        it.copy(
                            serverRunning = true,
                            serverPort = port,
                            snackbarMessage = "本地订阅服务已启动 (127.0.0.1:$port)"
                        )
                    }
                }
                .onFailure { err ->
                    postMessage("启动失败：${err.message}")
                }
        }
    }

    fun getLocalSubUrl(): String? {
        val id = _uiState.value.activeProfile?.meta?.id ?: return null
        return localServer.subscriptionUrl(id)
    }

    private fun mutateActiveProfile(updated: SplitProfile, successMsg: String?) {
        viewModelScope.launch {
            val saved = repository.saveProfile(updated)
            val list = repository.listProfiles()
            val compiled = withContext(Dispatchers.Default) {
                ConfigEngine.compileConfig(saved)
            }
            _uiState.update {
                it.copy(
                    profiles = list,
                    activeProfile = saved,
                    compiledYaml = compiled,
                    snackbarMessage = successMsg ?: it.snackbarMessage
                )
            }
        }
    }
}
