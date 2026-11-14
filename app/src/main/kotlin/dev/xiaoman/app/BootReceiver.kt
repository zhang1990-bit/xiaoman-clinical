package dev.xiaoman.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Alarms are cleared by the system on reboot and on reinstall; the tasks are on disk, so rebuilding
 * the next checkpoint here is all that is needed to keep the four daily pushes alive.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        (context.applicationContext as XiaomanApp).graph.taskScheduler.scheduleNext()
    }
}
