package com.wikillm.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wikillm.android.ui.screens.HomeScreen
import com.wikillm.android.ui.screens.ModelsScreen
import com.wikillm.android.ui.screens.WikiScreen
import com.wikillm.android.ui.screens.ChatScreen
import com.wikillm.android.rag.WikiSearchScreen
import com.wikillm.android.ui.theme.WikiLLMTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WikiLLMTheme {
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
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { HomeScreen(navController) }
        composable("models") { ModelsScreen(navController) }
        composable("wiki") { WikiScreen(navController) }
        composable("chat") { ChatScreen(navController) }
        composable("wikisearch") { WikiSearchScreen(navController) }
    }
}
