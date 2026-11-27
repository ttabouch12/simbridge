package io.github.simbridge.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.simbridge.LinkCore
import io.github.simbridge.LinkState
import io.github.simbridge.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The four places of the app, in bottom-bar order. */
object Tabs { const val MESSAGES = 0; const val CALLS = 1; const val SIM = 2; const val SETTINGS = 3 }

internal data class Dest(val label: Int, val on: ImageVector, val off: ImageVector)

internal val DESTS = listOf(
    Dest(R.string.tab_messages, Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    Dest(R.string.tab_calls, Icons.Filled.Call, Icons.Outlined.Call),
    Dest(R.string.tab_sim_phone, Icons.Filled.PhoneAndroid, Icons.Outlined.PhoneAndroid),
    Dest(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

/**
 * Home: a navigation bar with Messages, Calls, SIM phone and Settings. Pages also swipe, except
 * Messages, where a swipe acts on a conversation. Everything deeper opens over this screen.
 */
@Composable
fun HomeScreen(
    core: LinkCore,
    tab: Int,
    onTab: (Int) -> Unit,
    onOpenChat: (String, String) -> Unit,
    onNewChat: () -> Unit,
    onTool: (Tool) -> Unit,
    onRemote: () -> Unit,
    onSettingsSection: (Int) -> Unit,
    onBlocked: () -> Unit,
    onDial: (String) -> Unit = {},
) {
    val pager = rememberPagerState(tab) { DESTS.size }
    val scope = rememberCoroutineScope()
    val link by core.status.collectAsState()
    val version by core.store.version.collectAsState()
    var unread by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) { unread = withContext(Dispatchers.IO) { core.store.conversations().count { it.unread > 0 && !it.archived } } }
    LaunchedEffect(tab) { if (pager.currentPage != tab) pager.scrollToPage(tab) }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { if (it != tab) onTab(it) } }

    Scaffold(
        bottomBar = {
            NavigationBar {
                DESTS.forEachIndexed { i, d ->
                    val selected = pager.currentPage == i
                    NavigationBarItem(
                        selected = selected,
                        onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        icon = { TabIcon(i, selected, unread, link.state != LinkState.CONNECTED) },
                        label = { Text(stringResource(d.label)) },
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        HorizontalPager(pager, Modifier.fillMaxSize().padding(pad), beyondViewportPageCount = 1,
            userScrollEnabled = pager.currentPage != Tabs.MESSAGES) { page ->
            when (page) {
                Tabs.MESSAGES -> ConversationsScreen(core, onOpenChat, onNewChat) { scope.launch { pager.animateScrollToPage(Tabs.SETTINGS) } }
                Tabs.CALLS -> CallsScreen(core, onTool, onSettingsSection, onBlocked, onOpenChat, onDial)
                Tabs.SIM -> SimPhoneScreen(core, null, { t -> t?.let(onTool) }, onRemote)
                else -> SettingsScreen(core, null, { s -> s?.let(onSettingsSection) })
            }
        }
    }
}

/** A place's icon with its badge: unread count on Messages, a dot on SIM phone when it's offline. */
@Composable
internal fun TabIcon(i: Int, selected: Boolean, unread: Int, offline: Boolean) {
    val d = DESTS[i]
    BadgedBox(badge = {
        when {
            i == Tabs.MESSAGES && unread > 0 -> Badge { Text(if (unread > 99) "99+" else unread.toString()) }
            i == Tabs.SIM && offline -> Badge()
        }
    }) { Icon(if (selected) d.on else d.off, null) }
}
