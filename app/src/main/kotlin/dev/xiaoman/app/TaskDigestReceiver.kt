package dev.xiaoman.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/** Receives the checkpoint alarm, posts the aggregated digest, then schedules the next one. */
class TaskDigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as XiaomanApp).graph
        val pending = goAsync()
        graph.scope.launch {
            try {
                graph.taskScheduler.pushDue()
            } finally {
                graph.taskScheduler.scheduleNext()
                pending.finish()
            }
        }
    }
}
