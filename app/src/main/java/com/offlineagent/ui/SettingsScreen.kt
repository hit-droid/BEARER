package com.offlineagent.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.offlineagent.automation.AgentAccessibilityService
import com.offlineagent.di.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = container.settings

    var modelPath by remember { mutableStateOf(s.modelPath) }
    var temperature by remember { mutableFloatStateOf(s.temperature) }
    var maxTokens by remember { mutableIntStateOf(s.maxTokens) }
    var autoEnabled by remember { mutableStateOf(AgentAccessibilityService.instance != null) }
    var gridMode by remember { mutableStateOf(s.gridMode) }
    var memStats by remember { mutableStateOf(container.memory.stats()) }
    var showDump by remember { mutableStateOf(false) }
    var dumpText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("推理引擎：${container.engineName}", style = MaterialTheme.typography.titleSmall)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("模型（GGUF）路径", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = modelPath,
                        onValueChange = { modelPath = it },
                        placeholder = { Text("/sdcard/Android/data/com.offlineagent/files/model.gguf") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "将 GGUF 文件通过 ADB 推送到应用私有目录，例如：\n" +
                            "adb push model.gguf /sdcard/Android/data/com.offlineagent/files/",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("温度：%.2f".format(temperature), modifier = Modifier.weight(1f))
                    }
                    androidx.compose.material3.Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..1f,
                    )
                    OutlinedTextField(
                        value = maxTokens.toString(),
                        onValueChange = { maxTokens = it.toIntOrNull() ?: 256 },
                        label = { Text("最大生成 token") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("自动化（无障碍服务）", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (autoEnabled) "状态：已开启" else "状态：未开启",
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = autoEnabled, onCheckedChange = {
                            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        })
                    }
                    Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                        Text("前往系统无障碍设置开启")
                    }
                }
            }

            Button(
                onClick = {
                    s.modelPath = modelPath
                    s.temperature = temperature
                    s.maxTokens = maxTokens
                    s.automationEnabled = autoEnabled
                    s.gridMode = gridMode
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存并返回") }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("网格点按模式", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "开启后主页叠加网格覆盖层，便于参考单元格坐标下达 tap_grid 动作（用于无文字标签的控件）",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Switch(checked = gridMode, onCheckedChange = { gridMode = it })
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地知识库（文档型记忆）", style = MaterialTheme.typography.labelMedium)
                    Text(memStats, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            container.memory.clear()
                            memStats = container.memory.stats()
                            if (showDump) dumpText = container.memory.dump()
                        }, modifier = Modifier.weight(1f)) { Text("清空知识库") }
                        Button(onClick = {
                            showDump = !showDump
                            if (showDump) dumpText = container.memory.dump()
                        }, modifier = Modifier.weight(1f)) { Text(if (showDump) "隐藏" else "预览内容") }
                    }
                    if (showDump) {
                        Text(dumpText, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    Text(
                        "智能体在运行中自动记录各 App 的可交互元素及其用途、所属页面，以及" +
                            "「点此元素→到达页面」的导航关系，下次执行同类任务时整份注入规划器，显著减少盲目试探。所有数据只存本机私有目录。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
