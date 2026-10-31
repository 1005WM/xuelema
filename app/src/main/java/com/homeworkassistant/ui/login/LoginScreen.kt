package com.homeworkassistant.ui.login

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.homeworkassistant.ui.components.OfficialWebView
import com.homeworkassistant.ui.LoginUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onVerify: (WebView) -> Unit, onManualVerify: (WebView) -> Unit, onBack: (() -> Unit)? = null,
    state: LoginUiState = LoginUiState()) {
    var view by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    val verify by rememberUpdatedState(onVerify)
    LaunchedEffect(view, pageReady) {
        while (true) { kotlinx.coroutines.delay(1500); if (pageReady) view?.let(verify) }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("登录学习通") }, actions = {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Default.Close, "返回") }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }, bottomBar = {
        Surface(color = MaterialTheme.colorScheme.surface) { Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("连接你的课程与作业", style = MaterialTheme.typography.titleMedium)
                Text("请在官方网页登录。App 不读取或保存你的密码；登录态仅用于获取你有权访问的作业。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.message, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { view?.let(onManualVerify) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = view != null && pageReady && !state.busy) {
                Text(if (state.busy) "正在验证…" else "我已完成登录，验证登录状态")
            }
        } }
    }) { padding ->
        OfficialWebView("https://i.chaoxing.com", Modifier.padding(padding).fillMaxSize(),
            onCreated = { view = it }, onFinished = onVerify, allowExternalLogin = true, onReleased = { view = null; pageReady = false },
            onReadiness = { pageReady = it })
    }
}
