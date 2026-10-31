package com.homeworkassistant.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.ui.components.*
import com.homeworkassistant.ui.theme.*
import java.time.*
import java.time.format.DateTimeFormatter

private fun date(value: Instant?): String = value?.atZone(ZoneId.of("Asia/Shanghai"))?.format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) ?: "未提供"
@Composable
fun HomeworkList(items: List<Homework>, loading: Boolean, now: Instant, modifier: Modifier = Modifier,
    reminderHours: Int = 24, onOpen: (Homework) -> Unit = {}, header: (LazyListScope.() -> Unit)? = null, showEmpty: Boolean = true) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        header?.invoke(this)
        if (loading && items.isEmpty()) items(4) { Skeleton(Modifier.fillMaxWidth()) }
        else if (items.isEmpty() && showEmpty) item { StatusPlaceholder("当前筛选条件下没有作业") }
        else items(items, key = { it.id }) { item ->
            val overdue = item.status.isPending && item.dueAt?.isBefore(now) == true
            val soon = item.status.isPending && item.dueAt?.let { !it.isBefore(now) && it.isBefore(now.plusSeconds(reminderHours * 3600L)) } == true
            val color = when {
                overdue -> MaterialTheme.colorScheme.error
                item.status == HomeworkStatus.GRADED -> SemanticColors.graded()
                item.status.isCompleted -> SemanticColors.done()
                else -> SemanticColors.pending()
            }
            val status = when (item.status) {
                HomeworkStatus.NOT_SUBMITTED -> if (overdue) "逾期 · 未交" else if (soon) "即将到期 · 未交" else "未交"
                HomeworkStatus.RETURNED -> "退回待交"
                HomeworkStatus.SUBMITTED -> "已交 · 待批阅"
                HomeworkStatus.GRADED -> "已批阅${item.score?.let { " · $it 分" }.orEmpty()}"
                HomeworkStatus.UNKNOWN -> "状态待识别"
            }
            Surface(onClick = { onOpen(item) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.course.name, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = .09f)) {
                            Text(status, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = color)
                        }
                    }
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("截止 ${item.dueAt?.let(::date) ?: item.dueText ?: "待获取"}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = if (overdue || soon) color else MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.publishedAt != null || item.submittedAt != null) Text(
                        "发布 ${date(item.publishedAt)}${if (item.status.isCompleted) " · 提交 ${date(item.submittedAt)}" else ""}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
