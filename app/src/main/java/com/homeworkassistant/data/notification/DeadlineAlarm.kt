package com.homeworkassistant.data.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.homeworkassistant.domain.repository.HomeworkRepository
import com.homeworkassistant.domain.repository.SettingsRepository
import com.homeworkassistant.domain.usecase.CollectDeadlineRemindersUseCase
import com.homeworkassistant.domain.usecase.planDeadlineAlarm
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeadlineAlarm @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val homework: HomeworkRepository,
    private val notifier: DeadlineNotifier,
) {
    private val lock = Mutex()
    private val delivered = context.getSharedPreferences("homework-deadline-notices", Context.MODE_PRIVATE)
    private val manager get() = context.getSystemService(AlarmManager::class.java)
    private fun request() = PendingIntent.getBroadcast(context, 0,
        Intent(context, DeadlineAlarmReceiver::class.java).setAction(DeadlineAlarmReceiver.REMIND),
        PendingIntent.FLAG_IMMUTABLE)

    suspend fun check() = withContext(Dispatchers.IO) {
        lock.withLock {
            val account = settings.accountId.first()
            val snapshot = account?.let { homework.observe(it).first() }
            if (account == null || snapshot == null) {
                manager.cancel(request())
                notifier.clear()
                return@withLock
            }
            val hours = settings.settings.first().reminderHours
            val now = Instant.now()
            val alreadyDelivered = delivered.getStringSet(account, emptySet()).orEmpty()
            val plan = planDeadlineAlarm(snapshot, hours, now, alreadyDelivered)
            // Avoid posting a notification for a previous account during an account switch.
            if (settings.accountId.first() != account) return@withLock
            val result = CollectDeadlineRemindersUseCase()(snapshot.copy(items = plan.ready), hours, now)
            if (result != null && notifier.post(result, now)) {
                delivered.edit().putStringSet(account, alreadyDelivered + plan.readyKeys).apply()
            }
            val next = plan.next
            if (next == null) manager.cancel(request())
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), request())
        }
    }
}

@AndroidEntryPoint
class DeadlineAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var alarm: DeadlineAlarm

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(REMIND, Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED)) return
        val broadcast = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeoutOrNull(8_000) { alarm.check() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w("DeadlineAlarm", "Could not check cached deadlines", e) }
            finally { broadcast.finish() }
        }
    }

    companion object { const val REMIND = "com.homeworkassistant.action.DEADLINE_REMINDER" }
}
