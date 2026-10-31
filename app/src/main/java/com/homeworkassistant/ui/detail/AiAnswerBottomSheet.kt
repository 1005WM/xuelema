package com.homeworkassistant.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import com.homeworkassistant.ui.components.StudyPanel
import com.homeworkassistant.domain.model.QuestionType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import android.content.ClipData
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import com.homeworkassistant.ui.AnswerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAnswerBottomSheet(state: AnswerUiState, onDismiss: () -> Unit, onEdit: (String, String) -> Unit,
    onFill: () -> Unit, onGenerate: () -> Unit, onCancel: () -> Unit, onCompare: () -> Unit,
    listState: LazyListState = rememberLazyListState()) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val filledCount = state.answers.count { state.fillFeedback[it.questionId] in setOf("已回填", "已有相同答案") }
    val firstPending = state.answers.indexOfFirst { state.fillFeedback[it.questionId] !in setOf("已回填", "已有相同答案") }
    fun copy(text: String) { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("作业答案", text))) } }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false, dragHandle = null) {
        // The modal owns a different focus manager from the underlying Activity.
        val focusManager = LocalFocusManager.current
        val scrollFocus = remember(focusManager) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f) focusManager.clearFocus(force = true)
                    return Offset.Zero
                }
            }
        }
        Column(Modifier.fillMaxWidth().height(windowHeight * .92f).padding(horizontal = 20.dp).imePadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI 作答", style = MaterialTheme.typography.headlineSmall)
                    if (state.fillFeedback.isEmpty()) Text(if (state.answers.isEmpty()) "读懂题目，理清思路。" else "${state.answers.size} 份答案 · 核对后回填",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(enabled = state.answers.isNotEmpty(), onClick = {
                    copy(state.answers.joinToString("\n\n") { answer ->
                        val label = state.page?.questions?.find { it.id == answer.questionId }?.displayLabel ?: "答案"
                        "$label：${answer.answer}\n${answer.explanation}"
                    })
                }) { Text("复制全部") }
                TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "关闭 AI 作答" }) { Text("关闭") }
            }
            if (state.fillFeedback.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已确认 $filledCount / ${state.answers.size} 题", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (firstPending >= 0) TextButton(onClick = { scope.launch { focusManager.clearFocus(); listState.animateScrollToItem(firstPending + 1) } },
                    modifier = Modifier.semantics { contentDescription = "查看待处理题目" }) { Text("待处理 ${state.answers.size - filledCount} 题 ↓") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            state.error?.let { error ->
                SelectionContainer {
                    Text(error, Modifier.fillMaxWidth().heightIn(max = 96.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("ai-answer-list").nestedScroll(scrollFocus), state = listState, overscrollEffect = null, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)) {
                item(key = "guidance") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("提交前请核对 AI 答案。生成时，题目及已启用的图片将发送给设置中的 AI 服务。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer {
                            Column {
                                if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (state.answers.isEmpty()) item {
                    StudyPanel {
                        Text("先读题，再动笔", style = MaterialTheme.typography.titleLarge)
                        Text("打开具体作业，点击下方“开始生成”。答案会显示在这里，你可以逐题修改、复制或与原文对照。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(state.answers, key = { it.questionId }) { answer ->
                    val q = state.page?.questions?.find { it.id == answer.questionId }
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val type = when (q?.type) { QuestionType.SINGLE -> "单选"; QuestionType.MULTIPLE -> "多选"; QuestionType.BOOLEAN -> "判断"; QuestionType.FILL -> "填空"; QuestionType.SHORT -> "简答"; QuestionType.MATCHING -> "连线"; else -> "待识别" }
                                Text("${q?.displayLabel ?: "答案"} · $type", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = { copy(answer.answer) }) { Text("复制本题") }
                            }
                            state.fillFeedback[answer.questionId]?.let { feedback ->
                                val failed = feedback.startsWith("未回填") || feedback.startsWith("待检查")
                                Surface(color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(8.dp)) {
                                    SelectionContainer { Text(feedback, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall,
                                        color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer) }
                                }
                            }
                            q?.let { Text(it.stem, style = MaterialTheme.typography.bodySmall) }
                            if (!q?.options.isNullOrEmpty()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                q?.options?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            if (q?.canFill == false && state.fillFeedback[answer.questionId] == null) Text("回填时将重新检查答案框；若仍无法定位，可复制后手动填写。", style = MaterialTheme.typography.labelSmall)
                            if (q?.type == com.homeworkassistant.domain.model.QuestionType.MATCHING) Text("连线答案按题项顺序使用 JSON 数组，如 [\"B\",\"A\"]", style = MaterialTheme.typography.labelSmall)
                            OutlinedTextField(answer.answer, { onEdit(answer.questionId, it) }, Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text("可编辑答案") })
                            if (answer.explanation.isNotBlank()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (q?.type == QuestionType.FILL) "解题步骤" else "解题思路", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(answer.explanation, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.busy) OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("停止处理") }
                else OutlinedButton(onClick = onGenerate, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (state.answers.isEmpty()) "开始生成" else "补全答案") }
                Button(enabled = !state.busy && state.answers.isNotEmpty(), onClick = onFill, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("回填页面") }
            }
            TextButton(enabled = !state.busy && state.answers.isNotEmpty(), onClick = onCompare, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("原文分屏对照") }
        }
    }
}
