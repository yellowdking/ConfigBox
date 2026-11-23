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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.MergeType
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.configbox.app.model.ProxyGroupEntry
import com.configbox.app.ui.ConfigBoxUiState
import com.configbox.app.ui.ConfigBoxViewModel
import com.configbox.app.ui.components.draggedItemVisual
import com.configbox.app.ui.components.longPressReorderContainer
import com.configbox.app.ui.components.rememberDragReorderState
import com.configbox.app.ui.theme.BorderGray
import com.configbox.app.ui.theme.EmeraldActive
import com.configbox.app.ui.theme.PureWhite
import com.configbox.app.ui.theme.RoseDanger
import com.configbox.app.ui.theme.SkyAccent
import com.configbox.app.ui.theme.SlateInk
import com.configbox.app.ui.theme.SlateMuted
import com.configbox.app.ui.theme.SoftSurface

private val GROUP_TYPES = listOf(
    "select" to "手动选择",
    "url-test" to "自动测速",
    "fallback" to "故障转移",
    "load-balance" to "负载均衡"
)

private val EMOJI_CATEGORIES = listOf(
    "常用" to listOf("🚀", "✈️", "⚡", "♻️", "🎯", "🏠", "🛡️", "🔒", "🔗", "⭐", "🔥", "💎"),
    "地区" to listOf("🇭🇰", "🇸🇬", "🇯🇵", "🇺🇸", "🇬🇧", "🇩🇪", "🇹🇼", "🇰🇷", "🇫🇷", "🇳🇱", "🇨🇦", "🌍"),
    "场景" to listOf("🤖", "🎬", "📺", "🎮", "🎵", "💬", "☁️", "💼", "🛒", "📰", "📡", "🍎")
)

