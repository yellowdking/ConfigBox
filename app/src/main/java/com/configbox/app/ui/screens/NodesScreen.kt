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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.configbox.app.model.NodeSource
import com.configbox.app.model.ProxyNode
import com.configbox.app.ui.ConfigBoxUiState
import com.configbox.app.ui.ConfigBoxViewModel
import com.configbox.app.ui.theme.AmberBadge
import com.configbox.app.ui.theme.BorderGray
import com.configbox.app.ui.theme.EmeraldActive
import com.configbox.app.ui.theme.PureWhite
import com.configbox.app.ui.theme.RoseDanger
import com.configbox.app.ui.theme.SkyAccent
import com.configbox.app.ui.theme.SlateInk
import com.configbox.app.ui.theme.SlateMuted
import com.configbox.app.ui.theme.SoftSurface

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

    var subTab by remember { mutableIntStateOf(0) } // 0 = 全部节点, 1 = 中转配置 (dialer-proxy)
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("全部") }
    var showAddNodeDialog by remember { mutableStateOf(false) }
    var showRelayBindDialog by remember { mutableStateOf(false) }
    var editingNode by remember { mutableStateOf<ProxyNode?>(null) }

    val protocolFilters = remember(active.proxies) {
        listOf("全部", "已中转", "自定义") + active.proxies.map { it.type.uppercase() }.distinct().sorted()
    }

    val filteredNodes = remember(active.proxies, searchQuery, selectedFilter) {
        active.proxies.filter { node ->
            val matchFilter = when (selectedFilter) {
                "全部" -> true
                "已中转" -> !node.dialerProxy.isNullOrBlank()
                "自定义" -> node.source == NodeSource.CUSTOM
                else -> node.type.equals(selectedFilter, ignoreCase = true)
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

    val entryCandidates = remember(active.proxyGroups, active.proxies) {
        active.proxyGroups.map { it.name } + active.proxies.map { it.name }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = subTab, containerColor = PureWhite) {
                Tab(
                    selected = subTab == 0,
                    onClick = { subTab = 0 },
                    text = { Text("全部节点 (${active.proxies.size})", fontSize = 13.sp) }
                )
                Tab(
                    selected = subTab == 1,
                    onClick = { subTab = 1 },
                    text = { Text("中转绑定 (${relayedNodes.size})", fontSize = 13.sp) }
                )
            }

            if (subTab == 0) {
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
                                    selected = selectedFilter == tag,
                                    onClick = { selectedFilter = tag },
                                    label = { Text(tag, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 84.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredNodes, key = { it.id }) { node ->
                        NodeCard(
                            node = node,
                            onClick = { editingNode = node },
                            onDelete = { viewModel.deleteNode(node.id) }
                        )
                    }
                }
            } else {
                // Direct 2-hop dialer-proxy binding screen (Entry -> Exit, no middle hop, no auto-created group)
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
                        items(relayedNodes, key = { it.id }) { node ->
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

        FloatingActionButton(
            onClick = { if (subTab == 0) showAddNodeDialog = true else showRelayBindDialog = true },
            containerColor = SkyAccent,
            contentColor = PureWhite,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = if (subTab == 0) Icons.Outlined.Add else Icons.Outlined.Link,
                contentDescription = if (subTab == 0) "添加节点" else "绑定中转"
            )
        }
    }

    if (showAddNodeDialog) {
        AddNodeDialog(
            onDismiss = { showAddNodeDialog = false },
            onAddUri = { uri ->
                showAddNodeDialog = false
                viewModel.addNodeFromUri(uri)
            },
            onAddYaml = { yaml ->
                showAddNodeDialog = false
                viewModel.addNodeFromYaml(yaml)
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

    editingNode?.let { node ->
        EditNodeDialog(
            node = node,
            entryCandidates = entryCandidates,
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
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = PureWhite),
        border = BorderStroke(1.dp, BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${node.server}:${node.port}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = SlateMuted
                )
                if (!node.dialerProxy.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    BadgeTag("dialer-proxy: ${node.dialerProxy}", AmberBadge)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除节点", tint = RoseDanger)
            }
        }
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
    onDismiss: () -> Unit,
    onAddUri: (String) -> Unit,
    onAddYaml: (String) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
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
        title = { Text("添加自定义节点") },
        text = {
            Column {
                TabRow(selectedTabIndex = tab, containerColor = PureWhite) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("URI 链接") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("节点 YAML") })
                }
                Spacer(Modifier.height(12.dp))
                if (tab == 0) {
                    OutlinedTextField(
                        value = uriInput,
                        onValueChange = { uriInput = it },
                        label = { Text("socks5:// / http:// / vless:// / trojan:// / hy2:// / ss://") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
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
        },
        confirmButton = {
            Button(
                onClick = { if (tab == 0) onAddUri(uriInput) else onAddYaml(yamlInput) },
                enabled = if (tab == 0) uriInput.isNotBlank() else yamlInput.isNotBlank()
            ) {
                Text("添加")
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
    onDismiss: () -> Unit,
    onSaveBasic: (name: String, server: String, port: Int, udp: Boolean, dialerProxy: String?) -> Unit,
    onSaveRawYaml: (String) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(node.name) }
    var server by remember { mutableStateOf(node.server) }
    var portText by remember { mutableStateOf(node.port.toString()) }
    var udp by remember { mutableStateOf(node.udp) }
    var dialerProxy by remember { mutableStateOf(node.dialerProxy.orEmpty()) }
    var rawYaml by remember { mutableStateOf(node.rawYaml) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text("编辑节点") },
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
                                onValueChange = { server = it },
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
