package com.configbox.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.configbox.app.engine.LatencyResult
import com.configbox.app.engine.NodeNetworkTools
import com.configbox.app.model.GlobalPoolNode
import com.configbox.app.model.NodeSource
import com.configbox.app.model.NodeSubscription
import com.configbox.app.model.ProfileMeta
import com.configbox.app.model.ProxyNode
import com.configbox.app.model.SplitProfile
import com.configbox.app.ui.ConfigBoxUiState
import com.configbox.app.ui.ConfigBoxViewModel
import com.configbox.app.ui.components.draggedItemVisual
import com.configbox.app.ui.components.longPressReorderContainer
import com.configbox.app.ui.components.rememberDragReorderState
import com.configbox.app.ui.theme.AmberBadge
import com.configbox.app.ui.theme.BorderGray
import com.configbox.app.ui.theme.EmeraldActive
import com.configbox.app.ui.theme.PureWhite
import com.configbox.app.ui.theme.RoseDanger
import com.configbox.app.ui.theme.SkyAccent
import com.configbox.app.ui.theme.SlateInk
import com.configbox.app.ui.theme.SlateMuted
import com.configbox.app.ui.theme.SoftSurface
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NodesScreen(
    uiState: ConfigBoxUiState,
    viewModel: ConfigBoxViewModel
) {
    val active = uiState.activeProfile
    if (active == null) {
        EmptyProfileNotice("请先在「配置」页导入或选择一个配置文件")
        return
    }

    var subTab by remember { mutableIntStateOf(0) } // 0 = 当前节点, 1 = 全局节点池, 2 = 节点订阅, 3 = 中转绑定
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("全部") }
    var showAddNodeDialog by remember { mutableStateOf(false) }
    var showRelayBindDialog by remember { mutableStateOf(false) }
    var editingNode by remember { mutableStateOf<ProxyNode?>(null) }
    var transferringNode by remember { mutableStateOf<ProxyNode?>(null) }
    var showImportFromProfileDialog by remember { mutableStateOf(false) }
    var showBatchDomainToIpConfirm by remember { mutableStateOf(false) }
    var showAddSubDialog by remember { mutableStateOf(false) }
    var editingSub by remember { mutableStateOf<NodeSubscription?>(null) }
    var deletingSub by remember { mutableStateOf<NodeSubscription?>(null) }

    val otherProfiles = remember(uiState.profiles, active.meta.id) {
        uiState.profiles.filter { it.id != active.meta.id }
    }
    val domainNodeCount = remember(active.proxies) {
        active.proxies.count { !NodeNetworkTools.isIpAddress(it.server) }
    }

    val subNameById = remember(active.nodeSubscriptions) {
        active.nodeSubscriptions.associate { it.id to it.name }
    }
    val subIdByFilterLabel = remember(active.nodeSubscriptions) {
        active.nodeSubscriptions.associate { "订阅:${it.name}" to it.id }
    }

    val protocolFilters = remember(active.proxies, active.nodeSubscriptions) {
        buildList {
            add("全部")
            add("已中转")
            add("自定义")
            active.nodeSubscriptions.forEach { add("订阅:${it.name}") }
            addAll(active.proxies.map { it.type.uppercase() }.distinct().sorted())
        }
    }

    val effectiveFilter = if (selectedFilter in protocolFilters) selectedFilter else "全部"

    val filteredNodes = remember(active.proxies, searchQuery, effectiveFilter, subIdByFilterLabel) {
        active.proxies.filter { node ->
            val matchFilter = when {
                effectiveFilter == "全部" -> true
                effectiveFilter == "已中转" -> !node.dialerProxy.isNullOrBlank()
                effectiveFilter == "自定义" -> node.source == NodeSource.CUSTOM
                effectiveFilter in subIdByFilterLabel -> node.subscriptionId == subIdByFilterLabel[effectiveFilter]
                else -> node.type.equals(effectiveFilter, ignoreCase = true)
            }
            val matchQuery = searchQuery.isBlank() ||
                node.name.contains(searchQuery, ignoreCase = true) ||
                node.server.contains(searchQuery, ignoreCase = true)
            matchFilter && matchQuery
        }
    }

    val relayedNodes = remember(active.proxies) {
        active.proxies.filter { !it.dialerProxy.isNullOrBlank() }
    }

    val groupNames = remember(active.proxyGroups) {
        active.proxyGroups.map { it.name }
    }

    val entryCandidates = remember(active.proxyGroups, active.proxies) {
        active.proxyGroups.map { it.name } + active.proxies.map { it.name }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (uiState.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = SkyAccent
                )
            }

            TabRow(selectedTabIndex = subTab, containerColor = PureWhite) {
                Tab(
                    selected = subTab == 0,
                    onClick = { subTab = 0 },
                    text = { Text("当前(${active.proxies.size})", fontSize = 11.sp, maxLines = 1) }
                )
                Tab(
                    selected = subTab == 1,
                    onClick = { subTab = 1 },
                    text = { Text("节点池(${uiState.globalNodePool.size})", fontSize = 11.sp, maxLines = 1) }
                )
                Tab(
                    selected = subTab == 2,
                    onClick = { subTab = 2 },
                    text = { Text("订阅(${active.nodeSubscriptions.size})", fontSize = 11.sp, maxLines = 1) }
                )
                Tab(
                    selected = subTab == 3,
                    onClick = { subTab = 3 },
                    text = { Text("中转(${relayedNodes.size})", fontSize = 11.sp, maxLines = 1) }
                )
            }

            when (subTab) {
                0 -> {
                    Surface(color = PureWhite) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("搜索节点名称或服务器地址...", fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                protocolFilters.forEach { tag ->
                                    FilterChip(
                                        selected = effectiveFilter == tag,
                                        onClick = { selectedFilter = tag },
                                        label = { Text(tag, fontSize = 12.sp) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.testAllNodesLatency(filteredNodes.map { it.id }) },
                                    enabled = !uiState.isTestingAllLatencies && filteredNodes.isNotEmpty(),
                                    border = BorderStroke(1.dp, BorderGray),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Outlined.Speed, null, Modifier.size(15.dp), tint = SkyAccent)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (uiState.isTestingAllLatencies) "测速中..." else "一键测速",
                                        fontSize = 12.sp,
                                        color = SlateInk
                                    )
                                }
                                OutlinedButton(
                                    onClick = { showBatchDomainToIpConfirm = true },
                                    enabled = !uiState.isLoading && domainNodeCount > 0,
                                    border = BorderStroke(1.dp, BorderGray),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Outlined.Dns, null, Modifier.size(15.dp), tint = EmeraldActive)
                                    Spacer(Modifier.width(4.dp))
                                    Text("域名转IP ($domainNodeCount)", fontSize = 12.sp, color = SlateInk)
                                }
                                if (otherProfiles.isNotEmpty()) {
                                    OutlinedButton(
                                        onClick = { showImportFromProfileDialog = true },
                                        enabled = !uiState.isLoading,
                                        border = BorderStroke(1.dp, BorderGray),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(15.dp), tint = SkyAccent)
                                        Spacer(Modifier.width(4.dp))
                                        Text("跨配置引入", fontSize = 12.sp, color = SlateInk)
                                    }
                                }
                            }
                        }
                    }

                    val canDragReorder = searchQuery.isBlank() && effectiveFilter == "全部"
                    val nodeListState = rememberLazyListState()
                    val nodeReorderState = rememberDragReorderState(listState = nodeListState, headerCount = 1)

                    LazyColumn(
                        state = nodeListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .longPressReorderContainer(
                                reorderState = nodeReorderState,
                                enabled = canDragReorder,
                                itemCount = filteredNodes.size,
                                onSwap = { from, to -> viewModel.swapNodesInMemory(from, to) },
                                onDragFinished = { viewModel.persistCurrentOrder() }
                            ),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 84.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(key = "nodes_hint") {
                            Text(
                                text = if (canDragReorder) "长按任意节点卡片可上下拖动排序 · 点击编辑 · 点延迟标签测速" else "清除搜索和筛选后可长按拖动排序",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        itemsIndexed(filteredNodes, key = { _, node -> node.id }) { idx, node ->
                            val isDragging = nodeReorderState.draggedDataIndex == idx
                            NodeCard(
                                node = node,
                                subscriptionName = node.subscriptionId?.let { subNameById[it] },
                                latency = uiState.nodeLatencies[node.id],
                                canTransfer = otherProfiles.isNotEmpty(),
                                isDragging = isDragging,
                                modifier = Modifier.draggedItemVisual(isDragging, nodeReorderState.dragOffsetY),
                                showDragHandle = canDragReorder,
                                onClick = { editingNode = node },
                                onTestLatency = { viewModel.testNodeLatency(node.id) },
                                onConvertDomainToIp = { viewModel.convertNodeDomainToIp(node.id) },
                                onTransfer = { transferringNode = node },
                                onDelete = { viewModel.deleteNode(node.id) }
                            )
                        }
                    }
                }

                1 -> {
                    GlobalNodePoolPane(
                        activeProfileName = active.meta.name,
                        pool = uiState.globalNodePool,
                        poolLatencies = uiState.poolLatencies,
                        isTestingPool = uiState.isTestingPoolLatencies,
                        currentGroupNames = groupNames,
                        onTestOne = { viewModel.testPoolNodeLatency(it) },
                        onTestBatch = { viewModel.testGlobalPoolLatencies(it) },
                        onAddToActive = { keys, targetGroups ->
                            viewModel.addNodesFromGlobalPool(keys, targetGroups)
                        }
                    )
                }

                2 -> {
                    NodeSubscriptionsPane(
                        subscriptions = active.nodeSubscriptions,
                        isLoading = uiState.isLoading,
                        onRefreshAll = { viewModel.refreshAllNodeSubscriptions() },
                        onRefreshOne = { viewModel.refreshNodeSubscription(it.id) },
                        onEdit = { editingSub = it },
                        onDelete = { deletingSub = it }
                    )
                }

                else -> {
                    // Direct 2-hop dialer-proxy binding screen (Entry -> Exit)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 84.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Surface(
                                color = PureWhite,
                                border = BorderStroke(1.dp, BorderGray),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text("简洁中转绑定（入口前置 → 出口落地）", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "配置后直接在出口落地节点上写入 dialer-proxy，不生成多余副本和隐藏策略组；如需独立分组，直接在「策略组」页手动创建即可。",
                                        fontSize = 12.sp,
                                        color = SlateMuted
                                    )
                                }
                            }
                        }

                        if (relayedNodes.isEmpty()) {
                            item {
                                Surface(
                                    color = PureWhite,
                                    border = BorderStroke(1.dp, BorderGray),
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "暂无已配置中转的落地节点，点击右下角按钮为出口落地节点绑定入口前置代理。",
                                        modifier = Modifier.padding(20.dp),
                                        fontSize = 13.sp,
                                        color = SlateMuted
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(relayedNodes, key = { _, it -> it.id }) { _, node ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = PureWhite),
                                    border = BorderStroke(1.5.dp, EmeraldActive),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "入口 [${node.dialerProxy}] → 出口 [${node.name}]",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = SlateInk
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "dialer-proxy: ${node.dialerProxy}  ·  ${node.server}:${node.port}",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = SlateMuted
                                            )
                                        }
                                        IconButton(onClick = { viewModel.clearNodeRelay(node.id) }) {
                                            Icon(Icons.Outlined.LinkOff, contentDescription = "解绑中转", tint = RoseDanger)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (subTab != 1) {
            FloatingActionButton(
                onClick = {
                    when (subTab) {
                        0 -> showAddNodeDialog = true
                        2 -> showAddSubDialog = true
                        else -> showRelayBindDialog = true
                    }
                },
                containerColor = SkyAccent,
                contentColor = PureWhite,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = when (subTab) {
                        0 -> Icons.Outlined.Add
                        2 -> Icons.Outlined.CloudDownload
                        else -> Icons.Outlined.Link
                    },
                    contentDescription = when (subTab) {
                        0 -> "添加节点"
                        2 -> "添加节点订阅"
                        else -> "绑定中转"
                    }
                )
            }
        }
    }

    if (showAddNodeDialog) {
        AddNodeDialog(
            groupNames = groupNames,
            onDismiss = { showAddNodeDialog = false },
            onAddUri = { uri ->
                showAddNodeDialog = false
                viewModel.addNodeFromUri(uri)
            },
            onAddSubscription = { name, url, prefix, groups ->
                showAddNodeDialog = false
                viewModel.addOrUpdateNodeSubscription(
                    existingId = null,
                    name = name,
                    url = url,
                    namePrefix = prefix,
                    autoAddToGroups = groups
                )
            },
            onAddYaml = { yaml ->
                showAddNodeDialog = false
                viewModel.addNodeFromYaml(yaml)
            }
        )
    }

    if (showAddSubDialog || editingSub != null) {
        NodeSubscriptionDialog(
            existing = editingSub,
            groupNames = groupNames,
            onDismiss = {
                showAddSubDialog = false
                editingSub = null
            },
            onConfirm = { name, url, prefix, groups ->
                val id = editingSub?.id
                showAddSubDialog = false
                editingSub = null
                viewModel.addOrUpdateNodeSubscription(
                    existingId = id,
                    name = name,
                    url = url,
                    namePrefix = prefix,
                    autoAddToGroups = groups
                )
            }
        )
    }

    deletingSub?.let { sub ->
        AlertDialog(
            onDismissRequest = { deletingSub = null },
            containerColor = PureWhite,
            title = { Text("删除节点订阅") },
            text = {
                Text(
                    text = "确定要删除订阅「${sub.name}」（当前关联 ${sub.nodeCount} 个节点）吗？\n\n您可以选择同时清理其节点，或仅移除订阅并将节点保留为自定义节点。",
                    fontSize = 13.sp,
                    color = SlateInk
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = RoseDanger),
                    onClick = {
                        deletingSub = null
                        viewModel.deleteNodeSubscription(sub.id, removeNodes = true)
                    }
                ) {
                    Text("删除并清理节点")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            deletingSub = null
                            viewModel.deleteNodeSubscription(sub.id, removeNodes = false)
                        }
                    ) {
                        Text("仅删订阅(留节点)")
                    }
                    TextButton(onClick = { deletingSub = null }) {
                        Text("取消")
                    }
                }
            }
        )
    }

    if (showRelayBindDialog) {
        BindRelayDialog(
            entryCandidates = entryCandidates,
            nodes = active.proxies,
            onDismiss = { showRelayBindDialog = false },
            onBind = { entry, exitIds ->
                showRelayBindDialog = false
                viewModel.bindRelayToNodes(exitIds, entry)
            }
        )
    }

    if (showBatchDomainToIpConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDomainToIpConfirm = false },
            containerColor = PureWhite,
            title = { Text("批量域名转 IP") },
            text = {
                Text(
                    text = "将当前配置中的 $domainNodeCount 个域名节点解析并替换为 IP 地址（优先 IPv4），同时自动保留原域名作为 TLS SNI / WebSocket Host，不影响原有加密握手。",
                    fontSize = 13.sp,
                    color = SlateInk
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
                    onClick = {
                        showBatchDomainToIpConfirm = false
                        viewModel.convertAllDomainNodesToIp()
                    }
                ) {
                    Text("开始转换")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDomainToIpConfirm = false }) { Text("取消") }
            }
        )
    }

    transferringNode?.let { node ->
        TransferNodeDialog(
            node = node,
            otherProfiles = otherProfiles,
            loadProfile = { viewModel.loadProfileForPicker(it) },
            onDismiss = { transferringNode = null },
            onConfirm = { targetProfileId, move, targetGroups ->
                transferringNode = null
                viewModel.transferNodeToProfile(node.id, targetProfileId, move, targetGroups)
            }
        )
    }

    if (showImportFromProfileDialog && otherProfiles.isNotEmpty()) {
        ImportFromProfileDialog(
            otherProfiles = otherProfiles,
            currentGroupNames = groupNames,
            loadProfile = { viewModel.loadProfileForPicker(it) },
            onDismiss = { showImportFromProfileDialog = false },
            onConfirm = { sourceId, nodeIds, targetGroups ->
                showImportFromProfileDialog = false
                viewModel.importNodesFromProfile(sourceId, nodeIds, targetGroups)
            }
        )
    }

    editingNode?.let { node ->
        EditNodeDialog(
            node = node,
            entryCandidates = entryCandidates,
            latency = uiState.nodeLatencies[node.id],
            onTestLatency = { viewModel.testNodeLatency(node.id) },
            onResolveDomain = { domain -> viewModel.resolveDomainCandidates(domain) },
            onConvertToIp = { chosenIp ->
                editingNode = null
                viewModel.convertNodeDomainToIp(node.id, chosenIp)
            },
            onDismiss = { editingNode = null },
            onSaveBasic = { name, server, port, udp, dialer ->
                editingNode = null
                viewModel.updateNodeBasic(node.id, name, server, port, udp, dialer)
            },
            onSaveRawYaml = { raw ->
                editingNode = null
                viewModel.updateNodeRawYaml(node.id, raw)
            }
        )
    }
}

