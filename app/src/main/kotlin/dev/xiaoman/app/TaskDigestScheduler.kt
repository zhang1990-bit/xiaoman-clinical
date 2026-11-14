package dev.xiaoman.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.xiaoman.api.TaskCheckpoints
import dev.xiaoman.api.TaskStore
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Drives the four daily checkpoints (08:00 / 11:00 / 14:00 / 17:00) for clinical tasks.
 *
 * Uses [AlarmManager.setAndAllowWhileIdle] rather than the clock app: the user only ever sees a
 * notification posted by Xiaoman. Alarms do not survive a reboot, so [BootReceiver] calls
 * [scheduleNext] again; the tasks themselves live in the JSON store, never in the alarm.
 */
class TaskDigestScheduler(
    private val context: Context,
    private val tasks: TaskStore,
    private val notifier: Notifier,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Schedules the next checkpoint; safe to call repeatedly. */
    fun scheduleNext() {
        val next = nextCheckpoint(ZonedDateTime.now())
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, TaskDigestReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending)
    }

    /** Posts one aggregated notification for everything due now, then counts the push. */
    suspend fun pushDue() {
        val today = LocalDate.now()
        val due = tasks.all().filter { it.isDue(today) }
        if (due.isEmpty()) return
        notifier.showDigest(due, today)
        tasks.updateAll(due.map { it.copy(pushCount = it.pushCount + 1) })
    }

    /**
     * Long-press on the chat title: pushes today's card right away without counting a push.
     * Returns how many tasks went in, or null when notifications are blocked.
     */
    suspend fun pushTest(): Int? {
        val today = LocalDate.now()
        val due = tasks.all().filter { it.isDue(today) }
        return if (notifier.showDigest(due, today, test = true)) due.size else null
    }

    companion object {
        private const val REQUEST_CODE = 4101

        /** The first checkpoint strictly after [now], rolling to tomorrow's 08:00 when the day is done. */
        fun nextCheckpoint(now: ZonedDateTime): ZonedDateTime {
            for (hour in TaskCheckpoints.HOURS) {
                val at = now.toLocalDate().atTime(hour, 0).atZone(now.zone)
                if (at.isAfter(now)) return at
            }
            return now.toLocalDate().plusDays(1).atTime(TaskCheckpoints.HOURS.first(), 0).atZone(now.zone)
        }
    }
}
