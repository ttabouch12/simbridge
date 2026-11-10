package io.github.simbridge

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps this phone registered with the SIM phone in the background.
 * Started from the app's own screen it also carries the microphone type, which Android requires
 * before a background app may capture call audio; after a reboot it starts without it until the
 * app (or its notification) is opened once.
 */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val core = LinkApp.core(this)
        val n = core.notifier.statusNotification(core.status.value)
        val full = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        instance = this
        if (LinkApp.core(this).prefs.webTexts) LinkApp.core(this).web.start()
        UpdateCheck.maybeCheck(this)
        if (placesJob == null) placesJob = scope.launch {
            while (true) { kotlinx.coroutines.delay(60_000); runCatching { Places.check(this@LinkService, LinkApp.core(this@LinkService)) } }
        }
        val fromUi = intent?.getBooleanExtra(EXTRA_UI, false) == true
        // Already running: only re-assert foreground when a visible screen can upgrade it, so a
        // background poke never downgrades microphone access we already hold.
        if (foreground && (!fromUi || micReady)) { core.start(); return START_STICKY }
        foreground = true
        micReady = runCatching { startForeground(Notifier.ID_STATUS, n, full); fromUi || micReady }.getOrElse {
            Log.w("service: no microphone access from background yet (${it.message})")
            runCatching { startForeground(Notifier.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            false
        }
        if (micReady) {
            core.notifier.micAllowed()
            getSystemService(NotificationManager::class.java).notify(Notifier.ID_STATUS, core.notifier.statusNotification(core.status.value))
        }
        Log.i("service: running (call audio ${if (micReady) "ready" else "needs the app opened once"})")
        core.start()
        if (watcher == null) watcher = scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            core.status.collect { nm.notify(Notifier.ID_STATUS, core.notifier.statusNotification(it)) }
        }
        if (schedJob == null) schedJob = scope.launch {
            while (true) { runCatching { core.sendDueScheduled() }; kotlinx.coroutines.delay(20_000) }
        }
        if (widgetJob == null) widgetJob = scope.launch {
            kotlinx.coroutines.flow.combine(core.status, core.control.status) { _, _ -> }.collect { LinkWidget.update(this@LinkService) }
        }
        return START_STICKY
    }

    /**
     * During a Telecom call Android lets a ConnectionService app take the phoneCall type, and with
     * it the microphone, even from the background. Used when the service started without the mic
     * (after a reboot or update) so the first call still has audio.
     */
    private fun upgradeForCall() {
        if (micReady) return
        val n = LinkApp.core(this).notifier.statusNotification(LinkApp.core(this).status.value)
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        // phoneCall first: an active Telecom call may make the app eligible for the microphone.
        runCatching { startForeground(Notifier.ID_STATUS, n, base) }
            .onFailure { Log.w("service: phoneCall type refused (${it.message})"); LinkApp.core(this).notifier.micBlocked(); return }
        runCatching { startForeground(Notifier.ID_STATUS, n, base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
            .onSuccess { micReady = true; Log.i("service: microphone granted for the call") }
            .onFailure { Log.w("service: microphone refused in the background"); LinkApp.core(this).notifier.micBlocked() }
    }

    private var placesJob: kotlinx.coroutines.Job? = null
    private var widgetJob: kotlinx.coroutines.Job? = null
    private var schedJob: kotlinx.coroutines.Job? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile var micReady = false
            private set
        @Volatile private var instance: LinkService? = null

        /** Call when a call goes active; must run on the main thread. */
        fun onCallActive() { instance?.upgradeForCall() }

        private const val EXTRA_UI = "ui"

        /** [fromUi]: called while one of our screens is visible, so the microphone type sticks. */
        fun start(context: Context, fromUi: Boolean = false) {
            runCatching { context.startForegroundService(Intent(context, LinkService::class.java).putExtra(EXTRA_UI, fromUi)) }
                .onFailure { Log.w("service: start refused", it) }
        }
    }
}
