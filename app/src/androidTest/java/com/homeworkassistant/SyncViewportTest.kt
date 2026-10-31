package com.homeworkassistant

import android.webkit.WebView
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.homeworkassistant.data.web.WebSessionController
import com.homeworkassistant.ui.components.SyncWebViewHost
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SyncViewportTest {
    @get:Rule val compose = createComposeRule()
    @Test fun tinyContainerDoesNotCollapseDocumentViewport() {
        var host: SyncWebViewHost? = null
        compose.setContent {
            AndroidView(modifier = Modifier.size(1.dp), factory = { context ->
                SyncWebViewHost(context, WebSessionController()).also { host = it }
            }, onRelease = { it.release() })
        }
        compose.runOnIdle {
            val container = requireNotNull(host)
            val web = container.getChildAt(0) as WebView
            assertTrue(web.measuredWidth > 100)
            assertTrue(web.measuredHeight > 100)
            assertTrue(web.measuredWidth > container.measuredWidth * 10)
        }
    }
}
