package com.configbox.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.configbox.app.ui.ConfigBoxUiState
import com.configbox.app.ui.ConfigBoxViewModel
import com.configbox.app.ui.theme.BorderGray
import com.configbox.app.ui.theme.EmeraldActive
import com.configbox.app.ui.theme.PureWhite
import com.configbox.app.ui.theme.SkyAccent
import com.configbox.app.ui.theme.SlateInk
import com.configbox.app.ui.theme.SlateMuted
import com.configbox.app.ui.theme.SoftSurface

@Composable
fun ExportScreen(
    uiState: ConfigBoxUiState,
    viewModel: ConfigBoxViewModel
) {
    val active = uiState.activeProfile
    if (active == null) {
        EmptyProfileNotice("请先在「配置」页导入或选择一个 Clash 配置文件")
        return
    }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val localUrl = viewModel.getLocalSubUrl().orEmpty()

    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/yaml")
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(uiState.compiledYaml.toByteArray(Charsets.UTF_8))
                } ?: error("无法打开目标文件")
                viewModel.postMessage("已导出完整 Mihomo / Clash 配置文件")
            }.onFailure { err ->
                viewModel.postMessage("导出失败：${err.message}")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Local HTTP Subscription Server Card
        Card(
            colors = CardDefaults.cardColors(containerColor = PureWhite),
            border = BorderStroke(
                if (uiState.serverRunning) 1.5.dp else 1.dp,
                if (uiState.serverRunning) EmeraldActive else BorderGray
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("本机 HTTP 订阅服务", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "开启后可直接将下方链接填入 ClashMetaForAndroid / FlClash 订阅",
                            fontSize = 12.sp,
                            color = SlateMuted
                        )
                    }
                    Switch(
                        checked = uiState.serverRunning,
                        onCheckedChange = { viewModel.toggleLocalServer() }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = SoftSurface,
                    border = BorderStroke(1.dp, BorderGray),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Link, null, Modifier.size(16.dp), tint = SkyAccent)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = localUrl,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = SlateInk,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(localUrl))
                        viewModel.postMessage("已复制本机订阅链接")
                    },
                    border = BorderStroke(1.dp, BorderGray),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("复制本机订阅链接", color = SlateInk)
                }
            }
        }

        // Direct Export Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { exportFileLauncher.launch("${active.meta.name}.yaml") },
                colors = ButtonDefaults.buttonColors(containerColor = SkyAccent),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.Download, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("导出 .yaml 文件")
            }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(uiState.compiledYaml))
                    viewModel.postMessage("已复制完整编译后 YAML")
                },
                border = BorderStroke(1.dp, BorderGray),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("复制 YAML", color = SlateInk)
            }
        }

        // Live Compiled YAML Preview
        val lineCount = uiState.compiledYaml.lines().size
        Text(
            text = "编译后最终 YAML 实时预览（共 $lineCount 行 · 含 ${active.proxies.count { !it.dialerProxy.isNullOrBlank() }} 个已配置 dialer-proxy 的中转节点）",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
        Surface(
            color = PureWhite,
            border = BorderStroke(1.dp, BorderGray),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Text(
                text = uiState.compiledYaml,
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = SlateInk
            )
        }
    }
}
