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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.configbox.app.model.RelayGroup
import com.configbox.app.ui.ConfigBoxUiState
import com.configbox.app.ui.ConfigBoxViewModel
import com.configbox.app.ui.theme.BorderGray
import com.configbox.app.ui.theme.EmeraldActive
import com.configbox.app.ui.theme.PureWhite
import com.configbox.app.ui.theme.RoseDanger
import com.configbox.app.ui.theme.SkyAccent
import com.configbox.app.ui.theme.SlateInk
import com.configbox.app.ui.theme.SlateMuted
import com.configbox.app.ui.theme.SoftSurface

@Composable
fun RelaysScreen(
    uiState: ConfigBoxUiState,
    viewModel: ConfigBoxViewModel
) {
    val active = uiState.activeProfile
    if (active == null) {
        EmptyProfileNotice("请先在「配置」页导入或选择一个 Clash 配置文件")
        return
    }

    var editingRelay by remember { mutableStateOf<RelayGroup?>(null) }
    var previewYamlText by remember { mutableStateOf<String?>(null) }

    val entryCandidates = remember(active.proxyGroups, active.proxies) {
        active.proxyGroups.map { it.name } + active.proxies.map { it.name }
    }
    val nodeNames = remember(active.proxies) {
        active.proxies.map { it.name }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    color = PureWhite,
                    border = BorderStroke(1.dp, BorderGray),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Mihomo dialer-proxy 节点中转组",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "原节点保持不变；编译导出时会自动克隆出口落地节点并注入 dialer-proxy，同时生成独立中转策略组。",
                            fontSize = 12.sp,
                            color = SlateMuted
                        )
                    }
                }
            }

            if (active.relayGroups.isEmpty()) {
                item {
                    Surface(
                        color = PureWhite,
                        border = BorderStroke(1.dp, BorderGray),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("尚未添加节点中转组", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "点击右下角 + 按钮，选择「入口前置（节点或策略组）」与「出口落地节点」即可一键组装链式代理。",
                                fontSize = 13.sp,
                                color = SlateMuted
                            )
                        }
                    }
                }
            } else {
                items(active.relayGroups, key = { it.id }) { relay ->
                    RelayGroupCard(
                        relay = relay,
                        onClick = { editingRelay = relay },
                        onToggle = { viewModel.toggleRelayGroup(relay.id) },
                        onPreviewYaml = { previewYamlText = viewModel.previewRelayYaml(relay) },
                        onDelete = { viewModel.deleteRelayGroup(relay.id) }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                editingRelay = RelayGroup(
                    name = "链式中转组 ${active.relayGroups.size + 1}",
                    groupType = "select",
                    entryProxy = entryCandidates.firstOrNull().orEmpty(),
                    exitProxies = nodeNames.lastOrNull()?.let { listOf(it) } ?: emptyList()
                )
            },
            containerColor = SkyAccent,
            contentColor = PureWhite,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "新建中转组")
        }
    }

    editingRelay?.let { relay ->
        RelayEditDialog(
            initial = relay,
            entryCandidates = entryCandidates,
            nodeNames = nodeNames,
            onDismiss = { editingRelay = null },
            onSave = { updated ->
                editingRelay = null
                viewModel.upsertRelayGroup(updated)
            }
        )
    }

    previewYamlText?.let { snippet ->
        AlertDialog(
            onDismissRequest = { previewYamlText = null },
            containerColor = PureWhite,
            title = { Text("生成的 dialer-proxy YAML 预览") },
            text = {
                Surface(
                    color = SoftSurface,
                    border = BorderStroke(1.dp, BorderGray),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = snippet,
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = SlateInk
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { previewYamlText = null }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun RelayGroupCard(
    relay: RelayGroup,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onPreviewYaml: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (relay.enabled) 1f else 0.55f)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = PureWhite),
        border = BorderStroke(if (relay.enabled) 1.5.dp else 1.dp, if (relay.enabled) EmeraldActive else BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(relay.name, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = SkyAccent.copy(alpha = 0.1f),
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = relay.groupType,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = SkyAccent
                            )
                        }
                    }
                    if (relay.injectToMainGroups) {
                        Text("已自动注入主选择策略组", fontSize = 11.sp, color = EmeraldActive)
                    }
                }
                Switch(checked = relay.enabled, onCheckedChange = { onToggle() })
            }

            Spacer(Modifier.height(10.dp))

            // Visual chain flow
            Surface(
                color = SoftSurface,
                border = BorderStroke(1.dp, BorderGray),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val middleStr = if (relay.middleProxies.isNotEmpty()) {
                        " → " + relay.middleProxies.joinToString(" → ")
                    } else {
                        ""
                    }
                    Text(
                        text = "设备 → [${relay.entryProxy}]$middleStr → 出口 (${relay.exitProxies.size} 个)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = SlateInk
                    )
                    Text(
                        text = "出口落地：${relay.exitProxies.joinToString(", ")}",
                        fontSize = 11.sp,
                        color = SlateMuted
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onPreviewYaml,
                    border = BorderStroke(1.dp, BorderGray),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Outlined.Code, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("预览 YAML", fontSize = 12.sp, color = SlateInk)
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除中转组", tint = RoseDanger)
                }
            }
        }
    }
}

@Composable
private fun RelayEditDialog(
    initial: RelayGroup,
    entryCandidates: List<String>,
    nodeNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (RelayGroup) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var groupType by remember { mutableStateOf(initial.groupType) }
    var entryProxy by remember { mutableStateOf(initial.entryProxy) }
    var middleProxies by remember { mutableStateOf(initial.middleProxies) }
    var exitProxies by remember { mutableStateOf(initial.exitProxies) }
    var injectToMain by remember { mutableStateOf(initial.injectToMainGroups) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text("配置 dialer-proxy 节点中转组") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("中转组名称") },
                    placeholder = { Text("例如：美国家宽中转") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("策略组模式", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("select" to "手动选择 (select)", "url-test" to "自动测速 (url-test)").forEach { (key, label) ->
                        FilterChip(
                            selected = groupType == key,
                            onClick = { groupType = key },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }

                Text("1. 入口前置代理（可选策略组或单个节点）", style = MaterialTheme.typography.labelMedium)
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

                Text("2. 中间跳板节点（可选，多跳链路）", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    nodeNames.filter { it != entryProxy && it !in exitProxies }.forEach { nodeName ->
                        val selected = nodeName in middleProxies
                        FilterChip(
                            selected = selected,
                            onClick = {
                                middleProxies = if (selected) middleProxies - nodeName else middleProxies + nodeName
                            },
                            label = { Text(nodeName, fontSize = 11.sp) }
                        )
                    }
                }

                Text("3. 出口落地节点（可多选，自动注入 dialer-proxy）", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    nodeNames.filter { it != entryProxy && it !in middleProxies }.forEach { nodeName ->
                        val selected = nodeName in exitProxies
                        FilterChip(
                            selected = selected,
                            onClick = {
                                exitProxies = if (selected) exitProxies - nodeName else exitProxies + nodeName
                            },
                            label = { Text(nodeName, fontSize = 11.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("自动加入主 select 策略组", fontSize = 13.sp)
                    Switch(checked = injectToMain, onCheckedChange = { injectToMain = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            groupType = groupType,
                            entryProxy = entryProxy.trim(),
                            middleProxies = middleProxies,
                            exitProxies = exitProxies,
                            injectToMainGroups = injectToMain
                        )
                    )
                },
                enabled = name.isNotBlank() && entryProxy.isNotBlank() && exitProxies.isNotEmpty()
            ) {
                Text("保存中转组")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
