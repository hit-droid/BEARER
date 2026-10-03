package com.offlineagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 脚本库页面：列出已录制的可回放脚本，支持一键回放与删除。
 * 回放会跳转回主页（由调用方 popBackStack）并在主页日志中观察进度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的脚本（${uiState.scripts.size}）") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
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
            if (uiState.scripts.isEmpty()) {
                Text(
                    "还没有录制任何脚本。\n\n回到主页，打开「录制本次任务为脚本」，执行一次任务后即可在此回放。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(uiState.scripts, key = { it.id }) { script ->
                        ScriptCard(
                            script = script,
                            onReplay = { viewModel.replay(script); onBack() },
                            onDelete = { viewModel.deleteScript(script.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptCard(
    script: com.offlineagent.core.ActionScript,
    onReplay: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(script.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "目标：${script.goal.ifBlank { "（未记录）" }}",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
            )
            Text(
                "${script.steps.size} 步 · ${script.packageName.ifEmpty { "多应用" }} · " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(script.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onReplay, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("回放")
                }
                Button(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("删除")
                }
            }
        }
    }
}
