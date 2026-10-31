package com.homeworkassistant.ui.about

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AboutScreen(onBack: () -> Unit) {
    val resources = LocalResources.current
    val notices = remember(resources) { resources.openRawResource(com.homeworkassistant.R.raw.third_party_notices).bufferedReader().use { it.readText() } }
    Scaffold(topBar = { TopAppBar(title = { Text("关于与开源许可") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${stringResource(com.homeworkassistant.R.string.app_name)} ${com.homeworkassistant.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
            Text("本项目仅供学习交流。使用第三方平台数据需遵守其服务条款，由此产生的账号风险由使用者自行承担。AI 答案仅供参考，提交前需人工确认。")
            Text("数据保存在设备本地。只有主动生成答案时，所选题目才会发送给你配置的 AI 服务。App 不保存学习通明文密码。")
            Text(notices, style = MaterialTheme.typography.bodySmall)
        }
    }
}
