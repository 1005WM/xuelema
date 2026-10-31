package com.homeworkassistant.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay

data class SyncNotice(val id: Long, val message: String, val warning: Boolean)

/** Every new result gets its own three-second lifetime, even when the text is identical. */
@Composable
fun SyncResultPopup(notice: SyncNotice?, onDismiss: (Long) -> Unit) {
    if (notice == null) return
    LaunchedEffect(notice.id) {
        delay(3_000)
        onDismiss(notice.id)
    }
    Popup(alignment = Alignment.Center, onDismissRequest = { onDismiss(notice.id) }) {
        Surface(Modifier.widthIn(max = 380.dp).fillMaxWidth(.9f).testTag("sync-result-popup")
            .semantics { liveRegion = LiveRegionMode.Polite },
            shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 8.dp) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (notice.warning) "同步提示" else "同步完成", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(notice.message, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium)
                }
                Text("3 秒后自动关闭", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
