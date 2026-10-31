package com.homeworkassistant.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp

private data class GuidePage(val eyebrow: String, val title: String, val body: String)
private val pages = listOf(
    GuidePage("01 · 收好你的学习待办", "还有什么没写，\n一眼看清",
        "集中查看各门未结课课程的作业和考试，快速找到未完成任务与已读取的截止时间。作业提交后，完成状态会自动核对更新。"),
    GuidePage("02 · 多一个解题思路", "遇到难题，\n让 AI 帮你理思路",
        "读取题目、生成参考答案，再由你选择是否回填。图片题需开启“图片题输入”，并选择支持图片的模型。"),
    GuidePage("03 · 最后一步，由你确认", "核对完成，再提交",
        "回填后，请在官方作业页面检查答案并提交。仅生成答案、回填或保存草稿，都不会被算作已完成。")
)

@Composable
fun GuideScreen(onFinish: () -> Unit, replay: Boolean = false, saving: Boolean = false,
    error: String? = null, initialPage: Int = 0) {
    var page by rememberSaveable { mutableIntStateOf(initialPage.coerceIn(pages.indices)) }
    val content = pages[page]
    BackHandler(enabled = page > 0 || replay) {
        if (!saving) { if (page > 0) page-- else onFinish() }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Check, null, Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Text("写了吗？", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onFinish, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("跳过") }
        }
    }, bottomBar = {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.semantics(mergeDescendants = true) { stateDescription = "第 ${page + 1} 页，共 3 页" },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(3) { dot -> Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (dot <= page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) }
                    }
                    Text("${page + 1} / 3", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (page > 0) OutlinedIconButton(onClick = { page-- }, enabled = !saving, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "上一页")
                    }
                    Button(onClick = { if (page == pages.lastIndex) onFinish() else page++ }, enabled = !saving,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
                        Text(if (saving) "正在保存…" else if (page == pages.lastIndex) "开始使用" else "下一步")
                    }
                }
            }
        }
    }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val compact = maxHeight < 500.dp || LocalDensity.current.fontScale > 1.3f
            val scroll = key(page) { rememberScrollState() }
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(scroll)
                .padding(horizontal = 24.dp).padding(top = if (compact) 8.dp else 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 28.dp)) {
                key(page) { GuideIllustration(page, compact) }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(content.eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(content.title, Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineLarge.copy(lineBreak = LineBreak.Heading))
                    Text(content.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (page == 1) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("AI 答案仅供参考，请结合课程内容核对。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable private fun GuideIllustration(page: Int, compact: Boolean) {
    val icon = when (page) { 0 -> Icons.Default.List; 1 -> Icons.Default.Edit; else -> Icons.Default.Check }
    val caption = if (compact) listOf("学习待办", "AI 参考思路", "核对后提交")[page]
        else listOf("待办，心里有数", "参考思路 · 自主选择", "核对 → 官方页面提交")[page]
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 20.dp else 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                    Icon(icon, null, Modifier.padding(if (compact) 12.dp else 16.dp).size(if (compact) 26.dp else 34.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(caption, Modifier.weight(1f),
                    style = (if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
                        .copy(lineBreak = LineBreak.Heading), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (!compact) {
                val labels = when (page) { 0 -> listOf("课程作业", "考试安排"); 1 -> listOf("读取题目", "生成参考答案"); else -> listOf("检查答案", "手动提交") }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    labels.forEachIndexed { index, label ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .8f)) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("0${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Box(Modifier.width(if (index == 0) 40.dp else 28.dp).height(4.dp).clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.outlineVariant))
                            }
                        }
                    }
                }
            }
        }
    }
}
