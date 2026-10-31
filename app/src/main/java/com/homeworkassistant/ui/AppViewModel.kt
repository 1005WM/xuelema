package com.homeworkassistant.ui

import android.content.Context
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.homeworkassistant.data.security.SecretStore
import com.homeworkassistant.data.ai.AiConnection
import com.homeworkassistant.data.session.CookieCoordinator
import com.homeworkassistant.data.sync.HomeworkSyncCoordinator
import com.homeworkassistant.data.sync.PendingHomeworkDetails
import com.homeworkassistant.data.sync.PendingExamSync
import com.homeworkassistant.data.web.*
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.domain.repository.*
import com.homeworkassistant.domain.usecase.BuildHomeContentUseCase
import com.homeworkassistant.ui.home.HomeUiState
import com.homeworkassistant.ui.components.SyncNotice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.*
import javax.inject.Inject

private data class RefreshState(val busy: Boolean = false, val error: String? = null,
    val needsLogin: Boolean = false, val fresh: Boolean = false, val progress: String = "",
    val detailsBusy: Boolean = false, val examMessage: String? = null, val examsBusy: Boolean = false)
data class AnswerUiState(val busy: Boolean = false, val page: QuestionPage? = null,
    val answers: List<Answer> = emptyList(), val message: String = "", val error: String? = null,
    val fillFeedback: Map<String, String> = emptyMap())
data class LoginUiState(val busy: Boolean = false, val message: String = "请在官方网页完成登录")
data class AiConnectionUiState(val busy: Boolean = false, val models: List<String> = emptyList(),
    val message: String? = null, val failed: Boolean = false)
data class PageImportUiState(val busy: Boolean = false, val message: String = "点击同步，获取未结课课程的作业与考试",
    val frames: List<String> = emptyList())
