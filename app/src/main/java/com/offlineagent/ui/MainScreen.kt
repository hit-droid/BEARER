package com.offlineagent.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onOpenSettings: () -> Unit) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    var goal by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.refreshStatus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("离线智能体") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            // 状态条
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { }, label = { Text(uiState.engineName) })
                AssistChip(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    label = { Text(if (uiState.accessibilityEnabled) "无障碍：已开启" else "无障碍：未开启") },
                )
            }

            // 目标输入
            OutlinedTextField(
                value = goal,
                onValueChange = { goal = it },
                label = { Text("输入任务目标，例如：打开设置并进入关于手机") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                keyboardOptions = KeyboardOptions(autoCorrect = false),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.start(goal) },
                    enabled = !uiState.running && goal.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("开始执行") }
                Button(
                    onClick = { viewModel.stop() },
                    enabled = uiState.running,
                    modifier = Modifier.weight(1f),
                ) { Text("停止") }
            }

            // 实时观察
            Text("当前界面观察", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Card(
                modifier = Modifier.fillMaxWidth().weight(0.4f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Text(
                    text = uiState.observation.ifBlank { "（暂无界面快照）" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
                )
            }

            // 执行日志
            Text("执行日志", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(0.6f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(uiState.logs) { entry ->
                    val color = levelColor(entry.level)
                    Text(
                        text = "[${entry.time}] ${entry.level}  ${entry.text}",
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

private fun levelColor(level: String): Color = when (level) {
    "PLAN" -> Color(0xFF5BC0BE)
    "OBS" -> Color(0xFF9BC1BC)
    "ACT" -> Color(0xFF7CE38B)
    "DONE" -> Color(0xFFFFD166)
    "WARN" -> Color(0xFFFFB454)
    "FAIL", "ERROR" -> Color(0xFFFF6B6B)
    else -> Color(0xFFEAEAEA)
}
