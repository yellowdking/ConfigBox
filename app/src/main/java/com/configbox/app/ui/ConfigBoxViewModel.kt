package com.configbox.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.configbox.app.data.ProfileRepository
import com.configbox.app.engine.ConfigEngine
import com.configbox.app.engine.LatencyResult
import com.configbox.app.engine.NodeNetworkTools
import com.configbox.app.engine.ProxyUriParser
import com.configbox.app.model.NodeSource
import com.configbox.app.model.NodeSubscription
import com.configbox.app.model.ProfileMeta
import com.configbox.app.model.ProxyGroupEntry
import com.configbox.app.model.RuleEntry
import com.configbox.app.model.SplitProfile
import com.configbox.app.server.LocalSubServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.UUID

data class ConfigBoxUiState(
    val profiles: List<ProfileMeta> = emptyList(),
    val activeProfile: SplitProfile? = null,
    val compiledYaml: String = "",
    val isLoading: Boolean = false,
    val isTestingAllLatencies: Boolean = false,
    val nodeLatencies: Map<String, LatencyResult> = emptyMap(),
    val serverRunning: Boolean = false,
    val serverPort: Int = 18080,
    val snackbarMessage: String? = null
)

class ConfigBoxViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProfileRepository(application)
    private val mutationMutex = Mutex()

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
            runCatching {
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
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, snackbarMessage = "加载配置失败：${err.message}")
                }
            }
        }
    }

    fun selectProfile(profileId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                val loaded = repository.loadProfile(profileId)
                val compiled = loaded?.let {
                    withContext(Dispatchers.Default) { ConfigEngine.compileConfig(it) }
                }.orEmpty()
                _uiState.update {
                    it.copy(
                        activeProfile = loaded,
                        compiledYaml = compiled,
                        nodeLatencies = emptyMap(),
                        isLoading = false
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, snackbarMessage = "切换配置失败：${err.message}")
                }
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
                        snackbarMessage = "已自动拆分：${saved.proxies.size} 节点 · ${saved.proxyGroups.size} 策略组 · ${saved.rules.size} 规则"
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
                        snackbarMessage = "订阅已拉取并拆分：${saved.proxies.size} 节点 · ${saved.proxyGroups.size} 策略组"
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
                        snackbarMessage = "已仅更新订阅节点，自定义节点、策略组、规则与中转绑定均已保留"
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
            runCatching {
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
            }.onFailure { err ->
                postMessage("删除失败：${err.message}")
            }
        }
    }

    // --- Extra Node Subscriptions (Subscribe to Add & Sync Nodes) ---

    fun addOrUpdateNodeSubscription(
        existingId: String?,
        name: String,
        url: String,
        namePrefix: String = "",
        autoAddToGroups: List<String> = emptyList()
    ) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) {
            postMessage("请输入订阅链接")
            return
        }
        viewModelScope.launch {
            mutationMutex.withLock {
                val profile = _uiState.value.activeProfile ?: return@withLock
                _uiState.update { it.copy(isLoading = true) }
                runCatching {
                    val sub = NodeSubscription(
                        id = existingId ?: UUID.randomUUID().toString().take(8),
                        name = name.trim().ifBlank { "节点订阅" },
                        url = cleanUrl,
                        namePrefix = if (namePrefix.isBlank()) "" else namePrefix.trimStart(),
                        autoAddToGroups = autoAddToGroups.distinct()
                    )
                    val rawContent = repository.fetchSubscriptionYaml(cleanUrl)
                    val (updated, summary) = withContext(Dispatchers.Default) {
                        ConfigEngine.applyNodeSubscription(profile, sub, rawContent)
                    }
                    val saved = repository.saveProfile(updated)
                    val list = repository.listProfiles()
                    val compiled = withContext(Dispatchers.Default) {
                        ConfigEngine.compileConfig(saved)
                    }
                    val skipNote = if (summary.skippedCount > 0) "，跳过 ${summary.skippedCount} 条不支持协议" else ""
                    val groupNote = if (sub.autoAddToGroups.isNotEmpty()) "，已同步至 ${sub.autoAddToGroups.size} 个策略组" else ""
                    _uiState.update {
                        it.copy(
                            profiles = list,
                            activeProfile = saved,
                            compiledYaml = compiled,
                            isLoading = false,
                            snackbarMessage = "订阅「${sub.name}」已同步：共 ${summary.totalNodes} 节点（+${summary.addedCount}/-${summary.removedCount}$skipNote）$groupNote"
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, snackbarMessage = "节点订阅拉取失败：${err.message}")
                    }
                }
            }
        }
    }

    fun refreshNodeSubscription(subscriptionId: String) {
        viewModelScope.launch {
            mutationMutex.withLock {
                val profile = _uiState.value.activeProfile ?: return@withLock
                val sub = profile.nodeSubscriptions.firstOrNull { it.id == subscriptionId } ?: return@withLock
                _uiState.update { it.copy(isLoading = true) }
                runCatching {
                    val rawContent = repository.fetchSubscriptionYaml(sub.url)
                    val (updated, summary) = withContext(Dispatchers.Default) {
                        ConfigEngine.applyNodeSubscription(profile, sub, rawContent)
                    }
                    val saved = repository.saveProfile(updated)
                    val list = repository.listProfiles()
                    val compiled = withContext(Dispatchers.Default) {
                        ConfigEngine.compileConfig(saved)
                    }
                    val skipNote = if (summary.skippedCount > 0) "，跳过 ${summary.skippedCount} 条" else ""
                    _uiState.update {
                        it.copy(
                            profiles = list,
                            activeProfile = saved,
                            compiledYaml = compiled,
                            isLoading = false,
                            snackbarMessage = "已更新「${sub.name}」：共 ${summary.totalNodes} 节点（+${summary.addedCount}/-${summary.removedCount}$skipNote）"
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, snackbarMessage = "更新「${sub.name}」失败：${err.message}")
                    }
                }
            }
        }
    }

    fun refreshAllNodeSubscriptions() {
        viewModelScope.launch {
            mutationMutex.withLock {
                val initial = _uiState.value.activeProfile ?: return@withLock
                if (initial.nodeSubscriptions.isEmpty()) {
                    postMessage("当前配置尚未添加节点订阅")
                    return@withLock
                }
                _uiState.update { it.copy(isLoading = true) }
                var current = initial
                var successCount = 0
                var failCount = 0
                var addedTotal = 0
                var removedTotal = 0
                for (sub in initial.nodeSubscriptions) {
                    val res = runCatching {
                        val rawContent = repository.fetchSubscriptionYaml(sub.url)
                        withContext(Dispatchers.Default) {
                            ConfigEngine.applyNodeSubscription(current, sub, rawContent)
                        }
                    }
                    res.onSuccess { (updated, summary) ->
                        current = updated
                        successCount++
                        addedTotal += summary.addedCount
                        removedTotal += summary.removedCount
                    }.onFailure {
                        failCount++
                    }
                }
                runCatching {
                    val saved = repository.saveProfile(current)
                    val list = repository.listProfiles()
                    val compiled = withContext(Dispatchers.Default) {
                        ConfigEngine.compileConfig(saved)
                    }
                    val failNote = if (failCount > 0) "，$failCount 个失败" else ""
                    _uiState.update {
                        it.copy(
                            profiles = list,
                            activeProfile = saved,
                            compiledYaml = compiled,
                            isLoading = false,
                            snackbarMessage = "全部节点订阅更新完成：成功 $successCount 个$failNote（+$addedTotal/-$removedTotal 节点）"
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, snackbarMessage = "保存订阅更新失败：${err.message}")
                    }
                }
            }
        }
    }

    fun deleteNodeSubscription(subscriptionId: String, removeNodes: Boolean) {
        val profile = _uiState.value.activeProfile ?: return
        val sub = profile.nodeSubscriptions.firstOrNull { it.id == subscriptionId } ?: return
        val updated = ConfigEngine.removeNodeSubscription(profile, subscriptionId, removeNodes)
        val msg = if (removeNodes) {
            "已删除订阅「${sub.name}」及其节点"
        } else {
            "已删除订阅「${sub.name}」，节点已保留为自定义节点"
        }
        mutateActiveProfile(updated, msg)
    }

    // --- Node Latency Testing, Domain-to-IP & Cross-Profile Transfer ---

    fun testNodeLatency(nodeId: String) {
        val node = _uiState.value.activeProfile?.proxies?.firstOrNull { it.id == nodeId } ?: return
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(nodeLatencies = state.nodeLatencies + (nodeId to LatencyResult(testing = true)))
            }
            val result = NodeNetworkTools.testTcpLatency(node.server, node.port)
            _uiState.update { state ->
                state.copy(nodeLatencies = state.nodeLatencies + (nodeId to result))
            }
        }
    }

    fun testAllNodesLatency(nodeIds: List<String>? = null) {
        val profile = _uiState.value.activeProfile ?: return
        val targets = if (nodeIds == null) {
            profile.proxies
        } else {
            val idSet = nodeIds.toSet()
            profile.proxies.filter { it.id in idSet }
        }
        if (targets.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { state ->
                val marking = targets.associate { it.id to LatencyResult(testing = true) }
                state.copy(
                    isTestingAllLatencies = true,
                    nodeLatencies = state.nodeLatencies + marking
                )
            }
            val semaphore = Semaphore(8)
            val results = coroutineScope {
                targets.map { node ->
                    async {
                        semaphore.withPermit {
                            val res = NodeNetworkTools.testTcpLatency(node.server, node.port)
                            _uiState.update { state ->
                                state.copy(nodeLatencies = state.nodeLatencies + (node.id to res))
                            }
                            res
                        }
                    }
                }.awaitAll()
            }
            val okCount = results.count { it.latencyMs != null }
            val failCount = results.size - okCount
            _uiState.update {
                it.copy(
                    isTestingAllLatencies = false,
                    snackbarMessage = "延迟测试完成：$okCount 个可达，$failCount 个超时/不可达"
                )
            }
        }
    }

    suspend fun resolveDomainCandidates(domain: String): Result<List<String>> =
        runCatching { NodeNetworkTools.resolveDomainIps(domain) }

    fun convertNodeDomainToIp(nodeId: String, chosenIp: String? = null) {
        viewModelScope.launch {
            mutationMutex.withLock {
                val profile = _uiState.value.activeProfile ?: return@withLock
                val target = profile.proxies.firstOrNull { it.id == nodeId } ?: return@withLock
                if (chosenIp == null && NodeNetworkTools.isIpAddress(target.server)) {
                    postMessage("节点「${target.name}」已经是 IP 地址：${target.server}")
                    return@withLock
                }
                _uiState.update { it.copy(isLoading = true) }
                runCatching {
                    val ip = chosenIp?.trim()?.takeIf { it.isNotEmpty() }
                        ?: NodeNetworkTools.resolveDomainIps(target.server).first()
                    val updatedNode = ConfigEngine.replaceNodeServerWithIp(target, ip)
                    val updatedProxies = profile.proxies.map { if (it.id == nodeId) updatedNode else it }
                    val saved = repository.saveProfile(profile.copy(proxies = updatedProxies))
                    val list = repository.listProfiles()
                    val compiled = withContext(Dispatchers.Default) { ConfigEngine.compileConfig(saved) }
                    _uiState.update {
                        it.copy(
                            profiles = list,
                            activeProfile = saved,
                            compiledYaml = compiled,
                            isLoading = false,
                            snackbarMessage = "已将「${target.name}」转为 IP：$ip（已自动保留原域名 SNI）"
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, snackbarMessage = "域名转 IP 失败：${err.message}")
                    }
                }
            }
        }
    }

    fun convertAllDomainNodesToIp() {
        viewModelScope.launch {
            mutationMutex.withLock {
                val profile = _uiState.value.activeProfile ?: return@withLock
                val domainNodes = profile.proxies.filter { !NodeNetworkTools.isIpAddress(it.server) }
                if (domainNodes.isEmpty()) {
                    postMessage("当前配置中所有节点均已是 IP 地址")
                    return@withLock
                }
                _uiState.update { it.copy(isLoading = true) }
                val semaphore = Semaphore(8)
                val resolvedById = coroutineScope {
                    domainNodes.map { node ->
                        async {
                            semaphore.withPermit {
                                val ip = runCatching { NodeNetworkTools.resolveDomainIps(node.server).first() }.getOrNull()
                                node.id to ip
                            }
                        }
                    }.awaitAll().toMap()
                }
                var converted = 0
                var failed = 0
                val updatedProxies = profile.proxies.map { node ->
                    if (node.id in resolvedById) {
                        val ip = resolvedById[node.id]
                        if (ip != null) {
                            converted++
                            ConfigEngine.replaceNodeServerWithIp(node, ip)
                        } else {
                            failed++
                            node
                        }
                    } else {
                        node
                    }
                }
                val skipped = profile.proxies.size - domainNodes.size
                runCatching {
                    val saved = repository.saveProfile(profile.copy(proxies = updatedProxies))
                    val list = repository.listProfiles()
                    val compiled = withContext(Dispatchers.Default) { ConfigEngine.compileConfig(saved) }
                    val failNote = if (failed > 0) "，$failed 个解析失败" else ""
                    _uiState.update {
                        it.copy(
                            profiles = list,
                            activeProfile = saved,
                            compiledYaml = compiled,
                            isLoading = false,
                            snackbarMessage = "批量域名转 IP 完成：成功 $converted 个（跳过 $skipped 个 IP$failNote，已保留 SNI）"
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, snackbarMessage = "保存失败：${err.message}")
                    }
                }
            }
        }
    }

    suspend fun loadProfileForPicker(profileId: String): SplitProfile? =
        runCatching { repository.loadProfile(profileId) }.getOrNull()

    fun transferNodeToProfile(
        nodeId: String,
        targetProfileId: String,
        move: Boolean,
        targetGroups: List<String> = emptyList()
    ) {
        viewModelScope.launch {
            mutationMutex.withLock {
                val active = _uiState.value.activeProfile ?: return@withLock
                if (active.meta.id == targetProfileId) {
                    postMessage("目标配置不能与当前配置相同")
                    return@withLock
                }
                val node = active.proxies.firstOrNull { it.id == nodeId } ?: return@withLock
                _uiState.update { it.copy(isLoading = true) }
                runCatching {
                    val target = repository.loadProfile(targetProfileId)
                        ?: throw IllegalStateException("目标配置不存在")
                    val (updatedTarget, copiedNode) = withContext(Dispatchers.Default) {
                        ConfigEngine.copyNodeToProfile(target, node, targetGroups)
                    }
                    repository.saveProfile(updatedTarget)

                    var nextActive = active
                    if (move) {
                        val remainingProxies = active.proxies
                            .filterNot { it.id == nodeId }
                            .map { n ->
                                if (n.dialerProxy == node.name) {
                                    ConfigEngine.updateNodeBasicFields(n, n.name, n.server, n.port, n.udp, null)
                                } else {
                                    n
                                }
                            }
                        val cleanedGroups = removeMemberFromGroups(active.proxyGroups, node.name)
                        val updatedSubs = if (node.subscriptionId != null) {
                            active.nodeSubscriptions.map { sub ->
                                if (sub.id == node.subscriptionId) {
                                    sub.copy(nodeCount = remainingProxies.count { it.subscriptionId == sub.id })
                                } else {
                                    sub
                                }
                            }
                        } else {
                            active.nodeSubscriptions
                        }
                        nextActive = repository.saveProfile(
                            active.copy(
                                proxies = remainingProxies,
                                proxyGroups = cleanedGroups,
                                nodeSubscriptions = updatedSubs
                            )
                        )
                    }

                    val list = repository.listProfiles()
                    val compiled = withContext(Dispatchers.Default) { ConfigEngine.compileConfig(nextActive) }
                    val actionText = if (move) "移动" else "复制"
                    val groupNote = if (targetGroups.isNotEmpty()) "（并加入 ${targetGroups.size} 个策略组）" else ""
                    _uiState.update {
                        it.copy(
                            profiles = list,
                            activeProfile = nextActive,
                            compiledYaml = compiled,
                            isLoading = false,
                            snackbarMessage = "已将节点「${copiedNode.name}」${actionText}到「${updatedTarget.meta.name}」$groupNote"
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, snackbarMessage = "跨配置转移失败：${err.message}")
                    }
                }
            }
        }
    }

    fun importNodesFromProfile(
        sourceProfileId: String,
        nodeIds: Set<String>,
        targetGroups: List<String> = emptyList()
    ) {
        if (nodeIds.isEmpty()) return
        viewModelScope.launch {
            mutationMutex.withLock {
                val active = _uiState.value.activeProfile ?: return@withLock
                if (active.meta.id == sourceProfileId) return@withLock
                _uiState.update { it.copy(isLoading = true) }
                runCatching {
                    val source = repository.loadProfile(sourceProfileId)
                        ?: throw IllegalStateException("来源配置不存在")
                    val selected = source.proxies.filter { it.id in nodeIds }
                    if (selected.isEmpty()) throw IllegalArgumentException("未选择有效节点")

                    var current = active
                    for (node in selected.reversed()) {
                        val (updated, _) = withContext(Dispatchers.Default) {
                            ConfigEngine.copyNodeToProfile(current, node, targetGroups)
                        }
                        current = updated
                    }
                    val saved = repository.saveProfile(current)
                    val list = repository.listProfiles()
                    val compiled = withContext(Dispatchers.Default) { ConfigEngine.compileConfig(saved) }
                    val groupNote = if (targetGroups.isNotEmpty()) "，并加入 ${targetGroups.size} 个策略组" else ""
                    _uiState.update {
                        it.copy(
                            profiles = list,
                            activeProfile = saved,
                            compiledYaml = compiled,
                            isLoading = false,
                            snackbarMessage = "已从「${source.meta.name}」引入 ${selected.size} 个节点$groupNote"
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, snackbarMessage = "引入节点失败：${err.message}")
                    }
                }
            }
        }
    }

    // --- Node & Direct `dialer-proxy` Operations ---

    fun addNodeFromUri(uriText: String) {
        val profile = _uiState.value.activeProfile ?: return
        runCatching {
            val parsedList = ProxyUriParser.parseBatch(uriText)
            if (parsedList.isEmpty()) throw IllegalArgumentException("请输入至少一条有效的节点 URI")
            val existingNames = profile.proxies.map { it.name }.toMutableSet()
            val deduped = parsedList.map { candidate ->
                val unique = ConfigEngine.ensureUniqueNodeName(candidate, existingNames)
                existingNames.add(unique.name)
                unique
            }
            val msg = if (deduped.size == 1) {
                "已添加节点：${deduped.first().name}"
            } else {
                "已批量添加 ${deduped.size} 个节点"
            }
            mutateActiveProfile(profile.copy(proxies = deduped + profile.proxies), msg)
        }.onFailure { err ->
            postMessage("URI 解析失败：${err.message}")
        }
    }

    fun addNodeFromYaml(yamlText: String) {
        val profile = _uiState.value.activeProfile ?: return
        runCatching {
            val parsed = ConfigEngine.parseNodeFromYaml(yamlText, NodeSource.CUSTOM)
            val existingNames = profile.proxies.map { it.name }.toSet()
            val node = ConfigEngine.ensureUniqueNodeName(parsed, existingNames)
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
        val oldNode = profile.proxies.firstOrNull { it.id == nodeId } ?: return
        val cleanNewName = newName.trim().ifBlank { oldNode.name }
        val renamed = oldNode.name != cleanNewName

        var updatedProxies = profile.proxies.map { node ->
            if (node.id == nodeId) {
                ConfigEngine.updateNodeBasicFields(node, cleanNewName, newServer, newPort, newUdp, newDialerProxy)
            } else {
                node
            }
        }

        // Cascade rename to other nodes' dialer-proxy and all proxyGroups
        var updatedGroups = profile.proxyGroups
        if (renamed) {
            updatedProxies = updatedProxies.map { n ->
                if (n.dialerProxy == oldNode.name) {
                    ConfigEngine.updateNodeBasicFields(n, n.name, n.server, n.port, n.udp, cleanNewName)
                } else {
                    n
                }
            }
            updatedGroups = replaceMemberInGroups(updatedGroups, oldNode.name, cleanNewName)
        }

        mutateActiveProfile(
            profile.copy(proxies = updatedProxies, proxyGroups = updatedGroups),
            "节点已更新"
        )
    }

    fun updateNodeRawYaml(nodeId: String, newRawYaml: String) {
        val profile = _uiState.value.activeProfile ?: return
        val existing = profile.proxies.firstOrNull { it.id == nodeId } ?: return
        runCatching {
            val parsed = ConfigEngine.parseNodeFromYaml(newRawYaml, existing.source, existing.subscriptionId).copy(id = nodeId)
            var updatedProxies = profile.proxies.map { if (it.id == nodeId) parsed else it }
            var updatedGroups = profile.proxyGroups
            if (existing.name != parsed.name) {
                updatedProxies = updatedProxies.map { n ->
                    if (n.dialerProxy == existing.name) {
                        ConfigEngine.updateNodeBasicFields(n, n.name, n.server, n.port, n.udp, parsed.name)
                    } else {
                        n
                    }
                }
                updatedGroups = replaceMemberInGroups(updatedGroups, existing.name, parsed.name)
            }
            mutateActiveProfile(
                profile.copy(proxies = updatedProxies, proxyGroups = updatedGroups),
                "节点 YAML 已保存"
            )
        }.onFailure { err ->
            postMessage("YAML 格式有误：${err.message}")
        }
    }

    fun swapNodesInMemory(fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
            val profile = state.activeProfile ?: return@update state
            if (fromIndex !in profile.proxies.indices || toIndex !in profile.proxies.indices) return@update state
            val list = profile.proxies.toMutableList()
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            state.copy(activeProfile = profile.copy(proxies = list))
        }
    }

    fun swapProxyGroupsInMemory(fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
            val profile = state.activeProfile ?: return@update state
            if (fromIndex !in profile.proxyGroups.indices || toIndex !in profile.proxyGroups.indices) return@update state
            val list = profile.proxyGroups.toMutableList()
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            state.copy(activeProfile = profile.copy(proxyGroups = list))
        }
    }

    fun persistCurrentOrder() {
        val profile = _uiState.value.activeProfile ?: return
        mutateActiveProfile(profile, null)
    }

    fun moveNode(nodeId: String, delta: Int) {
        val profile = _uiState.value.activeProfile ?: return
        val list = profile.proxies.toMutableList()
        val idx = list.indexOfFirst { it.id == nodeId }
        val targetIdx = idx + delta
        if (idx < 0 || targetIdx !in list.indices) return
        val item = list.removeAt(idx)
        list.add(targetIdx, item)
        mutateActiveProfile(profile.copy(proxies = list), null)
    }

    fun bindRelayToNodes(exitNodeIds: Set<String>, entryProxy: String) {
        val profile = _uiState.value.activeProfile ?: return
        if (exitNodeIds.isEmpty() || entryProxy.isBlank()) return
        val updatedNodes = ConfigEngine.applyDialerProxy(profile.proxies, exitNodeIds, entryProxy)
        mutateActiveProfile(
            profile.copy(proxies = updatedNodes),
            "已为 ${exitNodeIds.size} 个落地节点写入 dialer-proxy: $entryProxy"
        )
    }

    fun clearNodeRelay(nodeId: String) {
        val profile = _uiState.value.activeProfile ?: return
        val updatedNodes = ConfigEngine.applyDialerProxy(profile.proxies, setOf(nodeId), null)
        mutateActiveProfile(profile.copy(proxies = updatedNodes), "已取消该节点的 dialer-proxy 中转")
    }

    /**
     * Deletes a node AND immediately prunes it from all proxy-groups and any dialer-proxy references.
     */
    fun deleteNode(nodeId: String) {
        val profile = _uiState.value.activeProfile ?: return
        val target = profile.proxies.firstOrNull { it.id == nodeId } ?: return
        val remainingProxies = profile.proxies
            .filterNot { it.id == nodeId }
            .map { n ->
                if (n.dialerProxy == target.name) {
                    ConfigEngine.updateNodeBasicFields(n, n.name, n.server, n.port, n.udp, null)
                } else {
                    n
                }
            }
        val cleanedGroups = removeMemberFromGroups(profile.proxyGroups, target.name)
        val updatedSubs = if (target.subscriptionId != null) {
            profile.nodeSubscriptions.map { sub ->
                if (sub.id == target.subscriptionId) {
                    sub.copy(nodeCount = remainingProxies.count { it.subscriptionId == sub.id })
                } else {
                    sub
                }
            }
        } else {
            profile.nodeSubscriptions
        }
        mutateActiveProfile(
            profile.copy(
                proxies = remainingProxies,
                proxyGroups = cleanedGroups,
                nodeSubscriptions = updatedSubs
            ),
            "已删除节点「${target.name}」并同步清理策略组引用"
        )
    }

    // --- Proxy Group Operations ---

    fun upsertProxyGroup(
        existingId: String?,
        name: String,
        type: String,
        proxies: List<String>,
        url: String?,
        interval: Int?
    ) {
        val profile = _uiState.value.activeProfile ?: return
        val oldGroup = existingId?.let { id -> profile.proxyGroups.firstOrNull { it.id == id } }
        val built = if (existingId != null) {
            ConfigEngine.buildProxyGroup(
                id = existingId,
                name = name,
                type = type,
                proxies = proxies,
                url = url,
                interval = interval,
                existingRawYaml = oldGroup?.rawYaml
            )
        } else {
            ConfigEngine.buildProxyGroup(name = name, type = type, proxies = proxies, url = url, interval = interval)
        }
        val exists = oldGroup != null
        var updatedGroups = if (exists) {
            profile.proxyGroups.map { if (it.id == existingId) built else it }
        } else {
            listOf(built) + profile.proxyGroups
        }

        // If renamed, cascade to other groups, rules, node dialer-proxy, and nodeSubscriptions
        var updatedProxies = profile.proxies
        var updatedRules = profile.rules
        var updatedSubs = profile.nodeSubscriptions
        if (oldGroup != null && oldGroup.name != built.name) {
            updatedGroups = replaceMemberInGroups(updatedGroups, oldGroup.name, built.name)
            updatedProxies = updatedProxies.map { n ->
                if (n.dialerProxy == oldGroup.name) {
                    ConfigEngine.updateNodeBasicFields(n, n.name, n.server, n.port, n.udp, built.name)
                } else {
                    n
                }
            }
            updatedRules = updatedRules.map { r ->
                if (r.target == oldGroup.name) r.copy(target = built.name) else r
            }
            updatedSubs = updatedSubs.map { sub ->
                if (oldGroup.name in sub.autoAddToGroups) {
                    sub.copy(autoAddToGroups = sub.autoAddToGroups.map { if (it == oldGroup.name) built.name else it }.distinct())
                } else {
                    sub
                }
            }
        }

        mutateActiveProfile(
            profile.copy(
                proxies = updatedProxies,
                proxyGroups = updatedGroups,
                rules = updatedRules,
                nodeSubscriptions = updatedSubs
            ),
            if (exists) "策略组已更新" else "已新建策略组：${built.name}"
        )
    }

    fun moveProxyGroup(groupId: String, delta: Int) {
        val profile = _uiState.value.activeProfile ?: return
        val list = profile.proxyGroups.toMutableList()
        val idx = list.indexOfFirst { it.id == groupId }
        val targetIdx = idx + delta
        if (idx < 0 || targetIdx !in list.indices) return
        val item = list.removeAt(idx)
        list.add(targetIdx, item)
        mutateActiveProfile(profile.copy(proxyGroups = list), null)
    }

    fun deleteProxyGroup(groupId: String) {
        val profile = _uiState.value.activeProfile ?: return
        val target = profile.proxyGroups.firstOrNull { it.id == groupId } ?: return
        val remainingGroups = removeMemberFromGroups(
            profile.proxyGroups.filterNot { it.id == groupId },
            target.name
        )
        val updatedProxies = profile.proxies.map { n ->
            if (n.dialerProxy == target.name) {
                ConfigEngine.updateNodeBasicFields(n, n.name, n.server, n.port, n.udp, null)
            } else {
                n
            }
        }
        val updatedSubs = profile.nodeSubscriptions.map { sub ->
            if (target.name in sub.autoAddToGroups) {
                sub.copy(autoAddToGroups = sub.autoAddToGroups.filterNot { it == target.name })
            } else {
                sub
            }
        }
        mutateActiveProfile(
            profile.copy(
                proxies = updatedProxies,
                proxyGroups = remainingGroups,
                nodeSubscriptions = updatedSubs
            ),
            "已删除策略组「${target.name}」"
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

    private fun removeMemberFromGroups(
        groups: List<ProxyGroupEntry>,
        removedName: String
    ): List<ProxyGroupEntry> {
        return groups.map { group ->
            if (removedName in group.proxies) {
                val pruned = group.proxies.filterNot { it == removedName }
                ConfigEngine.buildProxyGroup(
                    id = group.id,
                    name = group.name,
                    type = group.type,
                    proxies = pruned,
                    url = group.url,
                    interval = group.interval,
                    existingRawYaml = group.rawYaml
                )
            } else {
                group
            }
        }
    }

    private fun replaceMemberInGroups(
        groups: List<ProxyGroupEntry>,
        oldName: String,
        newName: String
    ): List<ProxyGroupEntry> {
        return groups.map { group ->
            if (oldName in group.proxies) {
                val replaced = group.proxies.map { if (it == oldName) newName else it }.distinct()
                ConfigEngine.buildProxyGroup(
                    id = group.id,
                    name = group.name,
                    type = group.type,
                    proxies = replaced,
                    url = group.url,
                    interval = group.interval,
                    existingRawYaml = group.rawYaml
                )
            } else {
                group
            }
        }
    }

    private fun mutateActiveProfile(updated: SplitProfile, successMsg: String?) {
        viewModelScope.launch {
            mutationMutex.withLock {
                runCatching {
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
                }.onFailure { err ->
                    postMessage("保存失败：${err.message}")
                }
            }
        }
    }
}
