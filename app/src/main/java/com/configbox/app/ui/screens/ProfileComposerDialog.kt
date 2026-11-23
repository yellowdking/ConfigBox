package com.configbox.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.configbox.app.engine.LatencyResult
import com.configbox.app.model.GlobalPoolNode
import com.configbox.app.model.SplitProfile
import com.configbox.app.ui.theme.AmberBadge
import com.configbox.app.ui.theme.BorderGray
import com.configbox.app.ui.theme.EmeraldActive
import com.configbox.app.ui.theme.PureWhite
import com.configbox.app.ui.theme.SkyAccent
import com.configbox.app.ui.theme.SlateInk
import com.configbox.app.ui.theme.SlateMuted
import com.configbox.app.ui.theme.SoftSurface

/**
 * Interactive 4-step Config Composer:
 * 1. Choose a Base profile from the Config Pool.
 * 2. Cherry-pick extra proxy-groups & rules (including RULE-SET auto-merge) from any other profiles.
 * 3. Pick desired nodes from the unified Global Node Pool (across all imported profiles).
 * 4. Choose which proxy-groups should automatically receive the picked nodes.
 */
@Composable
fun ComposeProfileDialog(
    allProfiles: List<SplitProfile>,
    globalNodePool: List<GlobalPoolNode>,
    poolLatencies: Map<String, LatencyResult>,
    onDismiss: () -> Unit,
    onCompose: (
        profileName: String,
        baseProfileId: String,
        includeBaseGroups: Boolean,
        includeBaseRules: Boolean,
        donorGroupMap: Map<String, Set<String>>,
        donorRuleMap: Map<String, Set<String>>,
        selectedPoolKeys: Set<String>,
        autoPopulateGroups: Set<String>
    ) -> Unit
) {
    if (allProfiles.isEmpty()) return

    var stepTab by remember { mutableIntStateOf(0) } // 0 = 基底, 1 = 策略/规则撮合, 2 = 节点池挑选
    var profileName by remember { mutableStateOf("") }
    var baseProfileId by remember { mutableStateOf(allProfiles.first().meta.id) }
    var includeBaseGroups by remember { mutableStateOf(true) }
    var includeBaseRules by remember { mutableStateOf(true) }

    // Donor selections keyed by donor profileId
    var donorGroupMap by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var donorRuleMap by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }

    val otherDonors = remember(allProfiles, baseProfileId) {
        allProfiles.filter { it.meta.id != baseProfileId }
    }
    var activeDonorId by remember(otherDonors) {
        mutableStateOf(otherDonors.firstOrNull()?.meta?.id.orEmpty())
    }
    val activeDonor = otherDonors.firstOrNull { it.meta.id == activeDonorId } ?: otherDonors.firstOrNull()

    // Global Node Pool selection
    var poolSourceFilter by remember { mutableStateOf("全部来源") }
    var poolSearch by remember { mutableStateOf("") }
    var selectedPoolKeys by remember { mutableStateOf(setOf<String>()) }

    val poolSources = remember(globalNodePool) {
        listOf("全部来源") + globalNodePool.map { it.originProfileName }.distinct()
    }
    val filteredPoolNodes = remember(globalNodePool, poolSourceFilter, poolSearch) {
        globalNodePool.filter { entry ->
            val matchSource = poolSourceFilter == "全部来源" || entry.originProfileName == poolSourceFilter
            val matchQuery = poolSearch.isBlank() ||
                entry.node.name.contains(poolSearch, ignoreCase = true) ||
                entry.node.server.contains(poolSearch, ignoreCase = true) ||
                entry.originProfileName.contains(poolSearch, ignoreCase = true)
            matchSource && matchQuery
        }
    }

    val baseProfile = allProfiles.firstOrNull { it.meta.id == baseProfileId } ?: allProfiles.first()

    // Compute resulting group names so user can pick which groups auto-receive the chosen nodes
    val resultingGroupNames = remember(baseProfile, includeBaseGroups, donorGroupMap, allProfiles) {
        buildList {
            if (includeBaseGroups) {
                baseProfile.proxyGroups.forEach { add(it.name) }
            }
            allProfiles.filter { it.meta.id != baseProfileId }.forEach { donor ->
                val picked = donorGroupMap[donor.meta.id].orEmpty()
                donor.proxyGroups.filter { it.name in picked }.forEach { add(it.name) }
            }
        }.distinct()
    }
    var autoPopulateGroups by remember(resultingGroupNames) {
        mutableStateOf(resultingGroupNames.take(2).toSet())
    }

    val totalExtraGroups = donorGroupMap.filterKeys { it != baseProfileId }.values.sumOf { it.size }
    val totalExtraRules = donorRuleMap.filterKeys { it != baseProfileId }.values.sumOf { it.size }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = {
            Column {
                Text("撮合新建自定义配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "基底 + 混入策略规则 (+$totalExtraGroups 组 / +$totalExtraRules 规则) + ${selectedPoolKeys.size} 节点",
                    fontSize = 11.sp,
                    color = SlateMuted
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = stepTab, containerColor = PureWhite) {
                    Tab(selected = stepTab == 0, onClick = { stepTab = 0 }, text = { Text("1.基底", fontSize = 12.sp) })
                    Tab(selected = stepTab == 1, onClick = { stepTab = 1 }, text = { Text("2.规则/组", fontSize = 12.sp) })
                    Tab(selected = stepTab == 2, onClick = { stepTab = 2 }, text = { Text("3.选节点", fontSize = 12.sp) })
                }
                Spacer(Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (stepTab) {
                        0 -> {
                            OutlinedTextField(
                                value = profileName,
                                onValueChange = { profileName = it },
                                label = { Text("新配置名称") },
                                placeholder = { Text("${baseProfile.meta.name} · 定制") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("从配置池选择基底配置（继承 DNS / 基础设置 / rule-providers）", style = MaterialTheme.typography.labelMedium)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                allProfiles.forEach { prof ->
                                    val isSelected = prof.meta.id == baseProfileId
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { baseProfileId = prof.meta.id },
                                        color = if (isSelected) SkyAccent.copy(alpha = 0.1f) else SoftSurface,
                                        border = BorderStroke(1.dp, if (isSelected) SkyAccent else BorderGray),
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = prof.meta.name,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (isSelected) SkyAccent else SlateInk
                                                )
                                                Text(
                                                    text = "${prof.proxyGroups.size} 策略组 · ${prof.rules.size} 规则 · ${prof.proxies.size} 节点",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 10.sp,
                                                    color = SlateMuted
                                                )
                                            }
                                            if (isSelected) {
                                                Text("基底", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = SkyAccent)
                                            }
                                        }
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("继承基底策略组 (${baseProfile.proxyGroups.size})", fontSize = 13.sp)
                                Switch(checked = includeBaseGroups, onCheckedChange = { includeBaseGroups = it })
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("继承基底分流规则 (${baseProfile.rules.size})", fontSize = 13.sp)
                                Switch(checked = includeBaseRules, onCheckedChange = { includeBaseRules = it })
                            }
                        }

                        1 -> {
                            if (otherDonors.isEmpty()) {
                                Surface(
                                    color = SoftSurface,
                                    border = BorderStroke(1.dp, BorderGray),
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "当前配置池中只有 1 个配置；后续导入配置 2、3、4 后，即可在这里自由混入它们的策略组与分流规则。",
                                        modifier = Modifier.padding(14.dp),
                                        fontSize = 12.sp,
                                        color = SlateMuted
                                    )
                                }
                            } else {
                                Text("选择素材来源配置（可切换多个配置叠加挑选）", style = MaterialTheme.typography.labelMedium)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    otherDonors.forEach { donor ->
                                        val gCnt = donorGroupMap[donor.meta.id]?.size ?: 0
                                        val rCnt = donorRuleMap[donor.meta.id]?.size ?: 0
                                        val badge = if (gCnt + rCnt > 0) " (+${gCnt}组/${rCnt}规)" else ""
                                        FilterChip(
                                            selected = activeDonor?.meta?.id == donor.meta.id,
                                            onClick = { activeDonorId = donor.meta.id },
                                            label = { Text("${donor.meta.name}$badge", fontSize = 11.sp) }
                                        )
                                    }
                                }

                                if (activeDonor != null) {
                                    val donorId = activeDonor.meta.id
                                    val pickedGroups = donorGroupMap[donorId].orEmpty()
                                    val pickedRules = donorRuleMap[donorId].orEmpty()

                                    // Cherry-pick proxy-groups
                                    Text("挑取策略组 (${pickedGroups.size}/${activeDonor.proxyGroups.size})", style = MaterialTheme.typography.labelMedium)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        activeDonor.proxyGroups.forEach { g ->
                                            val sel = g.name in pickedGroups
                                            FilterChip(
                                                selected = sel,
                                                onClick = {
                                                    val next = if (sel) pickedGroups - g.name else pickedGroups + g.name
                                                    donorGroupMap = donorGroupMap + (donorId to next)
                                                },
                                                label = { Text("${g.name} (${g.type})", fontSize = 11.sp) }
                                            )
                                        }
                                    }

                                    HorizontalDivider(color = BorderGray)

                                    // Cherry-pick rules (with 1-tap target-group batch selectors!)
                                    val rulesByTarget = remember(activeDonor.rules) {
                                        activeDonor.rules
                                            .filterNot { it.type.equals("MATCH", ignoreCase = true) }
                                            .groupBy { it.target }
                                    }
                                    Text(
                                        text = "按目标策略组一键批量勾选规则（自动置于 MATCH 之前并补全 RULE-SET）",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        rulesByTarget.forEach { (targetName, rules) ->
                                            val targetIds = rules.map { it.id }.toSet()
                                            val allChecked = targetIds.isNotEmpty() && targetIds.all { it in pickedRules }
                                            FilterChip(
                                                selected = allChecked,
                                                onClick = {
                                                    val next = if (allChecked) pickedRules - targetIds else pickedRules + targetIds
                                                    donorRuleMap = donorRuleMap + (donorId to next)
                                                },
                                                label = { Text("$targetName (${rules.size}条)", fontSize = 11.sp) }
                                            )
                                        }
                                    }

                                    // Individual rule preview (first 40 non-MATCH rules for responsive UI)
                                    val previewRules = activeDonor.rules.take(40)
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        previewRules.forEach { rule ->
                                            val sel = rule.id in pickedRules
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        val next = if (sel) pickedRules - rule.id else pickedRules + rule.id
                                                        donorRuleMap = donorRuleMap + (donorId to next)
                                                    },
                                                color = if (sel) EmeraldActive.copy(alpha = 0.1f) else SoftSurface,
                                                border = BorderStroke(1.dp, if (sel) EmeraldActive else BorderGray),
                                                shape = MaterialTheme.shapes.extraSmall
                                            ) {
                                                Text(
                                                    text = rule.toClashRuleString(),
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 10.sp,
                                                    color = if (sel) EmeraldActive else SlateInk,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        if (activeDonor.rules.size > 40) {
                                            Text(
                                                text = "其余 ${activeDonor.rules.size - 40} 条规则可使用上方「按目标策略组」一键批量勾选",
                                                fontSize = 11.sp,
                                                color = SlateMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        else -> {
                            OutlinedTextField(
                                value = poolSearch,
                                onValueChange = { poolSearch = it },
                                placeholder = { Text("搜索节点池（名称 / 地址 / 来源配置）...", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                poolSources.forEach { src ->
                                    FilterChip(
                                        selected = poolSourceFilter == src,
                                        onClick = { poolSourceFilter = src },
                                        label = { Text(src, fontSize = 11.sp) }
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("已选 ${selectedPoolKeys.size} / 筛选 ${filteredPoolNodes.size}", style = MaterialTheme.typography.labelMedium)
                                Row {
                                    TextButton(onClick = {
                                        selectedPoolKeys = selectedPoolKeys + filteredPoolNodes.map { it.key }
                                    }) { Text("全选筛选", fontSize = 11.sp) }
                                    TextButton(onClick = { selectedPoolKeys = emptySet() }) { Text("清空", fontSize = 11.sp) }
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                filteredPoolNodes.forEach { entry ->
                                    val sel = entry.key in selectedPoolKeys
                                    val lat = poolLatencies[entry.key]
                                    val latLabel = lat?.latencyMs?.let { "${it}ms" }
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedPoolKeys = if (sel) selectedPoolKeys - entry.key else selectedPoolKeys + entry.key
                                            },
                                        color = if (sel) EmeraldActive.copy(alpha = 0.1f) else SoftSurface,
                                        border = BorderStroke(1.dp, if (sel) EmeraldActive else BorderGray),
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = entry.node.name,
                                                    fontSize = 12.sp,
                                                    color = if (sel) EmeraldActive else SlateInk,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "来自:${entry.originProfileName} · ${entry.node.type.uppercase()} · ${entry.node.server}",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 10.sp,
                                                    color = SlateMuted,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (latLabel != null) {
                                                Spacer(Modifier.width(6.dp))
                                                Text(latLabel, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = EmeraldActive)
                                            }
                                        }
                                    }
                                }
                            }
                            if (resultingGroupNames.isNotEmpty()) {
                                HorizontalDivider(color = BorderGray)
                                Text("自动将所选节点填入以下策略组：", style = MaterialTheme.typography.labelMedium)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    resultingGroupNames.forEach { gName ->
                                        val sel = gName in autoPopulateGroups
                                        FilterChip(
                                            selected = sel,
                                            onClick = {
                                                autoPopulateGroups = if (sel) autoPopulateGroups - gName else autoPopulateGroups + gName
                                            },
                                            label = { Text(gName, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
                onClick = {
                    onCompose(
                        profileName.ifBlank { "${baseProfile.meta.name} · 定制" },
                        baseProfileId,
                        includeBaseGroups,
                        includeBaseRules,
                        donorGroupMap,
                        donorRuleMap,
                        selectedPoolKeys,
                        autoPopulateGroups
                    )
                }
            ) {
                Text("生成配置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * Reusable dialog to cherry-pick proxy groups or rules from ANY other config in the Config Pool
 * directly into the currently active profile.
 */
@Composable
fun MixinFromPoolDialog(
    title: String,
    modeGroupsOnly: Boolean,
    donorProfiles: List<SplitProfile>,
    onDismiss: () -> Unit,
    onConfirm: (donorProfileId: String, groupNames: Set<String>, ruleIds: Set<String>, populateWithCurrentNodes: Boolean) -> Unit
) {
    if (donorProfiles.isEmpty()) return
    var selectedDonorId by remember { mutableStateOf(donorProfiles.first().meta.id) }
    val donor = donorProfiles.firstOrNull { it.meta.id == selectedDonorId } ?: donorProfiles.first()
    var selectedGroups by remember(selectedDonorId) { mutableStateOf(setOf<String>()) }
    var selectedRuleIds by remember(selectedDonorId) { mutableStateOf(setOf<String>()) }
    var populateWithCurrentNodes by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(330.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("1. 选择配置池中的来源配置", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    donorProfiles.forEach { prof ->
                        FilterChip(
                            selected = prof.meta.id == selectedDonorId,
                            onClick = { selectedDonorId = prof.meta.id },
                            label = { Text(prof.meta.name, fontSize = 12.sp) }
                        )
                    }
                }

                if (modeGroupsOnly) {
                    Text("2. 勾选要混入的策略组 (${selectedGroups.size}/${donor.proxyGroups.size})", style = MaterialTheme.typography.labelMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        donor.proxyGroups.forEach { g ->
                            val sel = g.name in selectedGroups
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedGroups = if (sel) selectedGroups - g.name else selectedGroups + g.name
                                    },
                                color = if (sel) EmeraldActive.copy(alpha = 0.1f) else SoftSurface,
                                border = BorderStroke(1.dp, if (sel) EmeraldActive else BorderGray),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(g.name, fontSize = 13.sp, color = if (sel) EmeraldActive else SlateInk, modifier = Modifier.weight(1f))
                                    Text(g.type, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = AmberBadge)
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("自动填入当前配置的节点", fontSize = 12.sp, color = SlateInk)
                        Switch(checked = populateWithCurrentNodes, onCheckedChange = { populateWithCurrentNodes = it })
                    }
                } else {
                    val rulesByTarget = remember(donor.rules) {
                        donor.rules
                            .filterNot { it.type.equals("MATCH", ignoreCase = true) }
                            .groupBy { it.target }
                    }
                    Text("2. 按目标策略组批量勾选规则（已选 ${selectedRuleIds.size} 条，自动补全 RULE-SET）", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rulesByTarget.forEach { (targetName, rules) ->
                            val ids = rules.map { it.id }.toSet()
                            val allChecked = ids.isNotEmpty() && ids.all { it in selectedRuleIds }
                            FilterChip(
                                selected = allChecked,
                                onClick = {
                                    selectedRuleIds = if (allChecked) selectedRuleIds - ids else selectedRuleIds + ids
                                },
                                label = { Text("$targetName (${rules.size}条)", fontSize = 11.sp) }
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        donor.rules.take(40).forEach { rule ->
                            val sel = rule.id in selectedRuleIds
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedRuleIds = if (sel) selectedRuleIds - rule.id else selectedRuleIds + rule.id
                                    },
                                color = if (sel) EmeraldActive.copy(alpha = 0.1f) else SoftSurface,
                                border = BorderStroke(1.dp, if (sel) EmeraldActive else BorderGray),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = rule.toClashRuleString(),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = if (sel) EmeraldActive else SlateInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                enabled = if (modeGroupsOnly) selectedGroups.isNotEmpty() else selectedRuleIds.isNotEmpty(),
                onClick = {
                    onConfirm(selectedDonorId, selectedGroups, selectedRuleIds, populateWithCurrentNodes)
                }
            ) {
                Text("混入当前配置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