@Composable
private fun NodeSubscriptionsPane(
    subscriptions: List<NodeSubscription>,
    isLoading: Boolean,
    onRefreshAll: () -> Unit,
    onRefreshOne: (NodeSubscription) -> Unit,
    onEdit: (NodeSubscription) -> Unit,
    onDelete: (NodeSubscription) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 84.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Surface(
                color = PureWhite,
                border = BorderStroke(1.dp, BorderGray),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("多源节点订阅（自动拉取与同步）", style = MaterialTheme.typography.titleMedium)
                        if (subscriptions.isNotEmpty()) {
                            OutlinedButton(
                                onClick = onRefreshAll,
                                enabled = !isLoading,
                                border = BorderStroke(1.dp, BorderGray),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Outlined.Refresh, null, Modifier.size(15.dp), tint = SkyAccent)
                                Spacer(Modifier.width(4.dp))
                                Text("全部更新", fontSize = 12.sp, color = SlateInk)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "可添加多个机场或自建节点订阅（支持 Clash YAML、Base64 及纯文本 URI 列表），一键拉取合并到当前配置，并可自动加入指定策略组，无需每次手动粘贴节点。",
                        fontSize = 12.sp,
                        color = SlateMuted
                    )
                }
            }
        }

        if (subscriptions.isEmpty()) {
            item {
                Surface(
                    color = PureWhite,
                    border = BorderStroke(1.dp, BorderGray),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "尚未添加节点订阅。点击右下角按钮添加订阅链接，即可自动导入并随时一键更新节点。",
                        modifier = Modifier.padding(20.dp),
                        fontSize = 13.sp,
                        color = SlateMuted
                    )
                }
            }
        } else {
            items(subscriptions, key = { it.id }) { sub ->
                NodeSubscriptionCard(
                    sub = sub,
                    isLoading = isLoading,
                    onRefresh = { onRefreshOne(sub) },
                    onEdit = { onEdit(sub) },
                    onDelete = { onDelete(sub) }
                )
            }
        }
    }
}

