package com.homeworkassistant.data.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.homeworkassistant.R
import com.homeworkassistant.domain.model.Homework
import com.homeworkassistant.domain.usecase.DeadlineReminder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Posts a system notification for locally cached overdue or soon-due homework. */
@Singleton
class DeadlineNotifier @Inject constructor(@param:ApplicationContext private val context: Context) {
    fun canPost(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    fun post(reminder: DeadlineReminder, now: Instant = Instant.now()): Boolean {
        if (!canPost() || reminder.size == 0) return false
        createChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            manager?.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val overdueIds = reminder.overdue.mapTo(hashSetOf()) { it.id }
        val title = buildString {
            append("${reminder.size} 项作业需要留意")
            if (reminder.overdue.isNotEmpty()) append(" · ${reminder.overdue.size} 项已逾期")
        }
        val first = reminder.items.firstOrNull()
        val summary = buildString {
            if (first != null) append("最急：${first.title}")
            if (reminder.partial) append(if (first == null) "数据可能不完整" else " · 数据可能不完整")
        }.ifEmpty { "逾期 ${reminder.overdue.size} 项 · ${reminder.hours} 小时内 ${reminder.upcoming.size} 项" }
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        reminder.items.take(MAX_LINES).forEach { style.addLine(line(it, overdueIds)) }
        if (reminder.size > MAX_LINES) style.addLine("…另有 ${reminder.size - MAX_LINES} 项")
        style.setSummaryText("逾期 ${reminder.overdue.size} 项 · ${reminder.hours} 小时内 ${reminder.upcoming.size} 项")
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = launch?.let {
            PendingIntent.getActivity(context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_deadline_notification)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setWhen(now.toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) { false }
    }

    fun clear() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun line(homework: Homework, overdueIds: Set<String>): String {
        val whenText = homework.dueAt?.atZone(ZoneId.of("Asia/Shanghai"))?.format(FORMAT)
        val label = if (homework.id in overdueIds) "已逾期${whenText?.let { "（$it）" }.orEmpty()}"
        else "截止 ${whenText ?: "时间待确认"}"
        return "${homework.course.name} · ${homework.title} · $label"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "作业截止提醒", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "已同步的未完成作业临近截止或逾期时提醒" }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "deadline_reminder"
        const val NOTIFICATION_ID = 1001
        private const val MAX_LINES = 6
        private val FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    }
}
