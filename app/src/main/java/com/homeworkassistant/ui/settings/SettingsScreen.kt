package com.homeworkassistant.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.ui.AiConnectionUiState
import com.homeworkassistant.ui.components.*
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(initial: AppSettings, readKey: suspend () -> String, onSave: (AppSettings, String) -> Unit,
    onTest: (AppSettings, String) -> Unit, onClear: () -> Unit, onLogout: () -> Unit, onBack: () -> Unit, onAbout: () -> Unit,
    connection: AiConnectionUiState = AiConnectionUiState(), onModels: (AppSettings, String) -> Unit = { _, _ -> },
    onAiConfigChanged: () -> Unit = {}, onAppearanceChange: (AppSettings) -> Unit = {}, onGuide: () -> Unit = {},
    onFillIntervalChange: (Int) -> Unit = {}) {
    var value by remember { mutableStateOf(initial) }
    var key by remember { mutableStateOf("") }
    var keyReady by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var modelMenu by remember { mutableStateOf(false) }
    var budgetText by remember { mutableStateOf(initial.inputBudgetChars.toString()) }
    var tokensText by remember { mutableStateOf(initial.outputTokens.toString()) }
    val budget = budgetText.toIntOrNull()?.takeIf { it in 2000..100000 }
    val tokens = tokensText.toIntOrNull()?.takeIf { it in 256..16384 }
    val canSave = keyReady && keyError == null && !connection.busy && budget != null && tokens != null
    fun configuredSettings() = value.copy(inputBudgetChars = requireNotNull(budget), outputTokens = requireNotNull(tokens))
    LaunchedEffect(Unit) { try { key = readKey() } catch (e: Exception) { keyError = e.message } finally { keyReady = true } }
    Scaffold(topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }, bottomBar = {
        Surface(color = MaterialTheme.colorScheme.surface) {
            BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                val narrow = maxWidth < 320.dp
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = canSave, onClick = { onSave(configuredSettings(), key) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (narrow) "保存 AI" else "保存 AI 设置") }
                    OutlinedButton(enabled = canSave, onClick = { onTest(configuredSettings(), key) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (narrow) "保存并测试" else "保存并测试连接") }
                }
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            StudyPanel {
                StudySectionTitle("外观与总览", "自动保存 · 主题可跟随系统")
                StudyTabs(listOf("系统", "浅色", "深色"), value.theme.ordinal,
                    { value = value.copy(theme = ThemeMode.entries[it]); onAppearanceChange(value) })
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("总览精简模式", style = MaterialTheme.typography.titleSmall)
                        Text("缩小总览，留更多空间给作业", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(value.compact, { value = value.copy(compact = it); onAppearanceChange(value) })
                }
                Column {
                    Text("截止高亮：提前 ${value.reminderHours} 小时", style = MaterialTheme.typography.bodyMedium)
                    Slider(value.reminderHours.toFloat(), { value = value.copy(reminderHours = it.toInt()) }, valueRange = 1f..168f, steps = 166,
                        onValueChangeFinished = { onAppearanceChange(value) })
                    Text("仅列表高亮，暂不发送系统通知。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            StudyPanel {
            StudySectionTitle("AI 服务配置", "连接服务 → 选择模型 → 测试连接")
            OutlinedTextField(value.baseUrl, { value = value.copy(baseUrl = it); modelMenu = false; onAiConfigChanged() }, Modifier.fillMaxWidth(), label = { Text("API 地址") }, placeholder = { Text("https://api.deepseek.com") }, singleLine = true,
                supportingText = { Text("支持 Base URL 或完整聊天接口地址") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(key, { key = it; keyError = null; modelMenu = false; onAiConfigChanged() }, Modifier.fillMaxWidth(), enabled = keyReady, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            keyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            FilledTonalButton(onClick = { onModels(configuredSettings(), key) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = canSave && value.baseUrl.isNotBlank() && key.isNotBlank()) {
                Text(if (connection.busy) "连接中…" else "保存并获取模型")
            }
            if (connection.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            connection.message?.let { message -> SelectionContainer {
                Text(message, style = MaterialTheme.typography.bodySmall,
                    color = if (connection.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            ExposedDropdownMenuBox(expanded = modelMenu && connection.models.isNotEmpty(),
                onExpandedChange = { if (connection.models.isNotEmpty()) modelMenu = it }) {
                OutlinedTextField(value.model, { value = value.copy(model = it) },
                    Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).fillMaxWidth(), label = { Text("模型（下拉选择或手动填写）") },
                    singleLine = true, trailingIcon = { if (connection.models.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(modelMenu) },
                    supportingText = { Text(if (connection.models.isEmpty()) "填写 URL 和 Key 后获取模型列表" else "选择后自动保存；手动填写请点击保存 AI 设置") })
                ExposedDropdownMenu(expanded = modelMenu && connection.models.isNotEmpty(), onDismissRequest = { modelMenu = false },
                    modifier = Modifier.heightIn(max = 280.dp)) {
                    connection.models.forEach { model -> DropdownMenuItem(text = { Text(model) }, enabled = canSave, onClick = {
                        value = value.copy(model = model); modelMenu = false; onSave(configuredSettings(), key)
                    }) }
                }
            }
            Text("Key 在设备本地加密保存。模型列表来自该服务的 /models；鉴权及列出模型成功不代表余额充足或实际生成一定成功。不提供列表的服务可手动填写模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StudyPanel {
            StudySectionTitle("生成偏好", "按模型能力设置输入与回答长度。")
            Text("回答随机性（temperature）：${String.format(Locale.ROOT, "%.2f", value.temperature)}", style = MaterialTheme.typography.titleSmall)
            Slider(value.temperature, { value = value.copy(temperature = it) }, valueRange = 0f..2f)
            Text("数值越低，回答通常越稳定；越高，表达通常越多样。默认 0.20，范围 0–2，与实际请求参数一致；部分模型可能忽略或限制此参数。修改后点击保存设置。", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("图片题输入", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(value.imageInputEnabled, { value = value.copy(imageInputEnabled = it) })
            }
            Text("仅在确认所选模型支持图片时开启。题干和选项图片将完整发送给配置的 AI 服务；关闭时跳过图片题。App 不限制单题图片张数，逐张读取并显示进度；单张最多 20 MB，实际输入能力以所选模型为准。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(budgetText, { if (it.all(Char::isDigit)) budgetText = it }, Modifier.fillMaxWidth(), label = { Text("每批输入字符预算（保守估算）") },
                isError = budget == null, supportingText = { if (budget == null) Text("请输入 2000–100000 的整数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            OutlinedTextField(tokensText, { if (it.all(Char::isDigit)) tokensText = it }, Modifier.fillMaxWidth(), label = { Text("每批输出 Token 上限") },
                isError = tokens == null, supportingText = { if (tokens == null) Text("请输入 256–16384 的整数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            Text("生成需支持 Chat Completions 与 SSE。修改后点击底部保存设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StudyPanel {
                StudySectionTitle("答案回填", "自动保存 · 作业与考试共用")
                Text("每题间隔 ${String.format(Locale.ROOT, "%.1f", value.fillIntervalMillis / 1000f)} 秒", style = MaterialTheme.typography.titleSmall)
                Slider(value.fillIntervalMillis.toFloat(), { value = value.copy(fillIntervalMillis = (it / 500f).roundToInt() * 500) },
                    modifier = Modifier.semantics { contentDescription = "每题回填间隔" },
                    valueRange = AppSettings.MIN_FILL_INTERVAL_MILLIS.toFloat()..AppSettings.MAX_FILL_INTERVAL_MILLIS.toFloat(), steps = 8,
                    onValueChangeFinished = { onFillIntervalChange(value.fillIntervalMillis) })
                Text("较快 0.5 秒 — 较慢 5 秒，默认 1.5 秒。按此间隔逐题回填，页面响应较慢时会等待更久。修改对下一次回填生效，可随时停止。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StudyPanel {
                StudySectionTitle("数据与账号")
                TextButton(onClick = onGuide, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("使用指南") }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("清理作业缓存") }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("退出登录并清除登录态") }
                TextButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) { Text("关于与开源许可") }
            }
        }
    }
}
