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
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.configbox.app.model.RuleEntry
import com.configbox.app.ui.ConfigBoxUiState
import com.configbox.app.ui.ConfigBoxViewModel
import com.configbox.app.ui.theme.BorderGray
import com.configbox.app.ui.theme.EmeraldActive
import com.configbox.app.ui.theme.PureWhite
import com.configbox.app.ui.theme.RoseDanger
import com.configbox.app.ui.theme.SkyAccent
import com.configbox.app.ui.theme.SlateMuted

private val COMMON_RULE_TYPES = listOf(
    "DOMAIN-SUFFIX",
    "DOMAIN",
    "DOMAIN-KEYWORD",
    "IP-CIDR",
    "IP-CIDR6",
    "GEOIP",
    "GEOSITE",
    "PROCESS-NAME",
    "RULE-SET",
    "MATCH"
)

@Composable
fun RulesScreen(
    uiState: ConfigBoxUiState,
    viewModel: ConfigBoxViewModel
) {
    val active = uiState.activeProfile
    if (active == null) {
        EmptyProfileNotice("请先在「配置」页导入或选择一个 Clash 配置文件")
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    var editingRule by remember { mutableStateOf<RuleEntry?>(null) }
    var isAddingNew by remember { mutableStateOf(false) }

    val availableTargets = remember(active.proxyGroups) {
        (active.proxyGroups.map { it.name } + listOf("DIRECT", "REJECT")).distinct()
    }

    val filteredRules = remember(active.rules, searchQuery) {
        if (searchQuery.isBlank()) {
            active.rules
        } else {
            active.rules.filter { rule ->
                rule.payload.contains(searchQuery, ignoreCase = true) ||
                    rule.target.contains(searchQuery, ignoreCase = true) ||
                    rule.type.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = PureWhite) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索域名、IP、规则类型或目标策略组...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 84.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    val enabledCount = active.rules.count { it.enabled }
                    Text(
                        text = "共 ${active.rules.size} 条规则（已启用 $enabledCount 条，按从上到下顺序匹配）",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                items(filteredRules, key = { it.id }) { rule ->
                    RuleCard(
                        rule = rule,
                        onClick = {
                            isAddingNew = false
                            editingRule = rule
                        },
                        onToggle = { viewModel.toggleRule(rule.id) },
                        onMoveUp = { viewModel.moveRule(rule.id, -1) },
                        onMoveDown = { viewModel.moveRule(rule.id, 1) },
                        onDelete = { viewModel.deleteRule(rule.id) }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                isAddingNew = true
                editingRule = RuleEntry(
                    type = "DOMAIN-SUFFIX",
                    payload = "",
                    target = availableTargets.firstOrNull() ?: "DIRECT"
                )
            },
            containerColor = SkyAccent,
            contentColor = PureWhite,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "添加规则")
        }
    }

    editingRule?.let { rule ->
        RuleEditDialog(
            initial = rule,
            isNew = isAddingNew,
            availableTargets = availableTargets,
            onDismiss = { editingRule = null },
            onSave = { saved ->
                editingRule = null
                if (isAddingNew) viewModel.addRule(saved) else viewModel.updateRule(saved)
            }
        )
    }
}

@Composable
private fun RuleCard(
    rule: RuleEntry,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (rule.enabled) 1f else 0.55f)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = PureWhite),
        border = BorderStroke(1.dp, BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = SkyAccent.copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = rule.type,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SkyAccent
                    )
                }
                Spacer(Modifier.width(6.dp))
                Surface(
                    color = EmeraldActive.copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = "→ ${rule.target}",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = EmeraldActive,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.weight(1f))
                Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            }
            if (rule.payload.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = rule.payload + if (rule.noResolve) "  [no-resolve]" else "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = SlateMuted
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = "上移", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = "下移", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除", tint = RoseDanger, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun RuleEditDialog(
    initial: RuleEntry,
    isNew: Boolean,
    availableTargets: List<String>,
    onDismiss: () -> Unit,
    onSave: (RuleEntry) -> Unit
) {
    var type by remember { mutableStateOf(initial.type) }
    var payload by remember { mutableStateOf(initial.payload) }
    var target by remember { mutableStateOf(initial.target) }
    var noResolve by remember { mutableStateOf(initial.noResolve) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text(if (isNew) "添加规则" else "编辑规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("规则类型", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    COMMON_RULE_TYPES.forEach { ruleType ->
                        FilterChip(
                            selected = type == ruleType,
                            onClick = { type = ruleType },
                            label = { Text(ruleType, fontSize = 11.sp) }
                        )
                    }
                }
                if (type != "MATCH") {
                    OutlinedTextField(
                        value = payload,
                        onValueChange = { payload = it },
                        label = { Text("匹配内容（域名 / IP 段 / 地区代码）") },
                        placeholder = { Text("例如：openai.com 或 192.168.0.0/16") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text("目标策略组 / 中转组", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableTargets.forEach { candidate ->
                        FilterChip(
                            selected = target == candidate,
                            onClick = { target = candidate },
                            label = { Text(candidate, fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("目标策略名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("附加 no-resolve（IP 规则推荐）", fontSize = 13.sp)
                    Switch(checked = noResolve, onCheckedChange = { noResolve = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            type = type.trim(),
                            payload = if (type == "MATCH") "" else payload.trim(),
                            target = target.trim(),
                            noResolve = noResolve
                        )
                    )
                },
                enabled = target.isNotBlank() && (type == "MATCH" || payload.isNotBlank())
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
