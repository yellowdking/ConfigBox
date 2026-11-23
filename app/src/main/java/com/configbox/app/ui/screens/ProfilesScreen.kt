package com.configbox.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.configbox.app.model.ProfileMeta
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
fun ProfilesScreen(
    uiState: ConfigBoxUiState,
    viewModel: ConfigBoxViewModel
) {
    val context = LocalContext.current
    var showUrlDialog by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var showComposeDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("无法读取文件内容")
                val fileName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "本地配置"
                viewModel.importFromYaml(name = fileName, rawYaml = content)
            }.onFailure { err ->
                viewModel.postMessage("读取文件失败：${err.message}")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = SkyAccent
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ImportActionsCard(
                    profileCount = uiState.profiles.size,
                    poolNodeCount = uiState.globalNodePool.size,
                    canCompose = uiState.allProfiles.isNotEmpty(),
                    onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
                    onImportUrl = { showUrlDialog = true },
                    onPasteYaml = { showPasteDialog = true },
                    onImportDemo = { viewModel.importDemoProfile() },
                    onComposeNew = { showComposeDialog = true }
                )
            }

            if (uiState.profiles.isEmpty()) {
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
                            Text(
                                text = "尚未导入 Clash 配置文件",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "导入后自动拆分为「配置池」与「全局节点池」，可在任意基底上自由混入其他配置的策略组、规则并挑选节点。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SlateMuted
                            )
                        }
                    }
                }
            } else {
                item {
                    Text(
                        text = "配置池 (${uiState.profiles.size}) · 全局节点池 (${uiState.globalNodePool.size} 节点)",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                items(uiState.profiles, key = { it.id }) { profile ->
                    val isActive = uiState.activeProfile?.meta?.id == profile.id
                    ProfileCard(
                        profile = profile,
                        isActive = isActive,
                        onSelect = { viewModel.selectProfile(profile.id) },
                        onRefreshSub = { viewModel.refreshSubscriptionNodes(profile.id) },
                        onSyncPool = { viewModel.syncProfileFromGlobalPool(profile.id) },
                        onDelete = { viewModel.deleteProfile(profile.id) }
                    )
                }
            }
        }
    }

    if (showComposeDialog && uiState.allProfiles.isNotEmpty()) {
        ComposeProfileDialog(
            allProfiles = uiState.allProfiles,
            globalNodePool = uiState.globalNodePool,
            poolLatencies = uiState.poolLatencies,
            onDismiss = { showComposeDialog = false },
            onCompose = { name, baseId, incBaseGroups, incBaseRules, donorGroups, donorRules, poolKeys, autoGroups ->
                showComposeDialog = false
                viewModel.composeCustomProfile(
                    profileName = name,
                    baseProfileId = baseId,
                    includeBaseGroups = incBaseGroups,
                    includeBaseRules = incBaseRules,
                    donorGroupMap = donorGroups,
                    donorRuleMap = donorRules,
                    selectedPoolKeys = poolKeys,
                    autoPopulateGroups = autoGroups
                )
            }
        )
    }

    if (showUrlDialog) {
        ImportUrlDialog(
            onDismiss = { showUrlDialog = false },
            onConfirm = { name, url ->
                showUrlDialog = false
                viewModel.importFromUrl(name, url)
            }
        )
    }

    if (showPasteDialog) {
        ImportPasteDialog(
            onDismiss = { showPasteDialog = false },
            onConfirm = { name, yaml ->
                showPasteDialog = false
                viewModel.importFromYaml(name, yaml)
            }
        )
    }
}

