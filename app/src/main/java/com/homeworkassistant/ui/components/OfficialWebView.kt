package com.homeworkassistant.ui.components

import android.net.Uri
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

@Composable
fun LoginOriginDialog(request: LoginRedirectApproval) {
    AlertDialog(onDismissRequest = request.decline,
        title = { Text(if (request.externalApp) "打开外部应用？" else "允许学校登录站点？") },
        text = { Text(if (request.externalApp) "官方页面请求打开外部应用（${request.origin}）。返回后可继续在此验证登录。"
            else "即将前往：\n${request.origin}\n\n仅在确认这是你的学校或身份认证服务时允许。本次允许仅对当前登录窗口生效。${if (request.formNavigation) "\n允许后需返回来源页，再次点击登录按钮。" else ""}") },
        confirmButton = { TextButton(onClick = request.allow) { Text(if (request.externalApp) "打开应用" else "本次允许") } },
        dismissButton = { TextButton(onClick = request.decline) { Text("取消") } })
}

@Composable
fun OfficialWebView(url: String, modifier: Modifier = Modifier, onCreated: (WebView) -> Unit = {},
    onFinished: (WebView) -> Unit = {}, onError: (String) -> Unit = {},
    allowExternalLogin: Boolean = false, onReleased: () -> Unit = {}, onReadiness: (Boolean) -> Unit = {}) {
    val finished by rememberUpdatedState(onFinished)
    val created by rememberUpdatedState(onCreated)
    val released by rememberUpdatedState(onReleased)
    val error by rememberUpdatedState(onError)
    val readiness by rememberUpdatedState(onReadiness)
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var browser by remember { mutableStateOf<OfficialBrowserView?>(null) }
    var state by remember { mutableStateOf(BrowserUiState()) }
    var approval by remember { mutableStateOf<LoginRedirectApproval?>(null) }
    var desktop by remember { mutableStateOf(false) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }
    DisposableEffect(Unit) { onDispose { fileCallback?.onReceiveValue(null); fileCallback = null } }
    LaunchedEffect(state.navigation, state.loading) {
        if (state.loading) { delay(25000); browser?.slowLoading() }
    }
    LaunchedEffect(state.loading, state.error, state.origin, approval) {
        readiness(!state.loading && state.error == null && approval == null)
    }
    BackHandler(state.canGoBack || state.popup) { browser?.back() }
    Column(modifier) {
        Surface(tonalElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.origin.ifBlank { "正在连接官方页面…" }, Modifier.weight(1f), maxLines = 1,
                    style = MaterialTheme.typography.labelSmall)
                if (state.popup) TextButton(onClick = { browser?.closePopup() }) { Text("关闭窗口") }
                if (allowExternalLogin) TextButton(onClick = { desktop = !desktop; browser?.toggleDesktop() }) { Text(if (desktop) "手机网页" else "电脑网页") }
                TextButton(onClick = { browser?.retry() }) { Text("刷新") }
            }
        }
        if (state.progress < 100 && state.error == null) LinearProgressIndicator(
            progress = { (state.progress / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                OfficialBrowserView(context, url, allowExternalLogin, { state = it },
                    { web -> if (web != null) created(web) else released() }, { finished(it) },
                    { approval = it }, { error(it) }, { callback, params ->
                        fileCallback?.onReceiveValue(null); fileCallback = callback
                        try { chooser.launch(params.createIntent()); true }
                        catch (_: Exception) { callback.onReceiveValue(null); fileCallback = null; false }
                    }).also { browser = it; it.start() }
            }, onRelease = { it.dispose(); browser = null })
            if (state.loading && state.error == null && approval == null) Surface(Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("正在加载官方页面…", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.error?.let { message ->
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = 3.dp) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Row {
                            TextButton(onClick = { browser?.retry() }) { Text("重试") }
                            TextButton(onClick = { browser?.home() }) { Text("返回官方首页") }
                            if (state.canGoBack || state.popup) TextButton(onClick = { browser?.back() }) { Text("返回上一页") }
                        }
                    }
                }
            }
        }
    }
    approval?.let { LoginOriginDialog(it) }
}
