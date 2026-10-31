package com.homeworkassistant.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.ui.theme.SemanticColors
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun OverviewHeader(summary: OverviewSummary, updatedAt: Instant?, refreshing: Boolean,
    cacheLabel: Boolean, compact: Boolean, selectedCourseId: String?, onRefresh: () -> Unit,
    onCourseSelected: (String?) -> Unit, modifier: Modifier = Modifier) {
    val local = summary.calculatedAt.atZone(ZoneId.of("Asia/Shanghai"))
    val greeting = when (local.hour) { in 5..10 -> "早上好"; in 11..13 -> "中午好"; in 14..18 -> "下午好"; else -> "晚上好" }
    val time = updatedAt?.atZone(ZoneId.of("Asia/Shanghai"))?.format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) ?: "尚未同步"
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(greeting, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(local.format(DateTimeFormatter.ofPattern("M月d日 · EEEE", java.util.Locale.CHINA)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (summary.coverage == SnapshotCoverage.COMPLETE) "待完成作业" else "已发现待完成",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (summary.canCelebrateAllDone) "全部完成" else "${summary.pendingTotal}",
                                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 38.sp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                            if (!summary.canCelebrateAllDone) Text("项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Text("已完成 ${summary.completedTotal}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    IconButton(onClick = onRefresh, enabled = !refreshing) {
                        if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "刷新作业", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Text("已逾期 ${summary.overdueTotal}   ·   24h 内 ${summary.dueWithin24HoursTotal}   ·   本周待交 ${summary.dueThisWeekTotal}",
                    Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelSmall,
                    color = if (summary.overdueTotal > 0) SemanticColors.pending() else MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${if (cacheLabel) "本地缓存 · 上次成功" else "上次成功更新"} $time", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${summary.involvedCourseCount} 个科目", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (summary.unknownStatusTotal > 0) Text("另有 ${summary.unknownStatusTotal} 项状态待识别，请到官方页面检查", style = MaterialTheme.typography.bodySmall)
        if (!compact && summary.pendingTotal > 0) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selectedCourseId == null, { onCourseSelected(null) }, label = { Text("全部科目") })
                summary.courseStats.forEach { stat ->
                    FilterChip(selectedCourseId == stat.course.id, { onCourseSelected(if (selectedCourseId == stat.course.id) null else stat.course.id) },
                        label = { Text("${stat.course.name} · ${stat.pendingCount}", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
    }
}

