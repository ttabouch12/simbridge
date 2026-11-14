package io.github.sparkcyf.voicesipagent.app

import android.app.Application

class GatewayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installWatchdog()
        // Telecom may start our process just to bind the InCallService, so the runtime (and with
        // it the CellularEndpoint) must exist before any activity or service runs.
        GatewayRuntime.get(this).start()
        GatewayService.start(this)
    }

    /**
     * On a crash, ask the system to start us again in 5 s and note it, so the main phone is told.
     * Samsung's battery manager can also kill the process; the foreground service and the boot
     * receiver bring it back, and [GatewayWatchdog] checks every 15 minutes.
     */
    private fun installWatchdog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                getSharedPreferences("control", MODE_PRIVATE).edit().putBoolean("crashed", true).commit()
                val pi = android.app.PendingIntent.getService(this, 7, android.content.Intent(this, GatewayService::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                getSystemService(android.app.AlarmManager::class.java)
                    .set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 5000, pi)
            }
            previous?.uncaughtException(t, e)
        }
        GatewayWatchdog.schedule(this)
    }
}

/** Every 15 minutes (even in Doze, with an inexact alarm) makes sure the gateway service runs. */
class GatewayWatchdog : android.content.BroadcastReceiver() {
    override fun onReceive(c: android.content.Context, i: android.content.Intent) {
        GatewayService.start(c)
        schedule(c)
    }

    companion object {
        fun schedule(c: android.content.Context) {
            val pi = android.app.PendingIntent.getBroadcast(c, 8, android.content.Intent(c, GatewayWatchdog::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            c.getSystemService(android.app.AlarmManager::class.java).setAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 15 * 60_000L, pi)
        }
    }
}