@Composable
private fun ImportActionsCard(
    profileCount: Int,
    poolNodeCount: Int,
    canCompose: Boolean,
    onPickFile: () -> Unit,
    onImportUrl: () -> Unit,
    onPasteYaml: () -> Unit,
    onImportDemo: () -> Unit,
    onComposeNew: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PureWhite),
        border = BorderStroke(1.dp, BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "导入配置入池 · 自由撮合定制",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "每个导入的配置都会自动拆分为「配置池」与「全局节点池」；选定基底后可混入任意配置的策略组/规则并自由挑节点。",
                fontSize = 12.sp,
                color = SlateMuted
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPickFile,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = SkyAccent)
                ) {
                    Icon(Icons.Outlined.FileOpen, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("本地 YAML", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onImportUrl,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, BorderGray)
                ) {
                    Icon(Icons.Outlined.CloudDownload, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("订阅链接", fontSize = 13.sp, color = SlateInk)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPasteYaml,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, BorderGray)
                ) {
                    Icon(Icons.Outlined.ContentPaste, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("粘贴文本", fontSize = 13.sp, color = SlateInk)
                }
                OutlinedButton(
                    onClick = onImportDemo,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, BorderGray)
                ) {
                    Icon(Icons.Outlined.Science, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("示例配置", fontSize = 13.sp, color = SlateInk)
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onComposeNew,
                enabled = canCompose,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldActive,
                    contentColor = PureWhite
                )
            ) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (canCompose) {
                        "撮合新建配置（配置池 $profileCount · 节点池 $poolNodeCount）"
                    } else {
                        "撮合新建配置（请先导入至少 1 份配置）"
                    },
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: ProfileMeta,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRefreshSub: () -> Unit,
    onSyncPool: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(containerColor = PureWhite),
        border = BorderStroke(if (isActive) 1.5.dp else 1.dp, if (isActive) EmeraldActive else BorderGray),
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
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (profile.isComposed) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = SkyAccent.copy(alpha = 0.12f),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = "撮合配置",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SkyAccent
                                )
                            }
                        }
                        if (isActive) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = EmeraldActive.copy(alpha = 0.12f),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = "当前编辑",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = EmeraldActive
                                )
                            }
                        }
                    }
                    if (profile.isComposed && profile.baseProfileName.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "基底配置：${profile.baseProfileName}",
                            fontSize = 11.sp,
                            color = SlateMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (profile.subscriptionUrl.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = profile.subscriptionUrl,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = SlateMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (profile.isComposed) {
                    IconButton(onClick = onSyncPool) {
                        Icon(Icons.Outlined.Sync, contentDescription = "同步全局节点池最新参数", tint = EmeraldActive)
                    }
                }
                if (profile.subscriptionUrl.isNotBlank()) {
                    IconButton(onClick = onRefreshSub) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "仅更新订阅节点", tint = SkyAccent)
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除配置", tint = RoseDanger)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatChip("${profile.nodeCount} 节点")
                if (profile.subscriptionCount > 0) {
                    StatChip("${profile.subscriptionCount} 节点订阅")
                }
                StatChip("${profile.groupCount} 策略组")
                StatChip("${profile.ruleCount} 规则")
                StatChip("${profile.relayBoundCount} 已中转")
            }
        }
    }
}

@Composable
private fun StatChip(text: String) {
    Surface(
        color = SoftSurface,
        border = BorderStroke(1.dp, BorderGray),
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = SlateMuted
        )
    }
}

@Composable
private fun ImportUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text("从订阅链接导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("配置名称") },
                    placeholder = { Text("例如：主力机场") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("订阅链接 (支持 Clash YAML / Base64 / URI)") },
                    placeholder = { Text("https://...") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.ifBlank { "远程订阅" }, url) },
                enabled = url.isNotBlank()
            ) {
                Text("拉取并拆分")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ImportPasteDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, yaml: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var yaml by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text("粘贴 Clash YAML 配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("配置名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = yaml,
                    onValueChange = { yaml = it },
                    label = { Text("YAML / Base64 / 节点 URI 内容") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.ifBlank { "粘贴配置" }, yaml) },
                enabled = yaml.isNotBlank()
            ) {
                Text("导入并拆分")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
