package com.homeworkassistant

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homeworkassistant.data.local.SettingsDataStore
import com.homeworkassistant.data.notification.DeadlineAlarm
import com.homeworkassistant.data.notification.DeadlineNotifier
import com.homeworkassistant.di.AppModule
import com.homeworkassistant.domain.model.Course
import com.homeworkassistant.domain.model.Homework
import com.homeworkassistant.domain.model.HomeworkSnapshot
import com.homeworkassistant.domain.model.HomeworkStatus
import com.homeworkassistant.domain.model.SnapshotCoverage
import com.homeworkassistant.domain.usecase.DeadlineReminder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Device-only check: the heads-up notification and its channel actually reach the phone. */
@RunWith(AndroidJUnit4::class)
class DeadlineNotificationTest {
    @Test fun scheduledAlarmPostsFromCacheWithoutOpeningAnActivity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notifier = DeadlineNotifier(context)
        assumeTrue("POST_NOTIFICATIONS 未授予", notifier.canPost())
        val manager = context.getSystemService(NotificationManager::class.java)
        val settings = SettingsDataStore(context)
        val priorAccount = settings.accountId.first()
        val account = "deadline-alarm-device-test"
        val db = AppModule.database(context)
        val repo = AppModule.homework(db)
        val alarm = DeadlineAlarm(context, settings, repo, notifier)
        val now = Instant.now()
        val due = now.plusSeconds(settings.settings.first().reminderHours.coerceIn(1, 168) * 3600L + 7)
        val item = Homework("test", Course("99:99", "测试课程"), "后台闹钟测试作业", "https://i.chaoxing.com/",
            dueAt = due, status = HomeworkStatus.NOT_SUBMITTED)
        fun hasNotice() = manager.activeNotifications.any { it.id == DeadlineNotifier.NOTIFICATION_ID }
        try {
            notifier.clear()
            settings.setAccount(account)
            repo.publish(HomeworkSnapshot(account, 1, listOf(item), now, SnapshotCoverage.COMPLETE, 1, 1))
            alarm.check()
            assertFalse("进入提醒时间前不应发送通知", hasNotice())
            withTimeout(45_000) {
                while (!hasNotice()) delay(250)
            }
            notifier.clear()
            alarm.check()
            assertFalse("已发送的通知不应重复发送", hasNotice())
        } finally {
            notifier.clear()
            settings.setAccount(priorAccount)
            db.homeworkDao().deleteItems(account)
            alarm.check()
            db.close()
        }
    }

    @Test fun postsReminderAndCreatesChannel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notifier = DeadlineNotifier(context)
        assumeTrue("POST_NOTIFICATIONS 未授予", notifier.canPost())
        val now = Instant.now()
        val course = Course("1:1", "测试课程")
        fun homework(id: String, title: String, dueAt: Instant) = Homework(id, course, title,
            "https://i.chaoxing.com/", dueAt = dueAt, status = HomeworkStatus.NOT_SUBMITTED)
        notifier.post(DeadlineReminder(
            overdue = listOf(homework("test-overdue", "测试作业（逾期）", now.minusSeconds(3600))),
            upcoming = listOf(homework("test-soon", "测试作业（临近截止）", now.plusSeconds(3600))),
            hours = 24, partial = false))
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertTrue("截止提醒渠道应已创建",
                manager.notificationChannels.any { it.id == DeadlineNotifier.CHANNEL_ID })
        }
        assertTrue("应存在一条截止提醒通知", manager.activeNotifications.any {
            it.id == DeadlineNotifier.NOTIFICATION_ID && it.packageName == context.packageName
        })
    }
}
