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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import com.offlineagent.core.Action

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    container: com.offlineagent.di.AppContainer,
    onOpenSettings: () -> Unit,
    onOpenScripts: () -> Unit,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    var goal by remember { mutableStateOf("") }
    val context = LocalContext.current
    val gridMode = container.settings.gridMode

    LaunchedEffect(Unit) { viewModel.refreshStatus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("离线智能体") },
                actions = {
                    TextButton(onClick = onOpenScripts) { Text("我的脚本") }
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

            // 录制模式开关：开启后本次运行结束会自动保存为可回放脚本
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("录制本次任务为脚本", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = uiState.recordMode, onCheckedChange = { viewModel.setRecordMode(it) })
            }

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

            // 「先摸清地形」：自动探索当前前台应用，把页面地图与导航关系写进本地知识库，
            // 探索只点击安全控件、不输入文本，后续任务规划会因此更准。
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { viewModel.exploreApp() },
                    enabled = !uiState.running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("探索当前应用（积累本地知识）") }
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

            // 网格参考覆盖层（开启网格模式时显示），便于对照单元格坐标下达 tap_grid
            if (gridMode) {
                Text("网格参考（tap_grid: row∈[0,${Action.GRID_ROWS}), col∈[0,${Action.GRID_COLS})）",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp))
                Card(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                        val rows = Action.GRID_ROWS
                        val cols = Action.GRID_COLS
                        val w = size.width
                        val h = size.height
                        for (r in 0..rows) {
                            val y = r * h / rows
                            drawLine(Color(0xFF4DD0E1), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                        }
                        for (c in 0..cols) {
                            val x = c * w / cols
                            drawLine(Color(0xFF4DD0E1), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                        }
                        val textPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 11f
                        }
                        for (r in 0 until rows) for (c in 0 until cols) {
                            if ((r * cols + c) % 3 == 0) {
                                val x = (c + 0.5f) * w / cols
                                val y = (r + 0.5f) * h / rows
                                drawContext.canvas.nativeCanvas.drawText("$r,$c", x - 12f, y + 4f, textPaint)
                            }
                        }
                    }
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
    // 探索与知识沉淀相关
    "PAGE", "EXPLORE", "BACK" -> Color(0xFF8ECAE6)
    "NAV" -> Color(0xFF48CAE4)
    // 脚本录制/回放与本地模型标注
    "REC", "STEP", "LLM" -> Color(0xFFC792EA)
    else -> Color(0xFFEAEAEA)
}
