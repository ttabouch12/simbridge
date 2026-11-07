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
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import io.github.simbridge.ui.ChatScreen
import io.github.simbridge.ui.ConversationsScreen
import io.github.simbridge.ui.LinkTheme
import io.github.simbridge.ui.NewChatScreen
import io.github.simbridge.ui.SettingsScreen

sealed interface Screen {
    data object List : Screen
    data class Chat(val key: String, val address: String, val draft: String = "") : Screen
    data object NewChat : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    private val screen = mutableStateOf<Screen>(Screen.List)
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
                    BackHandler(enabled = s != Screen.List) {
                        screen.value = Screen.List
                    }
                    io.github.simbridge.ui.ConfirmPairing(core, pendingPair.value) { pendingPair.value = null }
                    AnimatedContent(s, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { cur ->
                        when (cur) {
                            Screen.List -> ConversationsScreen(core,
                                onOpen = { k, a -> screen.value = Screen.Chat(k, a) },
                                onNew = { screen.value = Screen.NewChat },
                                onSettings = { screen.value = Screen.Settings })
                            is Screen.Chat -> ChatScreen(core, cur.key, cur.address, cur.draft) { screen.value = Screen.List }
                            Screen.NewChat -> NewChatScreen(core, { screen.value = Screen.List }) { k, a -> screen.value = Screen.Chat(k, a) }
                            Screen.Settings -> SettingsScreen(core) { screen.value = Screen.List }
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
            "android.permission.ACCESS_LOCAL_NETWORK",
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    companion object { const val EXTRA_KEY = "key" }
}
