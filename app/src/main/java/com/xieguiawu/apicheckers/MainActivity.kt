package com.xieguiawu.apicheckers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xieguiawu.apicheckers.data.SecureSettings
import com.xieguiawu.apicheckers.ui.DetailScreen
import com.xieguiawu.apicheckers.ui.HomeScreen
import com.xieguiawu.apicheckers.ui.QwenDetailScreen
import com.xieguiawu.apicheckers.ui.SettingsScreen
import com.xieguiawu.apicheckers.ui.theme.ApiCheckersTheme
import com.xieguiawu.apicheckers.ui.theme.Bg

class MainActivity : ComponentActivity() {
    // 单一 ViewModel 实例，三个路由共享同一份 UiState
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须在 ViewModel 创建前初始化（AppViewModel.init 中也会幂等调用）
        SecureSettings.init(applicationContext)
        setContent {
            ApiCheckersTheme {
                // Surface 包裹保证 LocalContentColor = onBackground（TextMain），
                // 避免 Material 默认黑色文字泄漏
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    AppNav(vm)
                }
            }
        }
    }
}

@Composable
private fun AppNav(vm: AppViewModel) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = vm,
                onOpenAccount = { nav.navigate("account/$it") },
                onOpenQwen = { nav.navigate("qwen/$it") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("account/{id}") { backStackEntry ->
            DetailScreen(
                vm = vm,
                id = backStackEntry.arguments?.getString("id") ?: "",
                onBack = { nav.popBackStack() },
            )
        }
        composable("qwen/{id}") { backStackEntry ->
            QwenDetailScreen(
                vm = vm,
                id = backStackEntry.arguments?.getString("id") ?: "",
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}
