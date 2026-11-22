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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
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
import com.configbox.app.ui.theme.SlateMuted
import com.configbox.app.ui.theme.SoftSurface

@Composable
fun NodesScreen(
    uiState: ConfigBoxUiState,
    viewModel: ConfigBoxViewModel
) {
    val active = uiState.activeProfile
    if (active == null) {
        EmptyProfileNotice("请先在「配置」页导入或选择一个 Clash 配置文件")
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("全部") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingNode by remember { mutableStateOf<ProxyNode?>(null) }

    val protocolFilters = remember(active.proxies) {
        listOf("全部", "自定义") + active.proxies.map { it.type.uppercase() }.distinct().sorted()
    }

    val filteredNodes = remember(active.proxies, searchQuery, selectedFilter) {
        active.proxies.filter { node ->
            val matchFilter = when (selectedFilter) {
                "全部" -> true
                "自定义" -> node.source == NodeSource.CUSTOM
                else -> node.type.equals(selectedFilter, ignoreCase = true)
            }
            val matchQuery = searchQuery.isBlank() ||
                node.name.contains(searchQuery, ignoreCase = true) ||
                node.server.contains(searchQuery, ignoreCase = true)
            matchFilter && matchQuery
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                item {
                    Text(
                        text = "共 ${filteredNodes.size} / ${active.proxies.size} 个独立节点（点击可编辑基础项或原始 YAML）",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                items(filteredNodes, key = { it.id }) { node ->
                    NodeCard(
                        node = node,
                        onClick = { editingNode = node },
                        onDelete = { viewModel.deleteNode(node.id) }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            containerColor = SkyAccent,
            contentColor = PureWhite,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "添加节点")
        }
    }

    if (showAddDialog) {
        AddNodeDialog(
            onDismiss = { showAddDialog = false },
            onAddUri = { uri ->
                showAddDialog = false
                viewModel.addNodeFromUri(uri)
            },
            onAddYaml = { yaml ->
                showAddDialog = false
                viewModel.addNodeFromYaml(yaml)
            }
        )
    }

    editingNode?.let { node ->
        EditNodeDialog(
            node = node,
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        OutlinedTextField(
                            value = dialerProxy,
                            onValueChange = { dialerProxy = it },
                            label = { Text("单节点 dialer-proxy（可选，留空直连）") },
                            placeholder = { Text("填写前置节点名或策略组名") },
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
