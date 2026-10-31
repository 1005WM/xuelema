package com.homeworkassistant.di

import android.content.Context
import androidx.room.Room
import com.homeworkassistant.data.ai.AiRepositoryImpl
import com.homeworkassistant.data.local.*
import com.homeworkassistant.data.security.*
import com.homeworkassistant.data.session.*
import com.homeworkassistant.data.mcp.*
import com.homeworkassistant.domain.repository.*
import dagger.*
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.*

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class PlatformClient
@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun platformReadGate(@ApplicationContext context: Context) = PlatformReadGate(PersistentReadPauseStore(context))
    @Provides @Singleton fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "homework.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    @Provides @Singleton fun homework(db: AppDatabase): HomeworkRepository = HomeworkRepositoryImpl(db)
    @Provides @Singleton fun exams(db: AppDatabase): ExamRepository = ExamRepositoryImpl(db)
    @Provides @Singleton fun mcpTransport(impl: CookieMcpReadTransport): McpReadTransport = impl
    @Provides @Singleton fun courseReadCache(@ApplicationContext context: Context): CourseReadCache = PersistentCourseReadCache(context)
    @Provides @Singleton fun settings(impl: SettingsDataStore): SettingsRepository = impl
    @Provides @Singleton fun secrets(impl: KeystoreSecretStore): SecretStore = impl
    @Provides @Singleton fun ai(impl: AiRepositoryImpl): AiRepository = impl
    @Provides @Singleton @PlatformClient fun platformClient(cookies: CookieCoordinator): OkHttpClient =
        OkHttpClient.Builder().cookieJar(WebViewCookieJar(cookies))
            .addNetworkInterceptor(RawSetCookieInterceptor(cookies)).build()
}
