package com.configbox.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.configbox.app.ui.ConfigBoxApp
import com.configbox.app.ui.ConfigBoxViewModel
import com.configbox.app.ui.theme.ConfigBoxTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ConfigBoxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ConfigBoxTheme {
                ConfigBoxApp(viewModel = viewModel)
            }
        }
    }
}
