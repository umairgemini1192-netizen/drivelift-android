package com.drivelift.app.feature.cockpit.widget

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Instantly refreshes [CockpitGlanceWidget] whenever the next alarm changes, system clock jumps,
 * or the device timezone changes.
 */
class CockpitAlarmSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                CockpitGlanceWidget.updateAllWidgets(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
