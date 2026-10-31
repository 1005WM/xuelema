package com.homeworkassistant.ui.exams

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.ui.components.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ExamsScreen(snapshot: ExamSnapshot?, refreshing: Boolean, message: String?,
    onRefresh: () -> Unit, onOfficial: () -> Unit, onBack: () -> Unit,
    onOpen: (Exam) -> Unit = { onOfficial() }) {
    var completed by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("考试安排", style = MaterialTheme.typography.titleLarge) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = { IconButton(onClick = onRefresh, enabled = !refreshing) { Icon(Icons.Default.Refresh, "刷新作业与考试") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }) { padding ->
        val visible = snapshot?.items.orEmpty().filter { it.submissionStatus.isCompleted == completed }
            .sortedWith(compareBy<Exam> { it.startsAt == null }.thenBy { it.startsAt })
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("已排除明确关联到结课或未开放课程的考试；课程待确认的考试保留展示。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${snapshot?.items?.size ?: 0} 场考试", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            val time = snapshot?.let { DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(it.fetchedAt) }
                            Text(time?.let { "本地缓存 · 更新于 $it" } ?: "同步后查看课程考试安排", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!message.isNullOrBlank() && !refreshing) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    StudyTabs(listOf("未完成 / 待确认", "已完成"), if (completed) 1 else 0, { completed = it == 1 })
                    Text("考试已结束不代表已交卷，请以答卷状态为准。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onOfficial, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("打开官方考试列表") }
                }
            }
            if (visible.isEmpty()) item { StatusPlaceholder(when {
                refreshing && snapshot == null -> "正在读取考试…"
                message != null && !refreshing -> "本次考试读取存在待确认情况，请查看上方提示或打开官方列表"
                snapshot == null -> "尚无考试缓存，请点击刷新"
                else -> "此分类暂无考试"
            }) }
            items(visible, key = { it.id }) { exam ->
                StudyPanel(Modifier.clickable(role = Role.Button, onClickLabel = "打开考试页面", onClick = { onOpen(exam) })) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(exam.courseName ?: "课程待确认", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(exam.scheduleStatus.ifBlank { "安排待确认" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(exam.title, style = MaterialTheme.typography.titleMedium)
                    Text(exam.timeText.ifBlank { "考试时间待确认" }, style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("答卷：${exam.answerStatus.ifBlank { "待确认" }}", style = MaterialTheme.typography.titleSmall)
                    if (exam.submissionStatus == HomeworkStatus.UNKNOWN) Text("未识别到明确答卷状态，请在官方列表确认", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("时长 ${exam.duration.ifBlank { "未知" }}  ·  方式 ${exam.mode.ifBlank { "未知" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (exam.score.isNotBlank()) Text("得分 ${exam.score}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(if (exam.detailUrl != null) "点击进入本场考试 · 支持 AI 作答" else "未获取到本场入口，点击从官方列表进入",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
