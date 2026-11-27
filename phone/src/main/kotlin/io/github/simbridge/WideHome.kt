package io.github.simbridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.simbridge.ui.BlockedScreen
import io.github.simbridge.ui.CallsScreen
import io.github.simbridge.ui.ChatScreen
import io.github.simbridge.ui.ConversationsScreen
import io.github.simbridge.ui.DESTS
import io.github.simbridge.ui.NewChatScreen
import io.github.simbridge.ui.RecordingsScreen
import io.github.simbridge.ui.SettingsScreen
import io.github.simbridge.ui.SimPhoneScreen
import io.github.simbridge.ui.TabIcon
import io.github.simbridge.ui.Tabs
import io.github.simbridge.ui.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tablets, foldables and landscape: wide enough for a list and its detail side by side. */
@Composable
fun isWide() = LocalConfiguration.current.screenWidthDp >= 600

/** The place on the rail that a screen belongs to. */
private fun tabOf(s: Screen): Int = when (s) {
    is Screen.Home -> s.tab
    is Screen.Chat, Screen.NewChat -> Tabs.MESSAGES
    Screen.Blocked -> Tabs.CALLS
    is Screen.Tool -> if (s.tool == Tool.CALLS) Tabs.CALLS else Tabs.SIM
    is Screen.Settings, Screen.Recordings -> Tabs.SETTINGS
    Screen.Remote -> Tabs.SIM
}

/**
 * Big-screen home: a navigation rail on the side, then every place as list + detail.
 * Messages: conversations | chat. Calls: calls | voicemail or blocked numbers.
 * SIM phone: controls and tools | the tool. Settings: sections | the section.
 */
@Composable
fun WideHome(core: LinkCore, s: Screen, go: (Screen) -> Unit) {
    val tab = tabOf(s)
    val link by core.status.collectAsState()
    val version by core.store.version.collectAsState()
    var unread by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) { unread = withContext(Dispatchers.IO) { core.store.conversations().count { it.unread > 0 && !it.archived } } }
    val width = LocalConfiguration.current.screenWidthDp
    val listWidth = if (width >= 1000) 420.dp else 360.dp
    // Side by side from 840 dp (landscape tablets); portrait tablets get the rail and one page at a time.
    val twoPane = width >= 840

    Row(Modifier.fillMaxSize()) {
        NavigationRail(Modifier.fillMaxHeight().padding(top = 24.dp)) {
            Spacer(Modifier.weight(1f))
            DESTS.forEachIndexed { i, d ->
                NavigationRailItem(
                    selected = tab == i,
                    onClick = { go(Screen.Home(i)) },
                    icon = { TabIcon(i, tab == i, unread, link.state != LinkState.CONNECTED) },
                    label = { Text(stringResource(d.label)) },
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
        }
        VerticalDivider()
        if (twoPane || s is Screen.Home) Box(if (twoPane) Modifier.width(listWidth).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight()) {
            when (tab) {
                Tabs.MESSAGES -> ConversationsScreen(core, { k, a -> go(Screen.Chat(k, a)) }, { go(Screen.NewChat) }) { go(Screen.Settings(2)) }
                Tabs.CALLS -> CallsScreen(core, { go(Screen.Tool(it)) }, { go(Screen.Settings(it)) }, { go(Screen.Blocked) }) { k, a -> go(Screen.Chat(k, a)) }
                Tabs.SIM -> SimPhoneScreen(core, null, { t -> t?.let { go(Screen.Tool(it)) } }) { go(Screen.Remote) }
                else -> SettingsScreen(core, null, { sec -> sec?.let { go(Screen.Settings(it)) } })
            }
        }
        if (twoPane) VerticalDivider()
        if (twoPane || s !is Screen.Home) Surface(Modifier.weight(1f).fillMaxHeight(),
            color = if (twoPane) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface) {
            val back = { go(Screen.Home(tab)) }
            // The detail: what the screen points at, or a sensible default for the place.
            val detail = if (s is Screen.Home) when (tab) {
                Tabs.CALLS -> Screen.Tool(Tool.CALLS)
                Tabs.SIM -> Screen.Tool(Tool.CHECK)
                Tabs.SETTINGS -> Screen.Settings(0)
                else -> s
            } else s
            key(detail) {
                when (detail) {
                    is Screen.Chat -> ChatScreen(core, detail.key, detail.address, detail.draft, back)
                    Screen.NewChat -> NewChatScreen(core, back) { k, a -> go(Screen.Chat(k, a)) }
                    is Screen.Tool -> SimPhoneScreen(core, detail.tool, { t -> go(if (t == null) Screen.Home(tab) else Screen.Tool(t)) }) { go(Screen.Remote) }
                    is Screen.Settings -> SettingsScreen(core, detail.section,
                        onSection = { sec -> go(if (sec == null) Screen.Home(Tabs.SETTINGS) else Screen.Settings(sec)) },
                        onSimPhone = { go(Screen.Home(Tabs.SIM)) },
                        onBlocked = { go(Screen.Blocked) },
                        onRecordings = { go(Screen.Recordings) })
                    Screen.Recordings -> RecordingsScreen { go(Screen.Settings(1)) }
                    Screen.Blocked -> BlockedScreen(core, back)
                    else -> EmptyPane()
                }
            }
        }
    }
}

@Composable
private fun EmptyPane() {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.pane_chat), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