@Composable
private fun NodeSubscriptionCard(
    sub: NodeSubscription,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val updatedLabel = remember(sub.lastUpdatedAt) {
        if (sub.lastUpdatedAt <= 0L) {
            "尚未更新"
        } else {
            "更新于 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(sub.lastUpdatedAt))}"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PureWhite),
        border = BorderStroke(1.dp, BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = sub.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(6.dp))
                        BadgeTag("${sub.nodeCount} 节点", SkyAccent)
                        if (sub.namePrefix.isNotBlank()) {
                            Spacer(Modifier.width(4.dp))
                            BadgeTag("前缀:${sub.namePrefix.trim()}", AmberBadge)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = sub.url,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = SlateMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onRefresh, enabled = !isLoading, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "更新订阅节点", tint = SkyAccent, modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = onEdit, enabled = !isLoading, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑订阅", tint = SlateMuted, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, enabled = !isLoading, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除订阅", tint = RoseDanger, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (sub.autoAddToGroups.isNotEmpty()) {
                        "自动加入策略组: ${sub.autoAddToGroups.joinToString("、")}"
                    } else {
                        "仅导入节点池（不自动改策略组）"
                    },
                    fontSize = 11.sp,
                    color = if (sub.autoAddToGroups.isNotEmpty()) EmeraldActive else SlateMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = updatedLabel,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = SlateMuted
                )
            }
        }
    }
}

