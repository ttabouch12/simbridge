package io.github.simbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import io.github.simbridge.ui.ChatScreen
import io.github.simbridge.ui.ConversationsScreen
import io.github.simbridge.ui.LinkTheme
import io.github.simbridge.ui.NewChatScreen
import io.github.simbridge.ui.SettingsScreen

sealed interface Screen {
    /** The bottom-bar home on one of its four places (see ui.Tabs). */
    data class Home(val tab: Int) : Screen
    data class Chat(val key: String, val address: String, val draft: String = "") : Screen
    data object NewChat : Screen
    data class Settings(val section: Int) : Screen
    data class Tool(val tool: io.github.simbridge.ui.Tool) : Screen
    data object Blocked : Screen
    data object Recordings : Screen
    data object Remote : Screen
}

class MainActivity : ComponentActivity() {
    private val screen = mutableStateOf<Screen>(Screen.Home(io.github.simbridge.ui.Tabs.SIM))
    /** The bottom-bar place to come back to. */
    private var lastTab = io.github.simbridge.ui.Tabs.SIM
    private val pendingPair = mutableStateOf<PairedGateway?>(null)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LinkApp.core(this).contacts.clearCache()
        // Microphone may have just been granted: restart the service so it gets the microphone type.
        LinkService.start(this, fromUi = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val core = LinkApp.core(this)
        LinkService.start(this, fromUi = true)
        askPermissions()
        handle(intent)
        setContent {
            LinkTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    val s by screen
                    val home = { t: Int -> lastTab = t; screen.value = Screen.Home(t) }
                    // Back: deeper pages go up one level; on the home, other places go back to the SIM phone.
                    BackHandler(enabled = (s as? Screen.Home)?.tab != io.github.simbridge.ui.Tabs.SIM) {
                        screen.value = when (s) {
                            Screen.Blocked -> Screen.Home(io.github.simbridge.ui.Tabs.CALLS)
                            Screen.Recordings -> Screen.Settings(1)
                            is Screen.Chat, Screen.NewChat -> Screen.Home(io.github.simbridge.ui.Tabs.MESSAGES)
                            is Screen.Settings -> Screen.Home(io.github.simbridge.ui.Tabs.SETTINGS)
                            is Screen.Home -> Screen.Home(io.github.simbridge.ui.Tabs.SIM)
                            else -> Screen.Home(lastTab)
                        }
                    }
                    io.github.simbridge.ui.ConfirmPairing(core, pendingPair.value) { pendingPair.value = null }
                    // Big screens: rail + list + detail. Remote control stays full screen.
                    if (isWide() && s !is Screen.Remote) {
                        WideHome(core, s) { next -> (next as? Screen.Home)?.let { lastTab = it.tab }; screen.value = next }
                        return@Surface
                    }
                    // Going deeper slides in from the side; changing place on the home only fades.
                    AnimatedContent(s, transitionSpec = {
                        if (initialState is Screen.Home && targetState is Screen.Home) fadeIn() togetherWith fadeOut()
                        else if (targetState is Screen.Home) (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
                        else (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                    }, contentKey = { if (it is Screen.Home) "home" else it.toString() }, label = "nav") { cur ->
                        when (cur) {
                            is Screen.Home -> io.github.simbridge.ui.HomeScreen(core, cur.tab,
                                onTab = { lastTab = it; screen.value = Screen.Home(it) },
                                onOpenChat = { k, a -> screen.value = Screen.Chat(k, a) },
                                onNewChat = { screen.value = Screen.NewChat },
                                onTool = { screen.value = Screen.Tool(it) },
                                onRemote = { screen.value = Screen.Remote },
                                onSettingsSection = { screen.value = Screen.Settings(it) },
                                onBlocked = { screen.value = Screen.Blocked })
                            is Screen.Tool -> io.github.simbridge.ui.SimPhoneScreen(core, cur.tool,
                                onTool = { t -> screen.value = if (t == null) Screen.Home(lastTab) else Screen.Tool(t) },
                                onRemote = { screen.value = Screen.Remote })
                            Screen.Remote -> io.github.simbridge.ui.RemoteScreen(core) { home(io.github.simbridge.ui.Tabs.SIM) }
                            is Screen.Chat -> ChatScreen(core, cur.key, cur.address, cur.draft) { home(io.github.simbridge.ui.Tabs.MESSAGES) }
                            Screen.NewChat -> NewChatScreen(core, { home(io.github.simbridge.ui.Tabs.MESSAGES) }) { k, a -> screen.value = Screen.Chat(k, a) }
                            is Screen.Settings -> SettingsScreen(core, cur.section,
                                onSection = { sec -> screen.value = if (sec == null) Screen.Home(io.github.simbridge.ui.Tabs.SETTINGS) else Screen.Settings(sec) },
                                onSimPhone = { home(io.github.simbridge.ui.Tabs.SIM) },
                                onBlocked = { screen.value = Screen.Blocked },
                                onRecordings = { screen.value = Screen.Recordings })
                            Screen.Recordings -> io.github.simbridge.ui.RecordingsScreen { screen.value = Screen.Settings(1) }
                            Screen.Blocked -> io.github.simbridge.ui.BlockedScreen(core) { home(io.github.simbridge.ui.Tabs.CALLS) }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!LinkService.micReady) LinkService.start(this, fromUi = true)
        LinkApp.core(this).kick()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "simbridge") {
            pendingPair.value = PairedGateway.parse(intent.data.toString())
            return
        }
        intent.getStringExtra(EXTRA_KEY)?.let { key ->
            val address = LinkApp.core(this).store.thread(key).lastOrNull()?.address ?: return@let
            screen.value = Screen.Chat(key, address)
        }
        if (intent.action == Intent.ACTION_SENDTO || intent.action == Intent.ACTION_SEND) {
            val number = intent.data?.schemeSpecificPart?.substringBefore('?')?.trim()
                ?.split(',', ';')?.firstOrNull()?.trim()
            val body = intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            screen.value = if (!number.isNullOrBlank()) Screen.Chat(MessageStore.keyOf(number), number, body) else Screen.NewChat
        }
    }

    private fun askPermissions() {
        val wanted = listOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.NEARBY_WIFI_DEVICES,
            "android.permission.ACCESS_LOCAL_NETWORK", Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    companion object { const val EXTRA_KEY = "key" }
}
