package com.homeworkassistant

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import com.homeworkassistant.ui.AppViewModel
import com.homeworkassistant.ui.about.AboutScreen
import com.homeworkassistant.ui.detail.HomeworkDetailScreen
import com.homeworkassistant.ui.exams.ExamsScreen
import com.homeworkassistant.ui.home.HomeScreen
import com.homeworkassistant.ui.guide.GuideScreen
import com.homeworkassistant.ui.login.LoginScreen
import com.homeworkassistant.ui.settings.SettingsScreen
import com.homeworkassistant.ui.components.SyncResultPopup
import com.homeworkassistant.ui.theme.HomeworkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        setContent {
            val home by model.home.collectAsStateWithLifecycle()
            val guide by model.guide.collectAsStateWithLifecycle()
            val answers by model.answers.collectAsStateWithLifecycle()
            val exams by model.exams.collectAsStateWithLifecycle()
            val examMessage by model.examMessage.collectAsStateWithLifecycle()
            val examsRefreshing by model.examsRefreshing.collectAsStateWithLifecycle()
            val notice by model.notice.collectAsStateWithLifecycle()
            val syncNotice by model.syncNotice.collectAsStateWithLifecycle()
            val aiConnection by model.aiConnection.collectAsStateWithLifecycle()
            val login by model.login.collectAsStateWithLifecycle()
            var route by rememberSaveable { mutableStateOf("home") }
            var detailUrl by rememberSaveable { mutableStateOf("https://i.chaoxing.com") }
            var detailTitle by rememberSaveable { mutableStateOf("官方页面") }
            var detailHomeworkId by rememberSaveable { mutableStateOf<String?>(null) }
            var detailReturnRoute by rememberSaveable { mutableStateOf("home") }
            var allowAi by rememberSaveable { mutableStateOf(true) }
            val snackbar = remember { SnackbarHostState() }
            fun closeDetail() {
                if (detailReturnRoute == "home") model.reconcileHomeworkOnReturn(detailHomeworkId)
                model.resetAnswers(); model.cancelPageImport(); route = detailReturnRoute
            }
            LifecycleStartEffect(route, detailHomeworkId, home.accountId to guide.completed) {
                if (guide.completed && route == "detail" && allowAi) model.watchHomeworkStatus(detailHomeworkId)
                else if (guide.completed && route == "home") model.resumeHomeworkStatusCheck()
                onStopOrDispose { model.stopHomeworkStatusWatch() }
            }
            LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); model.clearNotice() } }
            LaunchedEffect(home.accountId, home.needsLogin) {
                if (home.accountId != null && !home.needsLogin && route == "login") route = "home"
            }
            LaunchedEffect(Unit) { model.loginCompleted.collect { route = "home" } }
            LaunchedEffect(home.accountId, route, home.initialized, guide.completed) {
                if (guide.completed && home.initialized && home.accountId != null && route in listOf("home", "exams")) { kotlinx.coroutines.delay(600); model.autoRefreshOnce() }
                else model.stopForegroundSync(invalidate = route != "detail")
            }
            BackHandler(enabled = route != "home") {
                if (route == "detail") closeDetail()
                else route = if (route in listOf("about", "guide")) "settings" else "home"
            }
            HomeworkTheme(home.settings.theme) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        when {
                            !home.initialized || !guide.initialized -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(); Text("正在初始化…", Modifier.padding(12.dp))
                                }
                            }
                            !guide.completed || route == "guide" -> GuideScreen(
                                onFinish = { if (route == "guide") route = "home" else model.completeGuide() },
                                replay = route == "guide", saving = guide.saving, error = guide.error)
                            route == "login" -> LoginScreen(model::verifyLogin, model::manualVerifyLogin, { route = "home" }, login)
                            route == "settings" -> SettingsScreen(home.settings, model::readKey, model::saveSettings,
                                model::testAi, model::clearCache, { model.logout(); route = "home" }, { route = "home" }, { route = "about" },
                                connection = aiConnection, onModels = model::fetchAiModels, onAiConfigChanged = model::aiConfigChanged,
                                onAppearanceChange = model::saveAppearance, onGuide = { route = "guide" }, onFillIntervalChange = model::saveFillInterval)
                            route == "about" -> AboutScreen { route = "settings" }
                            route == "exams" -> ExamsScreen(exams, home.refreshing || examsRefreshing, examMessage,
                                model::refresh, {
                                    model.resetAnswers(); detailHomeworkId = null; detailReturnRoute = "exams"
                                    allowAi = true; detailUrl = com.homeworkassistant.data.mcp.McpChaoxingParser.EXAM_LIST
                                    detailTitle = "官方考试列表"; route = "detail"
                                }, { route = "home" }, onOpen = { exam ->
                                    model.resetAnswers(); detailHomeworkId = null; detailReturnRoute = "exams"; allowAi = true
                                    detailUrl = exam.detailUrl?.takeIf { com.homeworkassistant.data.web.TrustedOriginPolicy().allows(it) }
                                        ?: com.homeworkassistant.data.mcp.McpChaoxingParser.EXAM_LIST
                                    detailTitle = exam.title; route = "detail"
                                })
                            route == "detail" -> HomeworkDetailScreen(detailUrl, detailTitle, answers,
                                ::closeDetail, { model.generate(it, true) }, model::fill, model::editAnswer, model::cancelAi,
                                model.pageImport.collectAsStateWithLifecycle().value, model::importCurrentPage, allowAi, model::cancelSync,
                                { web -> if (allowAi && detailReturnRoute == "home") {
                                    model.homeworkIdForUrl(web.url.orEmpty())?.let { detailHomeworkId = it }
                                    model.captureHomeworkDeadline(web)
                                    model.requestHomeworkStatusCheck()
                                } }, examMode = detailReturnRoute == "exams")
                            else -> HomeScreen(home, model::refresh, model::filter, { h ->
                                model.resetAnswers(); detailReturnRoute = "home"; allowAi = true; detailHomeworkId = h.id; detailUrl = h.detailUrl; detailTitle = h.title; route = "detail"
                            }, { route = "settings" }, { route = "login" }, {
                                if (home.accountId == null) route = "login" else {
                                    model.resetAnswers(); detailReturnRoute = "home"; allowAi = true; detailHomeworkId = null; detailUrl = "https://i.chaoxing.com"; detailTitle = "官方页面"; route = "detail"
                                }
                            }, onExams = { route = if (home.accountId == null) "login" else "exams" }, onCancelSync = model::cancelSync,
                                onRefreshCurrent = model::refreshCurrentCourse, onForceRefresh = model::forceRefresh, onResumeReads = model::resumeOfficialReads)
                        }
                        SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding())
                        SyncResultPopup(syncNotice, model::dismissSyncNotice)
                    }
                }
            }
        }
    }
    override fun onStop() {
        super.onStop()
        // Read-only synchronization is foreground-only; leaving the app cancels in-flight requests.
        model.stopForegroundSync()
        model.stopHomeworkStatusChecks()
    }
}
