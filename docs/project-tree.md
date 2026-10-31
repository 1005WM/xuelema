# 完整工程目录

排除本机 SDK、缓存、构建结果和 node_modules。

```text
HomeworkAssistant/
├── app
│   ├── schemas
│   │   └── com.homeworkassistant.data.local.AppDatabase
│   │       ├── 1.json
│   │       ├── 2.json
│   │       └── 3.json
│   ├── src
│   │   ├── androidTest
│   │   │   └── java
│   │   │       └── com
│   │   │           └── homeworkassistant
│   │   │               ├── AppearancePersistenceTest.kt
│   │   │               ├── CookieSynchronizationTest.kt
│   │   │               ├── ExamMigrationTest.kt
│   │   │               ├── HomeScreenTest.kt
│   │   │               ├── LoginOriginDialogTest.kt
│   │   │               ├── OfficialHomeworkFramesTest.kt
│   │   │               ├── OfficialQuestionFramesTest.kt
│   │   │               ├── SettingsModelSelectionTest.kt
│   │   │               ├── SnapshotTransactionTest.kt
│   │   │               ├── SyncResultPopupTest.kt
│   │   │               └── SyncViewportTest.kt
│   │   ├── main
│   │   │   ├── assets
│   │   │   │   └── scripts
│   │   │   │       ├── chaoxing_ai
│   │   │   │       │   ├── LICENSE
│   │   │   │       │   ├── manifest.json
│   │   │   │       │   ├── question-actions.js
│   │   │   │       │   ├── question-context.js
│   │   │   │       │   └── question-parser.js
│   │   │   │       ├── detect_session.js
│   │   │   │       ├── extract_homework.js
│   │   │   │       ├── frame_reader.js
│   │   │   │       ├── question_engine.js
│   │   │   │       └── question_text_adapter.js
│   │   │   ├── java
│   │   │   │   └── com
│   │   │   │       └── homeworkassistant
│   │   │   │           ├── data
│   │   │   │           │   ├── ai
│   │   │   │           │   │   ├── AiAnswerCodec.kt
│   │   │   │           │   │   ├── AiConnection.kt
│   │   │   │           │   │   ├── AiImageLoader.kt
│   │   │   │           │   │   ├── AiRepositoryImpl.kt
│   │   │   │           │   │   └── SseEventReader.kt
│   │   │   │           │   ├── local
│   │   │   │           │   │   ├── AppDatabase.kt
│   │   │   │           │   │   ├── ExamStorage.kt
│   │   │   │           │   │   └── SettingsDataStore.kt
│   │   │   │           │   ├── mcp
│   │   │   │           │   │   ├── CourseScanScope.kt
│   │   │   │           │   │   ├── McpChaoxingParser.kt
│   │   │   │           │   │   ├── McpChaoxingReader.kt
│   │   │   │           │   │   └── McpReadTransport.kt
│   │   │   │           │   ├── security
│   │   │   │           │   │   └── SecretStore.kt
│   │   │   │           │   ├── session
│   │   │   │           │   │   ├── CookieCoordinator.kt
│   │   │   │           │   │   └── PlatformReadGate.kt
│   │   │   │           │   ├── sync
│   │   │   │           │   │   ├── HomeworkSyncCoordinator.kt
│   │   │   │           │   │   └── HomeworkSyncEntries.kt
│   │   │   │           │   └── web
│   │   │   │           │       ├── HomeworkPageDto.kt
│   │   │   │           │       ├── HomeworkPageMerger.kt
│   │   │   │           │       ├── LoginNavigationPolicy.kt
│   │   │   │           │       ├── LoginSessionEvaluator.kt
│   │   │   │           │       ├── OfficialHomeworkReader.kt
│   │   │   │           │       ├── OfficialQuestionReader.kt
│   │   │   │           │       ├── PageOperation.kt
│   │   │   │           │       ├── TrustedOriginPolicy.kt
│   │   │   │           │       └── WebSessionController.kt
│   │   │   │           ├── di
│   │   │   │           │   └── AppModule.kt
│   │   │   │           ├── domain
│   │   │   │           │   ├── model
│   │   │   │           │   │   ├── AppModels.kt
│   │   │   │           │   │   ├── ExamModels.kt
│   │   │   │           │   │   └── OverviewModels.kt
│   │   │   │           │   ├── repository
│   │   │   │           │   │   └── Repositories.kt
│   │   │   │           │   └── usecase
│   │   │   │           │       ├── AggregateOverviewUseCase.kt
│   │   │   │           │       ├── BuildHomeContentUseCase.kt
│   │   │   │           │       └── MergePartialSnapshotUseCase.kt
│   │   │   │           ├── ui
│   │   │   │           │   ├── about
│   │   │   │           │   │   └── AboutScreen.kt
│   │   │   │           │   ├── components
│   │   │   │           │   │   ├── OfficialBrowserView.kt
│   │   │   │           │   │   ├── OfficialWebView.kt
│   │   │   │           │   │   ├── StatusPlaceholder.kt
│   │   │   │           │   │   ├── SyncResultPopup.kt
│   │   │   │           │   │   └── SyncWebViewHost.kt
│   │   │   │           │   ├── detail
│   │   │   │           │   │   ├── AiAnswerBottomSheet.kt
│   │   │   │           │   │   └── HomeworkDetailScreen.kt
│   │   │   │           │   ├── exams
│   │   │   │           │   │   └── ExamsScreen.kt
│   │   │   │           │   ├── home
│   │   │   │           │   │   ├── HomeScreen.kt
│   │   │   │           │   │   ├── HomeUiState.kt
│   │   │   │           │   │   ├── HomeworkList.kt
│   │   │   │           │   │   └── OverviewHeader.kt
│   │   │   │           │   ├── login
│   │   │   │           │   │   └── LoginScreen.kt
│   │   │   │           │   ├── preview
│   │   │   │           │   │   └── PreviewData.kt
│   │   │   │           │   ├── settings
│   │   │   │           │   │   └── SettingsScreen.kt
│   │   │   │           │   ├── theme
│   │   │   │           │   │   └── Theme.kt
│   │   │   │           │   └── AppViewModel.kt
│   │   │   │           ├── HomeworkApplication.kt
│   │   │   │           └── MainActivity.kt
│   │   │   ├── res
│   │   │   │   ├── drawable
│   │   │   │   │   ├── ic_launcher_foreground.xml
│   │   │   │   │   └── ic_launcher_monochrome.xml
│   │   │   │   ├── drawable-nodpi
│   │   │   │   │   └── launcher_artwork.jpg
│   │   │   │   ├── mipmap-anydpi
│   │   │   │   │   ├── ic_launcher.xml
│   │   │   │   │   └── ic_launcher_round.xml
│   │   │   │   ├── raw
│   │   │   │   │   └── third_party_notices.txt
│   │   │   │   ├── values
│   │   │   │   │   ├── colors.xml
│   │   │   │   │   ├── ids.xml
│   │   │   │   │   ├── strings.xml
│   │   │   │   │   └── themes.xml
│   │   │   │   └── xml
│   │   │   │       ├── backup_rules.xml
│   │   │   │       ├── data_extraction_rules.xml
│   │   │   │       └── network_security_config.xml
│   │   │   └── AndroidManifest.xml
│   │   └── test
│   │       ├── java
│   │       │   └── com
│   │       │       └── homeworkassistant
│   │       │           ├── data
│   │       │           │   ├── AiConnectionTest.kt
│   │       │           │   ├── AiQuestionIntegrationTest.kt
│   │       │           │   ├── CourseScanScopeTest.kt
│   │       │           │   ├── HomeworkMapperTest.kt
│   │       │           │   ├── HomeworkPageMergerTest.kt
│   │       │           │   ├── HomeworkPageReadTest.kt
│   │       │           │   ├── HomeworkSyncEntriesTest.kt
│   │       │           │   ├── LoginNavigationPolicyTest.kt
│   │       │           │   ├── LoginSessionEvaluatorTest.kt
│   │       │           │   ├── McpChaoxingParserTest.kt
│   │       │           │   ├── McpChaoxingReaderTest.kt
│   │       │           │   ├── McpReadTransportTest.kt
│   │       │           │   ├── McpSyncCoordinatorTest.kt
│   │       │           │   ├── OriginPolicyTest.kt
│   │       │           │   ├── PageOperationTest.kt
│   │       │           │   └── SseEventReaderTest.kt
│   │       │           └── domain
│   │       │               ├── AggregateOverviewUseCaseTest.kt
│   │       │               ├── BuildHomeContentUseCaseTest.kt
│   │       │               └── MergePartialSnapshotUseCaseTest.kt
│   │       └── resources
│   │           └── fixtures
│   │               ├── homework_data_links.html
│   │               ├── homework_empty.html
│   │               ├── homework_stu_work.html
│   │               ├── homework_supported.html
│   │               ├── homework_unknown.html
│   │               ├── mcp_course_lifecycle.html
│   │               ├── mcp_courses.html
│   │               ├── mcp_exams.html
│   │               └── mcp_homework.html
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── docs
│   ├── aggregate-fixes.md
│   ├── ai-model-selection.md
│   ├── chaoxing-ai-integration.md
│   ├── course-scope-fixes.md
│   ├── detail-speed-fixes.md
│   ├── login-fixes.md
│   ├── mcp-integration.md
│   ├── parser-compatibility.md
│   ├── pending-fixes.md
│   ├── project-tree.md
│   ├── refresh-appearance-fixes.md
│   ├── sync-fixes.md
│   ├── sync-popup-fixes.md
│   ├── text-answer-fill-fixes.md
│   └── verification.md
├── gradle
│   ├── wrapper
│   │   ├── gradle-wrapper.jar
│   │   └── gradle-wrapper.properties
│   └── libs.versions.toml
├── tools
│   ├── js
│   │   ├── load_question_engine.cjs
│   │   ├── package-lock.json
│   │   ├── package.json
│   │   ├── verify.cjs
│   │   ├── verify_ai_questions.cjs
│   │   ├── verify_frames.cjs
│   │   ├── verify_pending.cjs
│   │   ├── verify_stu_work.cjs
│   │   └── verify_text_fill.cjs
│   ├── bootstrap.py
│   ├── maven_proxy.py
│   ├── package_source.py
│   ├── verify_domain.py
│   └── verify_migration.py
├── .gitignore
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── README.md
└── settings.gradle.kts
```
