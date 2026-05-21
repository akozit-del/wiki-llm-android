package com.wikillm.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Чат") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("Назад") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text("Этап 6: чат с моделью + RAG по Википедии появится здесь.")
            Spacer(Modifier.height(8.dp))
            Text("Будет опция «Прочесть N статей по ключевому слову».")
        }
    }
}
