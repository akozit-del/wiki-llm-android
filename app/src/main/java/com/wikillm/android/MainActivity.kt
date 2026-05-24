package com.wikillm.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wikillm.android.ui.screens.ModelsScreen
import com.wikillm.android.ui.screens.WikiScreen
import com.wikillm.android.ui.screens.ChatScreen
import com.wikillm.android.rag.WikiSearchScreen
import com.wikillm.android.diag.DiagScreen
import com.wikillm.android.settings.SettingsScreen
import com.wikillm.android.ui.theme.ThemePrefs
import com.wikillm.android.ui.theme.WikiLLMTheme
import com.wikillm.android.ui.theme.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mode by ThemePrefs.mode.collectAsState()
            val dark = mode.isDark()
            val view = LocalView.current
            SideEffect {
                // Light status/nav bar icons on dark themes, dark icons on light.
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            WikiLLMTheme(mode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
    }
}

@Composable
fun AppNav() {
    val navController = rememberNavController()
    // Chat is the home screen (OpenWebUI-style); everything else is reachable
    // from the chat screen's navigation drawer / settings.
    NavHost(navController = navController, startDestination = "chat") {
        composable("chat") { ChatScreen(navController) }
        composable("models") { ModelsScreen(navController) }
        composable("wiki") { WikiScreen(navController) }
        composable("wikisearch") { WikiSearchScreen(navController) }
        composable("diag") { DiagScreen(navController) }
        composable("settings") { SettingsScreen(navController) }
    }
}
