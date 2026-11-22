package com.configbox.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.configbox.app.ui.screens.ExportScreen
import com.configbox.app.ui.screens.GroupsScreen
import com.configbox.app.ui.screens.NodesScreen
import com.configbox.app.ui.screens.ProfilesScreen
import com.configbox.app.ui.screens.RulesScreen
import com.configbox.app.ui.theme.BorderGray
import com.configbox.app.ui.theme.EmeraldActive
import com.configbox.app.ui.theme.PureWhite
import com.configbox.app.ui.theme.SkyAccent
import com.configbox.app.ui.theme.SlateMuted
import com.configbox.app.ui.theme.SoftSurface

private data class NavTab(val label: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigBoxApp(viewModel: ConfigBoxViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    val tabs = listOf(
        NavTab("配置", Icons.Outlined.FolderOpen),
        NavTab("节点", Icons.Outlined.Dns),
        NavTab("策略组", Icons.Outlined.Layers),
        NavTab("规则", Icons.Outlined.Rule),
        NavTab("导出", Icons.Outlined.Share)
    )

    Scaffold(
        containerColor = SoftSurface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(PureWhite)) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = PureWhite),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "ConfigBox",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            val active = uiState.activeProfile
                            if (active != null) {
                                Spacer(Modifier.width(10.dp))
                                Surface(
                                    color = SoftSurface,
                                    border = BorderStroke(1.dp, BorderGray),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = active.meta.name,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        fontSize = 12.sp,
                                        color = SlateMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        if (uiState.serverRunning) {
                            Surface(
                                color = EmeraldActive.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, EmeraldActive),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.padding(end = 12.dp)
                            ) {
                                Text(
                                    text = "SUB :${uiState.serverPort}",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = EmeraldActive
                                )
                            }
                        }
                    }
                )
                HorizontalDivider(thickness = 1.dp, color = BorderGray)
            }
        },
        bottomBar = {
            Column(modifier = Modifier.background(PureWhite)) {
                HorizontalDivider(thickness = 1.dp, color = BorderGray)
                NavigationBar(
                    containerColor = PureWhite,
                    tonalElevation = 0.dp
                ) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(22.dp)) },
                            label = { Text(tab.label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = SkyAccent,
                                selectedTextColor = SkyAccent,
                                unselectedIconColor = SlateMuted,
                                unselectedTextColor = SlateMuted,
                                indicatorColor = SkyAccent.copy(alpha = 0.12f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> ProfilesScreen(uiState = uiState, viewModel = viewModel)
                1 -> NodesScreen(uiState = uiState, viewModel = viewModel)
                2 -> GroupsScreen(uiState = uiState, viewModel = viewModel)
                3 -> RulesScreen(uiState = uiState, viewModel = viewModel)
                4 -> ExportScreen(uiState = uiState, viewModel = viewModel)
            }
        }
    }
}
