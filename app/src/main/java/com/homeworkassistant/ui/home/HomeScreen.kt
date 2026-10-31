package com.homeworkassistant.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.ui.components.*
import com.homeworkassistant.ui.preview.PreviewData
import com.homeworkassistant.ui.theme.HomeworkTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUiState, onRefresh: () -> Unit, onFilter: (HomeFilter) -> Unit,
    onOpen: (Homework) -> Unit, onSettings: () -> Unit, onLogin: () -> Unit,
    onOfficial: () -> Unit, modifier: Modifier = Modifier, onExams: () -> Unit = {}, onCancelSync: () -> Unit = {},
    onRefreshCurrent: () -> Unit = onRefresh, onForceRefresh: () -> Unit = onRefresh, onResumeReads: () -> Unit = onRefresh) {
    Scaffold(modifier, containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Default.Check, null, Modifier.padding(8.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Text(stringResource(com.homeworkassistant.R.string.app_name), style = MaterialTheme.typography.titleLarge)
            }
        }, actions = {
            TextButton(onClick = onExams) { Text("考试") }
            var refreshMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { refreshMenu = true }) { Icon(Icons.Default.MoreVert, "刷新选项") }
                DropdownMenu(refreshMenu, { refreshMenu = false }) {
                    DropdownMenuItem(text = { Text("刷新过期课程") }, onClick = { refreshMenu = false; onRefresh() })
                    DropdownMenuItem(text = { Text("刷新当前课程") }, enabled = state.currentCourseId != null,
                        onClick = { refreshMenu = false; onRefreshCurrent() })
                    DropdownMenuItem(text = { Text("强制全量刷新") }, onClick = { refreshMenu = false; onForceRefresh() })
                }
            }
            IconButton(onClick = onOfficial) { Icon(Icons.Default.AccountCircle, "打开官方页面") }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        if (state.accountId == null && state.initialized) {
            GettingStartedHome(onLogin, onSettings, Modifier.padding(padding).fillMaxSize())
            return@Scaffold
        }
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val compact = state.settings.compact || maxHeight < 470.dp || LocalDensity.current.fontScale > 1.25f
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                HomeworkList(state.content?.visibleItems.orEmpty(), state.snapshot == null && state.refreshing,
                    state.content?.summary?.calculatedAt ?: java.time.Instant.now(), reminderHours = state.settings.reminderHours, onOpen = onOpen, showEmpty = state.snapshot != null,
                    header = {
                        item(key = "overview") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (state.needsLogin) FilledTonalButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("登录已失效，重新登录") }
                                if (state.refreshing || state.detailsUpdating) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(state.syncProgress.ifBlank { "正在同步…" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = onCancelSync) { Text(if (state.detailsUpdating && !state.refreshing) "停止补充" else "停止同步") }
                                }
                                state.content?.let { content ->
                                    OverviewHeader(content.summary, state.snapshot?.fetchedAt, state.refreshing, state.cacheLabel,
                                        compact, state.filter.courseId, onRefresh, { onFilter(state.filter.copy(courseId = it)) })
                                } ?: if (state.refreshing || !state.initialized) Skeleton(Modifier.fillMaxWidth())
                                else StatusPlaceholder("同步后展示作业总览", "刷新", onRefresh)
                                if (state.error?.let { it.contains("人机验证") || it.contains("限制访问") } == true) TextButton(onClick = onResumeReads) { Text("验证后重试") }
                                state.currentCourseId?.let { id -> state.courseCache[id]?.let { info ->
                                    val last = info.successfulAt?.atZone(java.time.ZoneId.of("Asia/Shanghai"))
                                        ?.format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm")) ?: "尚未成功更新"
                                    Text("当前课程 · ${if (state.refreshing) "更新中" else if (info.lastError != null) "更新失败，使用缓存" else if (state.cacheLabel) "本地缓存" else "已更新"} · 上次成功 $last",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } }
                                if (state.aiConfigured == false && !state.needsLogin) AiSetupHint(onSettings)
                                StudyTabs(listOf("未完成", "已完成"), if (state.filter.completed) 1 else 0,
                                    { onFilter(state.filter.copy(completed = it == 1)) })
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextField(state.filter.query, { onFilter(state.filter.copy(query = it)) }, Modifier.weight(1f),
                                        placeholder = { Text(if (compact) "搜索作业" else "搜索作业或科目", maxLines = 1, style = MaterialTheme.typography.bodyMedium) }, singleLine = true,
                                        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) }, shape = RoundedCornerShape(16.dp),
                                        colors = TextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                                    IconButton(onClick = { onFilter(state.filter.copy(ascending = !state.filter.ascending)) }) {
                                        Icon(if (state.filter.ascending) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                            if (state.filter.ascending) "截止时间正序，点击倒序" else "截止时间倒序，点击正序")
                                    }
                                    var menu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { menu = true }) { Icon(Icons.AutoMirrored.Filled.List, "筛选科目") }
                                        DropdownMenu(menu, { menu = false }) {
                                            DropdownMenuItem(text = { Text("全部科目") }, onClick = { onFilter(state.filter.copy(courseId = null)); menu = false })
                                            state.snapshot?.items?.map { it.course }?.distinctBy { it.id }?.forEach { course ->
                                                DropdownMenuItem(text = { Text(course.name) }, onClick = { onFilter(state.filter.copy(courseId = course.id)); menu = false })
                                            }
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val name = state.snapshot?.items?.firstOrNull { it.course.id == state.filter.courseId }?.course?.name
                                    Text(name ?: if (state.filter.completed) "已完成作业" else "接下来要完成", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    Text("${state.content?.visibleItems?.size ?: 0} 项${if (state.filter.courseId != null || state.filter.query.isNotBlank()) " · 筛选结果" else ""}",
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (state.snapshot == null && !state.refreshing && state.error != null) StatusPlaceholder("暂时无法获取作业", "打开官方页面", onOfficial)
                            }
                        }
                    })
            }
        }
    }
}
@Preview(showBackground = true, widthDp = 393, heightDp = 852) @Composable private fun HomeScreenPreview() {
    HomeworkTheme { HomeScreen(PreviewData.state, {}, {}, {}, {}, {}, {}) }
}