@Composable
fun GroupsScreen(
    uiState: ConfigBoxUiState,
    viewModel: ConfigBoxViewModel
) {
    val active = uiState.activeProfile
    val activeId = active?.meta?.id
    val groups = active?.proxyGroups.orEmpty()
    val proxies = active?.proxies.orEmpty()

    var editingGroup by remember { mutableStateOf<ProxyGroupEntry?>(null) }
    var isNewGroup by remember { mutableStateOf(false) }
    var showMixinDialog by remember { mutableStateOf(false) }

    val donorProfiles = remember(uiState.allProfiles, activeId) {
        uiState.allProfiles.filter { it.meta.id != activeId && it.proxyGroups.isNotEmpty() }
    }

    val relayedNodeNames = remember(proxies) {
        proxies.filter { !it.dialerProxy.isNullOrBlank() }.map { it.name }.toSet()
    }

    val groupListState = rememberLazyListState()
    val groupReorderState = rememberDragReorderState(listState = groupListState, headerCount = 1)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = groupListState,
            modifier = Modifier
                .fillMaxSize()
                .longPressReorderContainer(
                    reorderState = groupReorderState,
                    enabled = groups.isNotEmpty(),
                    itemCount = groups.size,
                    onSwap = { from, to -> viewModel.swapProxyGroupsInMemory(from, to) },
                    onDragFinished = { viewModel.persistCurrentOrder() }
                ),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "groups_hint") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (active == null) {
                            "尚未导入配置，点右下角「+」可直接新建策略组"
                        } else {
                            "共 ${groups.size} 个策略组 · 长按拖动排序"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (donorProfiles.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { showMixinDialog = true },
                            border = BorderStroke(1.dp, BorderGray),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Outlined.MergeType, null, Modifier.size(15.dp), tint = SkyAccent)
                            Spacer(Modifier.width(4.dp))
                            Text("从配置池混入", fontSize = 12.sp, color = SlateInk)
                        }
                    }
                }
            }

            if (groups.isEmpty()) {
                item(key = "groups_empty_card") {
                    Surface(
                        color = PureWhite,
                        border = BorderStroke(1.dp, BorderGray),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("暂无策略组", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "可以直接点击下方按钮新建自定义策略组，后续再添加节点或从配置池混入。",
                                fontSize = 12.sp,
                                color = SlateMuted
                            )
                            Button(
                                onClick = {
                                    isNewGroup = true
                                    editingGroup = ProxyGroupEntry(
                                        name = "🚀 ",
                                        type = "select",
                                        proxies = emptyList(),
                                        rawYaml = ""
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SkyAccent)
                            ) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("新建策略组", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            itemsIndexed(groups, key = { _, it -> it.id }) { idx, group ->
                val isDragging = groupReorderState.draggedDataIndex == idx
                GroupCard(
                    group = group,
                    isDragging = isDragging,
                    modifier = Modifier.draggedItemVisual(isDragging, groupReorderState.dragOffsetY),
                    onClick = {
                        isNewGroup = false
                        editingGroup = group
                    },
                    onDelete = { viewModel.deleteProxyGroup(group.id) }
                )
            }
        }

        FloatingActionButton(
            onClick = {
                isNewGroup = true
                editingGroup = ProxyGroupEntry(
                    name = "🏠 ",
                    type = "select",
                    proxies = emptyList(),
                    rawYaml = ""
                )
            },
            containerColor = SkyAccent,
            contentColor = PureWhite,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "新建策略组")
        }
    }

    if (showMixinDialog && donorProfiles.isNotEmpty()) {
        MixinFromPoolDialog(
            title = "从配置池混入策略组",
            modeGroupsOnly = true,
            donorProfiles = donorProfiles,
            onDismiss = { showMixinDialog = false },
            onConfirm = { donorId, groupNames, _, populateWithCurrentNodes ->
                showMixinDialog = false
                viewModel.mixinFromDonorProfile(
                    donorProfileId = donorId,
                    selectedGroupNames = groupNames,
                    selectedRuleIds = emptySet(),
                    autoPopulateWithCurrentNodes = populateWithCurrentNodes
                )
            }
        )
    }

    editingGroup?.let { group ->
        val otherGroupNames = groups.filter { it.id != group.id }.map { it.name }
        val allNodeNames = proxies.map { it.name }
        GroupEditDialog(
            initial = group,
            isNew = isNewGroup,
            otherGroupNames = otherGroupNames,
            allNodeNames = allNodeNames,
            relayedNodeNames = relayedNodeNames,
            onDismiss = { editingGroup = null },
            onSave = { name, type, members, url, interval ->
                editingGroup = null
                viewModel.upsertProxyGroup(
                    existingId = if (isNewGroup) null else group.id,
                    name = name,
                    type = type,
                    proxies = members,
                    url = url,
                    interval = interval
                )
            }
        )
    }
}

@Composable
private fun GroupCard(
    group: ProxyGroupEntry,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
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
            Icon(
                imageVector = Icons.Outlined.DragIndicator,
                contentDescription = "长按拖动",
                tint = SlateMuted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        color = SkyAccent.copy(alpha = 0.1f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = group.type,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SkyAccent
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${group.proxies.size} 项",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = SlateMuted
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = group.proxies.take(6).joinToString(" · ") + if (group.proxies.size > 6) " …" else "",
                    fontSize = 12.sp,
                    color = SlateMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除策略组", tint = RoseDanger, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun GroupEditDialog(
    initial: ProxyGroupEntry,
    isNew: Boolean,
    otherGroupNames: List<String>,
    allNodeNames: List<String>,
    relayedNodeNames: Set<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, type: String, members: List<String>, url: String?, interval: Int?) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var type by remember { mutableStateOf(initial.type) }
    val memberCandidates = remember(otherGroupNames, allNodeNames) {
        otherGroupNames + allNodeNames + listOf("DIRECT", "REJECT")
    }
    val validCandidateSet = remember(memberCandidates) { memberCandidates.toSet() }
    var selectedMembers by remember(initial.id, memberCandidates) {
        mutableStateOf(initial.proxies.filter { it in validCandidateSet })
    }
    var testUrl by remember { mutableStateOf(initial.url ?: "https://www.gstatic.com/generate_204") }
    var intervalText by remember { mutableStateOf((initial.interval ?: 300).toString()) }
    var emojiCatIdx by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text(if (isNew) "新建策略组" else "编辑策略组") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Emoji quick picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("快捷 Emoji", style = MaterialTheme.typography.labelMedium)
                    EMOJI_CATEGORIES.forEachIndexed { idx, (catName, _) ->
                        FilterChip(
                            selected = emojiCatIdx == idx,
                            onClick = { emojiCatIdx = idx },
                            label = { Text(catName, fontSize = 11.sp) }
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    EMOJI_CATEGORIES[emojiCatIdx].second.forEach { emoji ->
                        Surface(
                            color = SoftSurface,
                            border = BorderStroke(1.dp, BorderGray),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.clickable {
                                val stripped = name.trimStart()
                                name = "$emoji $stripped".trimEnd()
                            }
                        ) {
                            Text(
                                text = emoji,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("策略组名称") },
                    placeholder = { Text("例如：🏠 美国家宽 或 🤖 AI 专用") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("策略组类型", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GROUP_TYPES.forEach { (key, label) ->
                        FilterChip(
                            selected = type == key,
                            onClick = { type = key },
                            label = { Text("$label ($key)", fontSize = 11.sp) }
                        )
                    }
                }

                if (type in setOf("url-test", "fallback", "load-balance")) {
                    OutlinedTextField(
                        value = testUrl,
                        onValueChange = { testUrl = it },
                        label = { Text("测速地址 (url)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("测速间隔（秒）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (selectedMembers.isNotEmpty()) {
                    Text("已选成员顺序（${selectedMembers.size} 项，可上下调整）", style = MaterialTheme.typography.labelMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        selectedMembers.forEachIndexed { idx, member ->
                            Surface(
                                color = EmeraldActive.copy(alpha = 0.06f),
                                border = BorderStroke(1.dp, EmeraldActive.copy(alpha = 0.4f)),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${idx + 1}. " + if (member in relayedNodeNames) "⛓ $member" else member,
                                        fontSize = 11.sp,
                                        color = SlateInk,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            if (idx > 0) {
                                                val m = selectedMembers.toMutableList()
                                                val item = m.removeAt(idx)
                                                m.add(idx - 1, item)
                                                selectedMembers = m
                                            }
                                        },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(Icons.Outlined.ArrowUpward, contentDescription = "上移", modifier = Modifier.size(14.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            if (idx < selectedMembers.lastIndex) {
                                                val m = selectedMembers.toMutableList()
                                                val item = m.removeAt(idx)
                                                m.add(idx + 1, item)
                                                selectedMembers = m
                                            }
                                        },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(Icons.Outlined.ArrowDownward, contentDescription = "下移", modifier = Modifier.size(14.dp))
                                    }
                                    IconButton(
                                        onClick = { selectedMembers = selectedMembers - member },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除", tint = RoseDanger, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Text("添加 / 勾选成员（带 ⛓ 表示已配中转）", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    memberCandidates.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowItems.forEach { candidate ->
                                val isSelected = candidate in selectedMembers
                                val label = if (candidate in relayedNodeNames) "⛓ $candidate" else candidate
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            selectedMembers = if (isSelected) {
                                                selectedMembers - candidate
                                            } else {
                                                selectedMembers + candidate
                                            }
                                        },
                                    color = if (isSelected) EmeraldActive.copy(alpha = 0.1f) else SoftSurface,
                                    border = BorderStroke(1.dp, if (isSelected) EmeraldActive else BorderGray),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = label,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        fontSize = 11.sp,
                                        color = if (isSelected) EmeraldActive else SlateInk,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
                onClick = {
                    onSave(
                        name.trim(),
                        type,
                        selectedMembers.ifEmpty { listOf("DIRECT") },
                        testUrl,
                        intervalText.toIntOrNull() ?: 300
                    )
                },
                enabled = name.trim().isNotBlank()
            ) {
                Text("保存策略组")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
