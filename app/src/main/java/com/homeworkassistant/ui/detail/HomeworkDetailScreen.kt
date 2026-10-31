package com.homeworkassistant.ui.detail

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homeworkassistant.ui.AnswerUiState
import com.homeworkassistant.ui.PageImportUiState
import com.homeworkassistant.ui.components.OfficialWebView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkDetailScreen(url: String, title: String, state: AnswerUiState, onBack: () -> Unit,
    onGenerate: (WebView) -> Unit, onFill: (WebView) -> Unit, onEdit: (String, String) -> Unit,
    onCancel: () -> Unit, pageImport: PageImportUiState = PageImportUiState(), onSyncPage: (WebView) -> Unit = {},
    allowAi: Boolean = true, onCancelSync: () -> Unit = {}, onPageFinished: (WebView) -> Unit = {}, examMode: Boolean = false) {
    var view by remember { mutableStateOf<WebView?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var compare by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var framesMenu by remember { mutableStateOf(false) }
    BackHandler { if (sheet) sheet = false else if (compare) compare = false else if (view?.canGoBack() == true) view?.goBack() else onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = { if (state.answers.isNotEmpty()) TextButton(onClick = { compare = !compare }) { Text(if (compare) "收起对照" else "原文对照") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }, floatingActionButton = {
        if (allowAi) ExtendedFloatingActionButton(onClick = { sheet = true }, icon = { Icon(Icons.Default.Edit, null) }, text = { Text("AI 作答") })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SelectionContainer { Text(if (examMode && !pageImport.busy) "进入具体考试并等待题目显示后，可使用 AI 作答。答案仅供参考，请在官方页面核对并手动交卷。" else pageImport.message,
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
            FlowRow {
                TextButton(enabled = pageReady && !pageImport.busy, onClick = { view?.let(onSyncPage) }) { Text(if (pageImport.busy) "读取中…" else "同步作业与考试") }
                TextButton(enabled = !pageImport.busy, onClick = { view?.loadUrl(if (examMode) com.homeworkassistant.data.mcp.McpChaoxingParser.EXAM_LIST else com.homeworkassistant.data.sync.HomeworkSyncEntries.AGGREGATE) }) { Text(if (examMode) "考试列表" else "全部作业网页") }
                if (pageImport.frames.isNotEmpty()) Box {
                    TextButton(onClick = { framesMenu = true }) { Text("打开内嵌页面") }
                    DropdownMenu(framesMenu, { framesMenu = false }) {
                        pageImport.frames.sortedBy { if (it.contains("/work", true)) 0 else 1 }.forEachIndexed { i, target ->
                            val host = runCatching { java.net.URI(target).host }.getOrDefault("")
                            DropdownMenuItem(text = { Text("${if (target.contains("/work", true)) "作业页面" else "内嵌页面 ${i + 1}"} · $host") },
                                onClick = { framesMenu = false; view?.loadUrl(target) })
                        }
                    }
                }
            }
            if (pageImport.busy) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                LinearProgressIndicator(Modifier.weight(1f))
                TextButton(onClick = onCancelSync) { Text("停止同步") }
            }
            error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
            OfficialWebView(url, Modifier.fillMaxWidth().weight(1f), onCreated = { view = it }, onError = { error = it },
                onReleased = { view = null; pageReady = false }, onReadiness = { pageReady = it }, onFinished = onPageFinished)
            if (compare) {
                HorizontalDivider()
                Text("上方为官方原文，下方答案可编辑后复制", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
                LazyColumn(Modifier.weight(.8f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.answers, key = { it.questionId }) { answer ->
                        val label = state.page?.questions?.find { it.id == answer.questionId }?.displayLabel ?: "答案"
                        OutlinedTextField(answer.answer, { onEdit(answer.questionId, it) }, Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text(label) })
                    }
                }
            }
        }
    }
    if (sheet) AiAnswerBottomSheet(state, { sheet = false }, onEdit, { view?.let(onFill) }, { view?.let(onGenerate) }, onCancel,
        { sheet = false; compare = true })
}