@Composable
private fun NodeSubscriptionDialog(
    existing: NodeSubscription?,
    groupNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, namePrefix: String, autoAddToGroups: List<String>) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var namePrefix by remember { mutableStateOf(existing?.namePrefix.orEmpty()) }
    var selectedGroups by remember { mutableStateOf(existing?.autoAddToGroups?.toSet() ?: emptySet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text(if (existing == null) "添加节点订阅" else "编辑节点订阅") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("订阅名称") },
                    placeholder = { Text("例如：备用机场 / 落地节点") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("订阅链接 (Clash YAML / Base64 / URI)") },
                    placeholder = { Text("https://...") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = namePrefix,
                    onValueChange = { namePrefix = it },
                    label = { Text("节点名称前缀（可选，防止多机场重名）") },
                    placeholder = { Text("例如：[备用] ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (groupNames.isNotEmpty()) {
                    Text(
                        text = "同步后自动加入以下策略组（可选，多选）",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        groupNames.forEach { groupName ->
                            val isSelected = groupName in selectedGroups
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedGroups = if (isSelected) selectedGroups - groupName else selectedGroups + groupName
                                },
                                label = { Text(groupName, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
                onClick = {
                    onConfirm(
                        name.ifBlank { "节点订阅" },
                        url,
                        namePrefix,
                        groupNames.filter { it in selectedGroups }
                    )
                },
                enabled = url.isNotBlank()
            ) {
                Text("保存并拉取节点")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun BindRelayDialog(
    entryCandidates: List<String>,
    nodes: List<ProxyNode>,
    onDismiss: () -> Unit,
    onBind: (entryProxy: String, exitNodeIds: Set<String>) -> Unit
) {
    var entryProxy by remember { mutableStateOf(entryCandidates.firstOrNull().orEmpty()) }
    var selectedExitIds by remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text("配置节点中转 (dialer-proxy)") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("1. 选择入口前置代理（策略组或单节点）", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    entryCandidates.forEach { candidate ->
                        FilterChip(
                            selected = entryProxy == candidate,
                            onClick = { entryProxy = candidate },
                            label = { Text(candidate, fontSize = 11.sp) }
                        )
                    }
                }

                Text("2. 选择出口落地节点（可多选，直接写入 dialer-proxy）", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    nodes.filter { it.name != entryProxy }.forEach { node ->
                        val isSelected = node.id in selectedExitIds
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedExitIds = if (isSelected) selectedExitIds - node.id else selectedExitIds + node.id
                                },
                            color = if (isSelected) EmeraldActive.copy(alpha = 0.1f) else SoftSurface,
                            border = BorderStroke(1.dp, if (isSelected) EmeraldActive else BorderGray),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = node.name,
                                    fontSize = 12.sp,
                                    color = if (isSelected) EmeraldActive else SlateInk,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = node.type.uppercase(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = SlateMuted
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
                onClick = { onBind(entryProxy, selectedExitIds) },
                enabled = entryProxy.isNotBlank() && selectedExitIds.isNotEmpty()
            ) {
                Text("写入 dialer-proxy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun NodeCard(
    node: ProxyNode,
    subscriptionName: String?,
    latency: LatencyResult?,
    canTransfer: Boolean,
    isDragging: Boolean,
    showDragHandle: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onTestLatency: () -> Unit,
    onConvertDomainToIp: () -> Unit,
    onTransfer: () -> Unit,
    onDelete: () -> Unit
) {
    val isDomain = remember(node.server) { !NodeNetworkTools.isIpAddress(node.server) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = PureWhite),
        border = BorderStroke(if (isDragging) 1.5.dp else 1.dp, if (isDragging) SkyAccent else BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showDragHandle) {
                Icon(
                    imageVector = Icons.Outlined.DragIndicator,
                    contentDescription = "长按拖动",
                    tint = SlateMuted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = node.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    BadgeTag(node.type.uppercase(), SkyAccent)
                    if (node.source == NodeSource.CUSTOM) {
                        Spacer(Modifier.width(4.dp))
                        BadgeTag("自定义", EmeraldActive)
                    } else if (!subscriptionName.isNullOrBlank()) {
                        Spacer(Modifier.width(4.dp))
                        BadgeTag(subscriptionName, AmberBadge)
                    } else if (!node.originProfileName.isNullOrBlank()) {
                        Spacer(Modifier.width(4.dp))
                        BadgeTag("池:${node.originProfileName}", AmberBadge)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${node.server}:${node.port}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = SlateMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    LatencyChip(latency = latency, onClick = onTestLatency)
                    if (isDomain) {
                        Surface(
                            color = EmeraldActive.copy(alpha = 0.1f),
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.clickable { onConvertDomainToIp() }
                        ) {
                            Text(
                                text = "转IP",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = EmeraldActive
                            )
                        }
                    }
                }
                if (!node.dialerProxy.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    BadgeTag("dialer-proxy: ${node.dialerProxy}", AmberBadge)
                }
            }
            if (canTransfer) {
                IconButton(onClick = onTransfer, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.DriveFileMove, contentDescription = "复制/移动到其他配置", tint = SkyAccent, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除节点", tint = RoseDanger, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun LatencyChip(
    latency: LatencyResult?,
    onClick: () -> Unit
) {
    val (label, color) = when {
        latency == null -> "测速" to SlateMuted
        latency.testing -> "测速中..." to SkyAccent
        latency.latencyMs != null -> {
            val ms = latency.latencyMs
            val c = when {
                ms < 150 -> EmeraldActive
                ms < 400 -> AmberBadge
                else -> RoseDanger
            }
            "${ms}ms" to c
        }
        else -> (latency.error ?: "失败") to RoseDanger
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
private fun BadgeTag(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
private fun AddNodeDialog(
    groupNames: List<String>,
    onDismiss: () -> Unit,
    onAddUri: (String) -> Unit,
    onAddSubscription: (name: String, url: String, namePrefix: String, autoAddToGroups: List<String>) -> Unit,
    onAddYaml: (String) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) } // 0 = 订阅链接, 1 = URI 链接, 2 = 节点 YAML
    var subName by remember { mutableStateOf("") }
    var subUrl by remember { mutableStateOf("") }
    var subPrefix by remember { mutableStateOf("") }
    var selectedGroups by remember { mutableStateOf(setOf<String>()) }
    var uriInput by remember { mutableStateOf("") }
    var yamlInput by remember {
        mutableStateOf(
            """
            name: "自建落地节点"
            type: socks5
            server: 198.51.100.10
            port: 10808
            username: user
            password: pass
            udp: true
            """.trimIndent()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text("添加节点") },
        text = {
            Column {
                TabRow(selectedTabIndex = tab, containerColor = PureWhite) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("订阅导入", fontSize = 12.sp) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("URI 链接", fontSize = 12.sp) })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("YAML", fontSize = 12.sp) })
                }
                Spacer(Modifier.height(12.dp))
                when (tab) {
                    0 -> {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = subName,
                                onValueChange = { subName = it },
                                label = { Text("订阅名称") },
                                placeholder = { Text("例如：备用机场 / 落地订阅") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = subUrl,
                                onValueChange = { subUrl = it },
                                label = { Text("订阅链接 (Clash YAML / Base64 / URI)") },
                                placeholder = { Text("https://...") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = subPrefix,
                                onValueChange = { subPrefix = it },
                                label = { Text("节点名称前缀（可选）") },
                                placeholder = { Text("例如：[备用] ") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (groupNames.isNotEmpty()) {
                                Text("自动加入策略组（可选）", style = MaterialTheme.typography.labelMedium)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    groupNames.forEach { g ->
                                        val isSelected = g in selectedGroups
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                selectedGroups = if (isSelected) selectedGroups - g else selectedGroups + g
                                            },
                                            label = { Text(g, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        OutlinedTextField(
                            value = uriInput,
                            onValueChange = { uriInput = it },
                            label = { Text("支持多行批量：vless / vmess / trojan / hy2 / ss / socks5") },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    else -> {
                        OutlinedTextField(
                            value = yamlInput,
                            onValueChange = { yamlInput = it },
                            label = { Text("单节点 YAML 映射") },
                            textStyle = MaterialTheme.typography.labelMedium,
                            minLines = 7,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
                onClick = {
                    when (tab) {
                        0 -> onAddSubscription(
                            subName.ifBlank { "节点订阅" },
                            subUrl,
                            subPrefix,
                            groupNames.filter { it in selectedGroups }
                        )
                        1 -> onAddUri(uriInput)
                        else -> onAddYaml(yamlInput)
                    }
                },
                enabled = when (tab) {
                    0 -> subUrl.isNotBlank()
                    1 -> uriInput.isNotBlank()
                    else -> yamlInput.isNotBlank()
                }
            ) {
                Text(if (tab == 0) "订阅并拉取" else "添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun EditNodeDialog(
    node: ProxyNode,
    entryCandidates: List<String>,
    latency: LatencyResult?,
    onTestLatency: () -> Unit,
    onResolveDomain: suspend (String) -> Result<List<String>>,
    onConvertToIp: (String) -> Unit,
    onDismiss: () -> Unit,
    onSaveBasic: (name: String, server: String, port: Int, udp: Boolean, dialerProxy: String?) -> Unit,
    onSaveRawYaml: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(node.name) }
    var server by remember { mutableStateOf(node.server) }
    var portText by remember { mutableStateOf(node.port.toString()) }
    var udp by remember { mutableStateOf(node.udp) }
    var dialerProxy by remember { mutableStateOf(node.dialerProxy.orEmpty()) }
    var rawYaml by remember { mutableStateOf(node.rawYaml) }
    var resolvingIps by remember { mutableStateOf(false) }
    var resolvedIps by remember { mutableStateOf<List<String>>(emptyList()) }
    var resolveError by remember { mutableStateOf<String?>(null) }

    val serverIsDomain = remember(server) { server.isNotBlank() && !NodeNetworkTools.isIpAddress(server) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("编辑节点")
                LatencyChip(latency = latency, onClick = onTestLatency)
            }
        },
        text = {
            Column {
                TabRow(selectedTabIndex = tab, containerColor = PureWhite) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("基础字段") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("高级 YAML") })
                }
                Spacer(Modifier.height(10.dp))
                if (tab == 0) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("节点名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = server,
                                onValueChange = {
                                    server = it
                                    resolvedIps = emptyList()
                                    resolveError = null
                                },
                                label = { Text("服务器地址") },
                                singleLine = true,
                                modifier = Modifier.weight(2f)
                            )
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                                label = { Text("端口") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (serverIsDomain) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "检测到域名地址，可解析为 IP（自动保留 SNI）",
                                    fontSize = 11.sp,
                                    color = SlateMuted,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    enabled = !resolvingIps,
                                    onClick = {
                                        resolvingIps = true
                                        resolveError = null
                                        scope.launch {
                                            onResolveDomain(server)
                                                .onSuccess { ips -> resolvedIps = ips }
                                                .onFailure { err -> resolveError = err.message ?: "解析失败" }
                                            resolvingIps = false
                                        }
                                    }
                                ) {
                                    Text(if (resolvingIps) "解析中..." else "解析域名", fontSize = 12.sp)
                                }
                            }
                            if (resolveError != null) {
                                Text(resolveError!!, fontSize = 11.sp, color = RoseDanger)
                            }
                            if (resolvedIps.isNotEmpty()) {
                                Text("点击下方 IP 立即替换（保留原域名 SNI）：", fontSize = 11.sp, color = EmeraldActive)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    resolvedIps.forEach { ip ->
                                        FilterChip(
                                            selected = false,
                                            onClick = { onConvertToIp(ip) },
                                            label = { Text(ip, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }
                        Text("快捷选择入口前置 (dialer-proxy)", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = dialerProxy.isBlank(),
                                onClick = { dialerProxy = "" },
                                label = { Text("不中转", fontSize = 11.sp) }
                            )
                            entryCandidates.filter { it != node.name }.forEach { candidate ->
                                FilterChip(
                                    selected = dialerProxy == candidate,
                                    onClick = { dialerProxy = candidate },
                                    label = { Text(candidate, fontSize = 11.sp) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = dialerProxy,
                            onValueChange = { dialerProxy = it },
                            label = { Text("dialer-proxy（留空直连）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("启用 UDP 转发", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = udp, onCheckedChange = { udp = it })
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = rawYaml,
                        onValueChange = { rawYaml = it },
                        label = { Text("完整节点 YAML（无损保留协议高级参数）") },
                        textStyle = MaterialTheme.typography.labelMedium,
                        minLines = 8,
                        maxLines = 14,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
                onClick = {
                    if (tab == 0) {
                        onSaveBasic(
                            name,
                            server,
                            portText.toIntOrNull() ?: node.port,
                            udp,
                            dialerProxy.ifBlank { null }
                        )
                    } else {
                        onSaveRawYaml(rawYaml)
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun TransferNodeDialog(
    node: ProxyNode,
    otherProfiles: List<ProfileMeta>,
    loadProfile: suspend (String) -> SplitProfile?,
    onDismiss: () -> Unit,
    onConfirm: (targetProfileId: String, move: Boolean, targetGroups: List<String>) -> Unit
) {
    var selectedTargetId by remember { mutableStateOf(otherProfiles.firstOrNull()?.id.orEmpty()) }
    var moveMode by remember { mutableStateOf(false) } // false = 复制, true = 移动
    var targetProfile by remember { mutableStateOf<SplitProfile?>(null) }
    var selectedGroups by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(selectedTargetId) {
        selectedGroups = emptySet()
        targetProfile = if (selectedTargetId.isNotBlank()) loadProfile(selectedTargetId) else null
    }

    val targetGroupNames = targetProfile?.proxyGroups?.map { it.name }.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text("跨配置调整节点") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "当前节点：${node.name}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SlateInk
                )
                Text("1. 操作方式", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !moveMode,
                        onClick = { moveMode = false },
                        label = { Text("复制到目标配置", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = moveMode,
                        onClick = { moveMode = true },
                        label = { Text("移动到目标配置", fontSize = 12.sp) }
                    )
                }
                Text("2. 选择目标配置文件", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    otherProfiles.forEach { profile ->
                        val isSelected = profile.id == selectedTargetId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTargetId = profile.id },
                            color = if (isSelected) SkyAccent.copy(alpha = 0.1f) else SoftSurface,
                            border = BorderStroke(1.dp, if (isSelected) SkyAccent else BorderGray),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = profile.name,
                                    fontSize = 13.sp,
                                    color = if (isSelected) SkyAccent else SlateInk,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${profile.nodeCount} 节点",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = SlateMuted
                                )
                            }
                        }
                    }
                }
                if (targetGroupNames.isNotEmpty()) {
                    Text("3. 自动加入目标配置的策略组（可选）", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        targetGroupNames.forEach { groupName ->
                            val isSelected = groupName in selectedGroups
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedGroups = if (isSelected) selectedGroups - groupName else selectedGroups + groupName
                                },
                                label = { Text(groupName, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
                enabled = selectedTargetId.isNotBlank(),
                onClick = {
                    onConfirm(
                        selectedTargetId,
                        moveMode,
                        targetGroupNames.filter { it in selectedGroups }
                    )
                }
            ) {
                Text(if (moveMode) "移动过去" else "复制过去")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ImportFromProfileDialog(
    otherProfiles: List<ProfileMeta>,
    currentGroupNames: List<String>,
    loadProfile: suspend (String) -> SplitProfile?,
    onDismiss: () -> Unit,
    onConfirm: (sourceProfileId: String, nodeIds: Set<String>, targetGroups: List<String>) -> Unit
) {
    var selectedSourceId by remember { mutableStateOf(otherProfiles.firstOrNull()?.id.orEmpty()) }
    var sourceProfile by remember { mutableStateOf<SplitProfile?>(null) }
    var selectedNodeIds by remember { mutableStateOf(setOf<String>()) }
    var selectedGroups by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(selectedSourceId) {
        selectedNodeIds = emptySet()
        sourceProfile = if (selectedSourceId.isNotBlank()) loadProfile(selectedSourceId) else null
    }

    val sourceNodes = sourceProfile?.proxies.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text("从其他配置引入节点") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("1. 选择来源配置", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    otherProfiles.forEach { profile ->
                        FilterChip(
                            selected = profile.id == selectedSourceId,
                            onClick = { selectedSourceId = profile.id },
                            label = { Text(profile.name, fontSize = 12.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("2. 勾选要引入的节点 (${selectedNodeIds.size}/${sourceNodes.size})", style = MaterialTheme.typography.labelMedium)
                    if (sourceNodes.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selectedNodeIds = if (selectedNodeIds.size == sourceNodes.size) {
                                    emptySet()
                                } else {
                                    sourceNodes.map { it.id }.toSet()
                                }
                            }
                        ) {
                            Text(if (selectedNodeIds.size == sourceNodes.size) "全不选" else "全选", fontSize = 11.sp)
                        }
                    }
                }

                if (sourceNodes.isEmpty()) {
                    Text("该配置暂无节点", fontSize = 12.sp, color = SlateMuted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sourceNodes.forEach { node ->
                            val isSelected = node.id in selectedNodeIds
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedNodeIds = if (isSelected) selectedNodeIds - node.id else selectedNodeIds + node.id
                                    },
                                color = if (isSelected) EmeraldActive.copy(alpha = 0.1f) else SoftSurface,
                                border = BorderStroke(1.dp, if (isSelected) EmeraldActive else BorderGray),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = node.name,
                                            fontSize = 12.sp,
                                            color = if (isSelected) EmeraldActive else SlateInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${node.server}:${node.port}",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = SlateMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = node.type.uppercase(),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = SlateMuted
                                    )
                                }
                            }
                        }
                    }
                }

                if (currentGroupNames.isNotEmpty()) {
                    Text("3. 引入后自动加入当前配置的策略组（可选）", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        currentGroupNames.forEach { g ->
                            val isSelected = g in selectedGroups
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedGroups = if (isSelected) selectedGroups - g else selectedGroups + g
                                },
                                label = { Text(g, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
                enabled = selectedSourceId.isNotBlank() && selectedNodeIds.isNotEmpty(),
                onClick = {
                    onConfirm(
                        selectedSourceId,
                        selectedNodeIds,
                        currentGroupNames.filter { it in selectedGroups }
                    )
                }
            ) {
                Text("引入 (${selectedNodeIds.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun GlobalNodePoolPane(
    activeProfileName: String,
    pool: List<GlobalPoolNode>,
    poolLatencies: Map<String, LatencyResult>,
    isTestingPool: Boolean,
    currentGroupNames: List<String>,
    onTestOne: (String) -> Unit,
    onTestBatch: (List<String>) -> Unit,
    onAddToActive: (Set<String>, List<String>) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf("全部来源") }
    var protocolFilter by remember { mutableStateOf("全部协议") }
    var sortByLatency by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var showTargetGroupsDialog by remember { mutableStateOf(false) }

    val sources = remember(pool) {
        listOf("全部来源") + pool.map { it.originProfileName }.distinct()
    }
    val protocols = remember(pool) {
        listOf("全部协议") + pool.map { it.node.type.uppercase() }.distinct().sorted()
    }

    val effectiveSource = if (sourceFilter in sources) sourceFilter else "全部来源"
    val effectiveProto = if (protocolFilter in protocols) protocolFilter else "全部协议"

    val filtered = remember(pool, search, effectiveSource, effectiveProto, sortByLatency, poolLatencies) {
        val base = pool.filter { entry ->
            val matchSrc = effectiveSource == "全部来源" || entry.originProfileName == effectiveSource
            val matchProto = effectiveProto == "全部协议" || entry.node.type.equals(effectiveProto, ignoreCase = true)
            val matchQ = search.isBlank() ||
                entry.node.name.contains(search, ignoreCase = true) ||
                entry.node.server.contains(search, ignoreCase = true) ||
                entry.originProfileName.contains(search, ignoreCase = true)
            matchSrc && matchProto && matchQ
        }
        if (sortByLatency) {
            base.sortedBy { poolLatencies[it.key]?.latencyMs ?: Long.MAX_VALUE }
        } else {
            base
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = PureWhite) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("搜索全局节点池（名称 / 地址 / 来源配置）...", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        sources.forEach { src ->
                            FilterChip(
                                selected = effectiveSource == src,
                                onClick = { sourceFilter = src },
                                label = { Text(src, fontSize = 11.sp) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        protocols.forEach { proto ->
                            FilterChip(
                                selected = effectiveProto == proto,
                                onClick = { protocolFilter = proto },
                                label = { Text(proto, fontSize = 11.sp) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onTestBatch(filtered.map { it.key }) },
                            enabled = !isTestingPool && filtered.isNotEmpty(),
                            border = BorderStroke(1.dp, BorderGray),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Outlined.Speed, null, Modifier.size(15.dp), tint = SkyAccent)
                            Spacer(Modifier.width(4.dp))
                            Text(if (isTestingPool) "测速中..." else "一键测速", fontSize = 12.sp, color = SlateInk)
                        }
                        FilterChip(
                            selected = sortByLatency,
                            onClick = { sortByLatency = !sortByLatency },
                            leadingIcon = { Icon(Icons.Outlined.Sort, null, Modifier.size(15.dp)) },
                            label = { Text("按延迟排序", fontSize = 12.sp) }
                        )
                        TextButton(
                            onClick = { selectedKeys = selectedKeys + filtered.map { it.key } },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Text("全选筛选", fontSize = 12.sp)
                        }
                        if (selectedKeys.isNotEmpty()) {
                            TextButton(
                                onClick = { selectedKeys = emptySet() },
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Text("清空(${selectedKeys.size})", fontSize = 12.sp, color = RoseDanger)
                            }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 10.dp,
                    bottom = if (selectedKeys.isNotEmpty()) 88.dp else 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "pool_hint") {
                    Text(
                        text = "汇总所有已导入配置的去重节点（共 ${pool.size} 个）· 点选节点可批量加入「$activeProfileName」",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (filtered.isEmpty()) {
                    item(key = "pool_empty") {
                        Surface(
                            color = PureWhite,
                            border = BorderStroke(1.dp, BorderGray),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "当前筛选条件下没有匹配的节点池条目。",
                                modifier = Modifier.padding(20.dp),
                                fontSize = 13.sp,
                                color = SlateMuted
                            )
                        }
                    }
                } else {
                    items(filtered, key = { it.key }) { entry ->
                        val isSelected = entry.key in selectedKeys
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedKeys = if (isSelected) selectedKeys - entry.key else selectedKeys + entry.key
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) EmeraldActive.copy(alpha = 0.08f) else PureWhite
                            ),
                            border = BorderStroke(
                                if (isSelected) 1.5.dp else 1.dp,
                                if (isSelected) EmeraldActive else BorderGray
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = entry.node.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (isSelected) EmeraldActive else SlateInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        BadgeTag(entry.node.type.uppercase(), SkyAccent)
                                        Spacer(Modifier.width(4.dp))
                                        BadgeTag("来自:${entry.originProfileName}", AmberBadge)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "${entry.node.server}:${entry.node.port}",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = SlateMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        LatencyChip(
                                            latency = poolLatencies[entry.key],
                                            onClick = { onTestOne(entry.key) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (selectedKeys.isNotEmpty()) {
            Surface(
                color = PureWhite,
                border = BorderStroke(1.dp, EmeraldActive),
                shadowElevation = 4.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "已挑选 ${selectedKeys.size} 个池节点",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SlateInk
                    )
                    Button(
                        onClick = {
                            if (currentGroupNames.isEmpty()) {
                                onAddToActive(selectedKeys, emptyList())
                                selectedKeys = emptySet()
                            } else {
                                showTargetGroupsDialog = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldActive)
                    ) {
                        Icon(Icons.Outlined.PlaylistAdd, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("加入当前配置", fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (showTargetGroupsDialog) {
        AddPoolNodesToProfileDialog(
            activeProfileName = activeProfileName,
            selectedCount = selectedKeys.size,
            groupNames = currentGroupNames,
            onDismiss = { showTargetGroupsDialog = false },
            onConfirm = { chosenGroups ->
                showTargetGroupsDialog = false
                onAddToActive(selectedKeys, chosenGroups)
                selectedKeys = emptySet()
            }
        )
    }
}

@Composable
private fun AddPoolNodesToProfileDialog(
    activeProfileName: String,
    selectedCount: Int,
    groupNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var selectedGroups by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text("加入「$activeProfileName」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "将把已挑选的 $selectedCount 个全局节点池节点加入到当前配置。可选择顺便填入哪些策略组：",
                    fontSize = 12.sp,
                    color = SlateMuted
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    groupNames.forEach { gName ->
                        val sel = gName in selectedGroups
                        FilterChip(
                            selected = sel,
                            onClick = {
                                selectedGroups = if (sel) selectedGroups - gName else selectedGroups + gName
                            },
                            label = { Text(gName, fontSize = 12.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldActive),
                onClick = { onConfirm(groupNames.filter { it in selectedGroups }) }
            ) {
                Text("确认加入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
internal fun EmptyProfileNotice(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = PureWhite,
            border = BorderStroke(1.dp, BorderGray),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = SlateMuted
            )
        }
    }
}
