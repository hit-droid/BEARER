package com.offlineagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.offlineagent.ui.MainScreen
import com.offlineagent.ui.MainViewModel
import com.offlineagent.ui.SettingsScreen
import com.offlineagent.ui.theme.OfflineAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 在 Composable 作用域外捕获 container，避免作用域内无法访问 application
        val container = (application as OfflineAgentApp).container
        setContent {
            OfflineAgentTheme {
                val navController: NavHostController = rememberNavController()
                val viewModel: MainViewModel = viewModel()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(viewModel, container, onOpenSettings = { navController.navigate("settings") })
                    }
                    composable("settings") {
                        SettingsScreen(container, onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
