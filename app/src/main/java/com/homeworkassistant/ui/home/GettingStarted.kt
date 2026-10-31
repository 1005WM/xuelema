package com.homeworkassistant.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homeworkassistant.ui.components.StudyPanel

@Composable fun GettingStartedHome(onLogin: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("把学习待办，\n收在一起。", style = MaterialTheme.typography.headlineLarge)
        Text("先连接课程，再安排今天要完成的事。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StudyPanel {
            Text("连接你的课程", style = MaterialTheme.typography.titleLarge)
            Text("登录学习通后，查看有权访问的作业与考试。提交后的完成状态会自动核对。", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("登录学习通") }
        }
        StudyPanel {
            Text("AI，按需开启", style = MaterialTheme.typography.titleMedium)
            Text("可稍后在设置中填写服务地址、模型和 Key。无需配置 AI，也能登录、查看与同步任务。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看设置与使用指南") }
        }
    }
}

@Composable fun AiSetupHint(onSettings: () -> Unit) {
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (!dismissed) StudyPanel {
        Text("需要 AI 时，再来配置", style = MaterialTheme.typography.titleSmall)
        Text("尚未配置完整的 AI 服务，查看和同步作业、考试不受影响。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("配置 AI") }
            TextButton(onClick = { dismissed = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("稍后") }
        }
    }
}