data class GuideUiState(val initialized: Boolean = false, val completed: Boolean = false,
    val saving: Boolean = false, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val homework: HomeworkRepository,
    private val examRepository: ExamRepository,
    private val settingsRepository: SettingsRepository,
    private val secrets: SecretStore,
    private val cookies: CookieCoordinator,
    private val sync: HomeworkSyncCoordinator,
    private val ai: AiRepository,
    val webHost: WebSessionController,
) : ViewModel() {
    private val filter = MutableStateFlow(HomeFilter())
    private val guideSave = MutableStateFlow(GuideUiState())
    val guide = combine(settingsRepository.guideCompleted, guideSave) { completed, save ->
        save.copy(initialized = true, completed = completed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GuideUiState())
    private val keyPresent = MutableStateFlow<Boolean?>(null)
    private val displaySettings = combine(settingsRepository.settings, keyPresent) { settings, key ->
        settings to key?.let { it && settings.baseUrl.isNotBlank() && settings.model.isNotBlank() }
    }
    init { viewModelScope.launch {
        keyPresent.value = withContext(Dispatchers.IO) { runCatching { secrets.read().isNotBlank() }.getOrDefault(false) }
    } }
    private val refreshing = MutableStateFlow(RefreshState())
    val examMessage = refreshing.map { it.examMessage }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val examsRefreshing = refreshing.map { it.examsBusy }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val clock = flow { while (true) { emit(Instant.now()); delay(15000) } }
    private val snapshot = settingsRepository.accountId.flatMapLatest { id ->
        if (id == null) flowOf(Triple<String?, HomeworkSnapshot?, Map<String, CourseCacheInfo>>(null, null, emptyMap()))
        else combine(homework.observe(id), homework.observeCourseCache(id)) { snapshot, cache -> Triple(id, snapshot, cache) }
    }
    val exams = settingsRepository.accountId.flatMapLatest { id ->
        if (id == null) flowOf(null) else examRepository.observe(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val home = combine(snapshot, displaySettings, filter, refreshing, clock) { pair, configured, f, r, now ->
        HomeUiState(true, pair.first, pair.second,
            pair.second?.let { BuildHomeContentUseCase()(it, f, now, ZoneId.of("Asia/Shanghai")) },
            configured.first, f, r.busy, r.error, r.needsLogin, !r.fresh, r.progress, r.detailsBusy, configured.second,
            pair.third, f.courseId ?: sync.preferredCourseId(pair.first))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())
    private val _answers = MutableStateFlow(AnswerUiState())
    val answers = _answers.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private val _syncNotice = MutableStateFlow<SyncNotice?>(null)
    val syncNotice = _syncNotice.asStateFlow()
    private var syncNoticeId = 0L
    private val _login = MutableStateFlow(LoginUiState())
    val login = _login.asStateFlow()
    private val _aiConnection = MutableStateFlow(AiConnectionUiState())
    val aiConnection = _aiConnection.asStateFlow()
    private val settingsGate = Mutex()
    private var connectionJob: Job? = null
    private var connectionGeneration = 0L
    private val _loginCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loginCompleted = _loginCompleted.asSharedFlow()
    private val _pageImport = MutableStateFlow(PageImportUiState())
    val pageImport = _pageImport.asStateFlow()
    private var importJob: Job? = null
    private var autoRefreshedAccount: String? = null
    private var lastAutoRefreshNanos = 0L
    private var refreshJob: Job? = null
    private var detailsJob: Job? = null
    private var examsJob: Job? = null
    private var aiJob: Job? = null
    private var statusWatchJob: Job? = null
    private var statusReturnJob: Job? = null
    private var watchedHomework: String? = null
    private var pendingHomeworkStatus: Pair<String, String>? = null
    private val statusSignals = Channel<Unit>(Channel.CONFLATED)
    private var verifying = false
    private var loginJob: Job? = null
    fun filter(value: HomeFilter) { filter.value = value }
    fun completeGuide() {
        if (guideSave.value.saving) return
        guideSave.value = GuideUiState(saving = true)
        viewModelScope.launch {
            try { settingsRepository.completeGuide() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { guideSave.value = GuideUiState(error = "暂时无法保存引导记录，请重试") }
            finally { guideSave.update { it.copy(saving = false) } }
        }
    }
    fun clearNotice() { _notice.value = null }
    fun dismissSyncNotice(id: Long) { _syncNotice.update { if (it?.id == id) null else it } }
    private fun showSyncNotice(message: String, warning: Boolean = false) {
        _syncNotice.value = SyncNotice(++syncNoticeId, message, warning)
    }
    fun autoRefreshOnce() {
        val id = home.value.accountId ?: return
        if (autoRefreshedAccount == id && System.nanoTime() - lastAutoRefreshNanos < 1_500_000_000L) return
        autoRefreshedAccount = id
        lastAutoRefreshNanos = System.nanoTime()
        refresh()
    }
    fun stopForegroundSync(invalidate: Boolean = true) {
        refreshJob?.cancel()
        importJob?.cancel()
        detailsJob?.cancel()
        examsJob?.cancel()
        refreshing.update { it.copy(examsBusy = false) }
        if (invalidate) sync.invalidate()
    }
    fun homeworkIdForUrl(url: String): String? {
        if (!TrustedOriginPolicy().allows(url)) return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        fun parameter(vararg names: String) = parsed.queryParameterNames.firstOrNull { key -> names.any { it.equals(key, true) } }
            ?.let(parsed::queryParameter)
        val workId = parameter("workId", "taskrefId") ?: return null
        val courseId = parameter("courseId")
        val classId = parameter("classId", "clazzId")
        return home.value.snapshot?.items?.filter { item ->
            item.id.substringAfterLast(':') == workId &&
                (courseId == null || item.course.id.substringBefore(':') == courseId) &&
                (classId == null || item.course.id.substringAfter(':') == classId)
        }?.singleOrNull()?.id
    }

    fun captureHomeworkDeadline(web: WebView) {
        val account = home.value.accountId ?: return
        val id = homeworkIdForUrl(web.url.orEmpty()) ?: return
        val item = home.value.snapshot?.items?.firstOrNull { it.id == id } ?: return
        val initialUrl = web.url
        viewModelScope.launch {
            try {
                // Page-finished can precede AJAX metadata. No extra network request is made here.
                for (wait in listOf(0L, 600L, 1_500L)) {
                    delay(wait)
                    if (home.value.accountId != account || web.url != initialUrl) return@launch
                    val update = RenderedHomeworkMetadataReader().read(web, item) ?: continue
                    if (home.value.accountId == account && web.url == initialUrl) homework.updateDetails(account, listOf(update))
                    return@launch
                }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { /* Optional metadata never blocks the official task or status checks. */ }
        }
    }
    fun watchHomeworkStatus(homeworkId: String?) {
        if (homeworkId == null) return
        if (watchedHomework == homeworkId && statusWatchJob?.isActive == true) return
        stopHomeworkStatusWatch()
        statusReturnJob?.cancel()
        val account = home.value.accountId ?: return
        watchedHomework = homeworkId
        sync.preferCourse(account, homeworkId.substringBeforeLast(':'))
        pendingHomeworkStatus = account to homeworkId
        statusWatchJob = viewModelScope.launch {
            while (isActive && home.value.accountId == account) {
                if (!checkHomeworkStatus(account, homeworkId) && (refreshing.value.needsLogin || refreshing.value.error?.contains("暂停") == true)) break
                withTimeoutOrNull(5_000) { statusSignals.receive() }
            }
        }
    }
    fun requestHomeworkStatusCheck() { statusSignals.trySend(Unit) }
    fun stopHomeworkStatusWatch() { statusWatchJob?.cancel(); watchedHomework = null }
    fun stopHomeworkStatusChecks() { stopHomeworkStatusWatch(); statusReturnJob?.cancel() }
    fun resumeHomeworkStatusCheck() {
        if (statusReturnJob?.isActive == true) return
        val pending = pendingHomeworkStatus ?: return
        if (pending.first == home.value.accountId) reconcileHomeworkOnReturn(pending.second)
    }
    fun reconcileHomeworkOnReturn(homeworkId: String?) {
        if (homeworkId == null) return
        val account = home.value.accountId ?: return
        val pending = account to homeworkId
        pendingHomeworkStatus = pending
        val previousWatch = statusWatchJob
        stopHomeworkStatusWatch()
        statusReturnJob?.cancel()
        statusReturnJob = viewModelScope.launch {
            previousWatch?.join()
            var succeeded = false
            repeat(3) { attempt ->
                if (home.value.accountId != account) return@launch
                succeeded = checkHomeworkStatus(account, homeworkId)
                if (refreshing.value.needsLogin) return@launch
                if (succeeded && homework.observe(account).first()?.items
                        ?.firstOrNull { it.id == homeworkId }?.status?.isCompleted == true) {
                    if (pendingHomeworkStatus == pending) pendingHomeworkStatus = null
                    return@launch
                }
                if (attempt < 2) delay(1_500L * (attempt + 1))
            }
            if (!succeeded) _notice.value = "暂未核对到最新作业状态，请检查网络或官方登录状态；现有数据已保留"
            else if (pendingHomeworkStatus == pending) pendingHomeworkStatus = null
        }
    }
    private suspend fun checkHomeworkStatus(account: String, homeworkId: String): Boolean = try {
        sync.checkHomeworkStatus(account, homeworkId)
        true
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        false
    }
      catch (e: SessionExpiredException) {
        refreshing.update { it.copy(needsLogin = true, error = e.message) }
        false
    } catch (e: com.homeworkassistant.data.session.OfficialReadPausedException) {
        refreshing.update { it.copy(error = e.message) }; false
    } catch (_: Exception) { false }
    fun cancelSync() {
        stopForegroundSync()
        refreshing.update { it.copy(busy = false, detailsBusy = false, progress = "") }
        _pageImport.update { it.copy(busy = false, message = "同步已停止，现有数据已保留") }
        _notice.value = "同步已停止，现有数据已保留"
    }
    fun refresh() = refreshCourses(false, null)
    fun forceRefresh() = refreshCourses(true, null)
    fun refreshCurrentCourse() {
        val id = filter.value.courseId ?: sync.preferredCourseId(home.value.accountId)
        if (id == null) { _notice.value = "请先筛选或打开一门课程的作业"; return }
        refreshCourses(true, id)
    }
    fun resumeOfficialReads() { sync.resumeAfterVerification(); refresh() }
    private fun refreshCourses(force: Boolean, courseId: String?) {
        val id = home.value.accountId ?: return
        if (refreshJob?.isActive == true || importJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            detailsJob?.cancelAndJoin()
            examsJob?.cancelAndJoin()
            _syncNotice.value = null
            refreshing.value = refreshing.value.copy(busy = true, detailsBusy = false, progress = "", error = null, examMessage = null)
            try {
                val result = sync.sync(id, onHomeworkPublished = { refreshing.update { it.copy(fresh = true) } }, includeDetails = false, includeExams = false,
                    force = force, targetCourseId = courseId) {
                    progress -> refreshing.update { it.copy(progress = progress) }
                }
                refreshing.value = RefreshState(fresh = result.networkUpdated,
                    error = result.message.takeIf { result.partial }, examMessage = result.examMessage)
                showSyncNotice(result.message ?: "作业与考试已同步", result.partial)
                cookies.flush()
                startDetails(result.pendingDetails)
                startExams(result.pendingExams, force)
            } catch (e: TimeoutCancellationException) {
                val message = "同步超时，原缓存已保留。请检查官方登录后重试"
                refreshing.value = RefreshState(error = message, examMessage = message)
                showSyncNotice(message, true)
            }
              catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                val message = e.message ?: "同步失败，请重试"
                refreshing.value = RefreshState(error = message, needsLogin = e is SessionExpiredException, examMessage = message)
                showSyncNotice(message, true)
            }
            finally { refreshing.value = refreshing.value.copy(busy = false) }
        }
    }
    private fun startDetails(pending: PendingHomeworkDetails?) {
        if (pending == null) return
        detailsJob = viewModelScope.launch {
            refreshing.update { it.copy(detailsBusy = true, progress = "正在后台补充截止时间与得分…") }
            try {
                sync.enrichDetails(pending) { progress -> refreshing.update { it.copy(progress = progress) } }
            } catch (e: CancellationException) { throw e }
              catch (e: SessionExpiredException) {
                refreshing.update { it.copy(needsLogin = true, error = e.message) }
            } catch (e: com.homeworkassistant.data.session.OfficialReadPausedException) {
                refreshing.update { it.copy(error = e.message) }
            } catch (_: Exception) {
                // Optional metadata must not turn a successful list refresh into an error.
            } finally { refreshing.update { it.copy(detailsBusy = false, progress = "") } }
        }
    }
    private fun startExams(pending: PendingExamSync?, force: Boolean = false) {
        if (pending == null) return
        if (!force && ReadCacheDefaults.fresh(exams.value?.fetchedAt, Instant.now(), ReadCacheDefaults.HOMEWORK_MILLIS)) return
        examsJob = viewModelScope.launch {
            refreshing.update { it.copy(examsBusy = true, examMessage = null) }
            try {
                sync.syncExams(pending)?.let { result ->
                    refreshing.update { it.copy(examMessage = result.message.takeIf { result.partial }) }
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                refreshing.update { it.copy(examMessage = "考试读取未完成：${e.message ?: "请重试，原缓存保留"}",
                    needsLogin = it.needsLogin || e is SessionExpiredException) }
            } finally { refreshing.update { it.copy(examsBusy = false) } }
        }
    }
    fun verifyLogin(web: WebView) = verifyLoginInternal(web, false)
    fun manualVerifyLogin(web: WebView) = verifyLoginInternal(web, true)
    private fun verifyLoginInternal(web: WebView, showFailure: Boolean) {
        if (verifying) return
        verifying = true
        loginJob = viewModelScope.launch {
            try {
                val url = web.url.orEmpty()
                val policy = TrustedOriginPolicy()
                if (!policy.allows(url)) {
                    if (showFailure) _login.value = LoginUiState(message = "请先在学校认证页完成登录并返回学习通，再验证登录状态")
                    return@launch
                }
                if (showFailure) _login.value = LoginUiState(true, "正在验证登录状态…")
                val script = script("detect_session.js")
                val json = Gson().fromJson(web.evaluate(script), String::class.java) ?: return@launch
                val result = Gson().fromJson(json, SessionDto::class.java)
                if (web.url != url) return@launch
                val header = cookies.header(url) + ";" + cookies.header("https://i.chaoxing.com/")
                val uid = LoginSessionEvaluator().authenticatedUid(url, result, header)
                if (uid == null) {
                    if (showFailure && policy.isLogin(url)) {
                        _login.value = LoginUiState(message = "正在打开个人主页验证；若仍显示登录页，请先完成官方登录")
                        web.loadUrl("https://i.chaoxing.com/")
                    } else if (showFailure) _login.value = LoginUiState(message = "尚未确认账号身份。请等待个人主页加载完成，再点击验证；也可点击网页上方的刷新")
                    return@launch
                }
                val id = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
                webHost.useUserAgent(web.settings.userAgentString)
                cookies.flush()
                sync.invalidate()
                refreshJob?.cancelAndJoin(); importJob?.cancelAndJoin(); detailsJob?.cancelAndJoin(); examsJob?.cancelAndJoin()
                settingsRepository.setAccount(id)
                sync.resumeAfterVerification()
                autoRefreshedAccount = null
                refreshing.value = RefreshState()
                _login.value = LoginUiState(message = "登录成功，正在进入首页…")
                _loginCompleted.emit(Unit)
                _notice.value = "登录验证成功"
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { if (showFailure) _login.value = LoginUiState(message = e.message ?: "登录验证失败，请刷新网页后重试") }
            finally { verifying = false; _login.update { it.copy(busy = false) } }
        }
    }
    fun logout() { viewModelScope.launch {
        stopHomeworkStatusChecks()
        pendingHomeworkStatus = null
        try {
            sync.invalidate(); sync.clearReadCache()
            refreshJob?.cancelAndJoin(); aiJob?.cancelAndJoin()
            detailsJob?.cancelAndJoin()
            examsJob?.cancelAndJoin()
            loginJob?.cancelAndJoin()
            importJob?.cancelAndJoin()
            val fullClear = webHost.clearStorage()
            cookies.clear(); homework.clear(); examRepository.clear()
            settingsRepository.setAccount(null)
            refreshing.value = RefreshState()
            filter.value = HomeFilter(); _answers.value = AnswerUiState()
            _syncNotice.value = null
            _login.value = LoginUiState()
            aiConfigChanged()
            _pageImport.value = PageImportUiState(); autoRefreshedAccount = null
            if (!fullClear) _notice.value = "已退出登录。当前 WebView 不支持完整站点存储清理，建议更新 Android System WebView"
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) { _notice.value = e.message ?: "退出清理失败，请重试" }
    } }
    fun clearCache() { viewModelScope.launch {
        stopHomeworkStatusChecks()
        pendingHomeworkStatus = null
        sync.invalidate(); sync.clearReadCache(); refreshJob?.cancelAndJoin(); importJob?.cancelAndJoin(); detailsJob?.cancelAndJoin(); examsJob?.cancelAndJoin(); homework.clear(); examRepository.clear()
        refreshing.value = RefreshState(); _syncNotice.value = null; _notice.value = "作业与考试缓存已清理"
    } }
    suspend fun readKey(): String = withContext(Dispatchers.IO) { secrets.read() }
    fun saveFillInterval(intervalMillis: Int) {
        viewModelScope.launch {
            try { settingsGate.withLock { settingsRepository.saveFillInterval(intervalMillis) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _notice.value = "回填速度未能保存，请重试" }
        }
    }
    fun saveAppearance(value: AppSettings) {
        viewModelScope.launch {
            try {
                settingsGate.withLock { settingsRepository.saveAppearance(value.theme, value.compact, value.reminderHours) }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { _notice.value = "外观设置保存失败，请重试" }
        }
    }
    private suspend fun persistSettings(settings: AppSettings, key: String) = settingsGate.withLock {
        val normalized = settings.copy(baseUrl = if (settings.baseUrl.isBlank()) "" else AiConnection.baseUrl(settings.baseUrl).toString(),
            model = settings.model.trim())
        val normalizedKey = if (key.isBlank()) "" else AiConnection.key(key)
        withContext(Dispatchers.IO) { secrets.write(normalizedKey) }
        settingsRepository.save(normalized)
        keyPresent.value = normalizedKey.isNotBlank()
    }
    fun saveSettings(settings: AppSettings, key: String) { viewModelScope.launch {
        try {
            persistSettings(settings, key)
            _notice.value = "设置已保存"
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) { _notice.value = e.message ?: "保存失败" }
    } }
    fun aiConfigChanged() {
        connectionGeneration++; connectionJob?.cancel()
        _aiConnection.value = AiConnectionUiState()
    }
    fun fetchAiModels(settings: AppSettings, key: String) = connectAi(settings, key, true)
    fun testAi(settings: AppSettings, key: String) = connectAi(settings, key, false)
    private fun connectAi(settings: AppSettings, key: String, fetchModels: Boolean) {
        aiConfigChanged()
        val generation = connectionGeneration
        _aiConnection.value = AiConnectionUiState(busy = true,
            message = if (fetchModels) "正在保存配置并获取模型列表…" else "正在保存配置并验证连接…")
        connectionJob = viewModelScope.launch {
            try {
                AiConnection.key(key) // Do not clear an existing saved key when testing an empty field.
                persistSettings(settings, key)
                if (fetchModels) {
                    val models = ai.models(settings)
                    if (generation == connectionGeneration) _aiConnection.value = AiConnectionUiState(models = models,
                        message = "已获取 ${models.size} 个模型，点击模型栏选择；选择后自动保存")
                } else {
                    val message = ai.test(settings)
                    if (generation == connectionGeneration) _aiConnection.value = AiConnectionUiState(message = message)
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                if (generation == connectionGeneration) _aiConnection.value = AiConnectionUiState(failed = true,
                    message = (e.message ?: "连接失败") + if (fetchModels) "。若服务不支持模型列表，可手动填写模型后保存并测试" else "")
            } finally {
                if (generation == connectionGeneration) _aiConnection.update { it.copy(busy = false) }
            }
        }
    }
    private suspend fun script(name: String) = withContext(Dispatchers.IO) {
        context.assets.open("scripts/$name").bufferedReader().use { it.readText() }
    }
    fun cancelPageImport() { importJob?.cancel(); _pageImport.update { it.copy(busy = false) } }
    fun importCurrentPage(web: WebView) {
        if (importJob?.isActive == true || refreshJob?.isActive == true) return
        val account = home.value.accountId ?: return
        if (!TrustedOriginPolicy().allows(web.url.orEmpty())) {
            showSyncNotice("请先返回官方页面完成登录", true)
            return
        }
        importJob = viewModelScope.launch {
            detailsJob?.cancelAndJoin()
            examsJob?.cancelAndJoin()
            _syncNotice.value = null
            _pageImport.value = PageImportUiState(busy = true, message = "正在使用 MCP 读取流程同步…")
            try {
                val result = sync.sync(account, onHomeworkPublished = { refreshing.update { it.copy(fresh = true) } }, includeDetails = false, includeExams = false) { progress ->
                    _pageImport.update { it.copy(message = progress) }
                }
                require(home.value.accountId == account) { "账号已切换，本次读取已取消" }
                refreshing.value = RefreshState(fresh = result.networkUpdated, error = result.message.takeIf { result.partial }, examMessage = result.examMessage)
                _pageImport.value = PageImportUiState()
                showSyncNotice(result.message ?: "作业与考试已同步", result.partial)
                cookies.flush()
                startDetails(result.pendingDetails)
                startExams(result.pendingExams)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                _pageImport.value = PageImportUiState()
                showSyncNotice(e.message ?: "MCP 读取失败，原缓存保留", true)
                refreshing.value = RefreshState(needsLogin = e is SessionExpiredException, error = e.message,
                    examMessage = e.message ?: "考试读取失败，原缓存保留")
            } finally { _pageImport.update { it.copy(busy = false) } }
        }
    }
    fun resetAnswers() { aiJob?.cancel(); _answers.value = AnswerUiState() }
    fun cancelAi() { aiJob?.cancel(); _answers.update { it.copy(busy = false, message = "已停止，已完成答案保留") } }
    fun generate(web: WebView, onlyMissing: Boolean = false) {
        if (aiJob?.isActive == true) return
        val preceding = aiJob
        aiJob = viewModelScope.launch {
            preceding?.join()
            val previous = _answers.value
            _answers.value = previous.copy(busy = true, error = null, message = "正在提取题目")
            try {
                require(TrustedOriginPolicy().allows(web.url.orEmpty())) { "当前不是受支持的官方页面" }
                val page = OfficialQuestionReader.read(web)
                require(page.questions.isNotEmpty()) { "当前页面未识别到题目。可在官方题目所在页面重试" }
                require(page.questions.map { it.id }.distinct().size == page.questions.size) { "页面题目标识重复，无法可靠匹配答案，请使用官方页面" }
                val retained = if (onlyMissing && previous.page?.fingerprint == page.fingerprint && previous.page?.url == page.url) previous.answers else emptyList()
                _answers.value = AnswerUiState(true, page, retained, "已识别 ${page.questions.size} 个作答区域 · ${page.frames.size} 个页面",
                    page.warnings.takeIf { it.isNotEmpty() }?.joinToString("\n"))
                val questions = page.questions.filter { q -> retained.none { it.questionId == q.id } }
                if (questions.isNotEmpty()) ai.generate(questions, home.value.settings).collect { event ->
                    when (event) {
                        is AiEvent.Batch -> _answers.update { it.copy(answers = (it.answers + event.answers).distinctBy(Answer::questionId)) }
                        is AiEvent.Progress -> _answers.update { it.copy(message = event.text) }
                        is AiEvent.Warning -> _answers.update { it.copy(error = listOfNotNull(it.error, event.message).joinToString("\n")) }
                    }
                }
                _answers.update { it.copy(busy = false, message = "已识别 ${page.questions.size} 个作答区域，已生成 ${it.answers.size} 份答案。请逐题核对，回填后手动保存或提交") }
            } catch (e: CancellationException) { _answers.update { it.copy(busy = false) }; throw e }
              catch (e: Exception) { _answers.update { it.copy(busy = false, error = e.message ?: "AI 作答失败") } }
        }
    }
    fun editAnswer(id: String, value: String) { _answers.update { state ->
        if (state.busy) state else state.copy(answers = state.answers.map { if (it.questionId == id) it.copy(answer = value) else it }, fillFeedback = state.fillFeedback - id)
    } }
    fun fill(web: WebView) {
        if (aiJob?.isActive == true) return
        val preceding = aiJob
        aiJob = viewModelScope.launch {
        preceding?.join()
        try {
            val state = _answers.value
            val page = state.page ?: return@launch
            require(!state.busy && state.answers.isNotEmpty())
            require(TrustedOriginPolicy().allows(web.url.orEmpty())) { "当前页面不受信任" }
            _answers.update { it.copy(busy = true, error = null, fillFeedback = emptyMap(), message = "正在回填（每题间隔 ${home.value.settings.fillIntervalMillis / 1000f} 秒），请勿切换页面或编辑网页答案…") }
            val result = OfficialQuestionReader.fillDetailed(web, page, state.answers, home.value.settings.fillIntervalMillis)
            _answers.update { it.copy(message = "回填结果：${result.message()}\n请检查官方页面并手动保存或提交", fillFeedback = result.feedback.orEmpty()) }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            _answers.update { it.copy(error = "回填超时，已停止后续操作；请检查页面中已填写的部分") }
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) { _answers.update { it.copy(error = e.message ?: "无法回填，请复制答案") } }
        finally { _answers.update { it.copy(busy = false) } }
    } }
}
