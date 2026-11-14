package io.github.simbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import io.github.simbridge.R
import androidx.compose.ui.res.stringResource
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.simbridge.Conversation
import io.github.simbridge.LinkCore
import io.github.simbridge.LinkState
import io.github.simbridge.LinkStatus
import io.github.simbridge.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ConvRow(val conv: Conversation, val name: String, val photo: Uri?)
private data class Hit(val msg: Msg, val name: String)
private enum class Filter(val label: Int) {
    ALL(R.string.filter_all), UNREAD(R.string.filter_unread), ARCHIVED(R.string.filter_archived), STARRED(R.string.filter_starred)
}

/** Swipe and toolbar actions on conversations. */
internal object ConvAction {
    const val READ = "read"; const val ARCHIVE = "archive"; const val DELETE = "delete"; const val PIN = "pin"; const val NONE = "none"
    val all = listOf(READ, ARCHIVE, DELETE, PIN, NONE)
    fun label(a: String) = when (a) {
        READ -> R.string.act_read; ARCHIVE -> R.string.act_archive; DELETE -> R.string.delete; PIN -> R.string.act_pin
        else -> R.string.act_none
    }
    fun icon(a: String): ImageVector? = when (a) {
        READ -> Icons.Outlined.MarkEmailRead; ARCHIVE -> Icons.Outlined.Archive; DELETE -> Icons.Outlined.Delete
        PIN -> Icons.Outlined.PushPin; else -> null
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(core: LinkCore, onOpen: (String, String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val version by core.store.version.collectAsState()
    val status by core.status.collectAsState()
    var rows by remember { mutableStateOf(emptyList<ConvRow>()) }
    var loaded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var hits by remember { mutableStateOf(emptyList<Hit>()) }
    // Long-press selects; while anything is selected a tap selects too (like Gmail and Google Messages).
    var selected by remember { mutableStateOf(emptySet<String>()) }
    // Deleted conversations stay hidden until the Undo bar is gone, then they are really deleted.
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    var blockAsk by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val swipeRight = core.prefs.swipeRight
    val swipeLeft = core.prefs.swipeLeft

    LaunchedEffect(version) {
        rows = withContext(Dispatchers.IO) {
            core.store.conversations().map { c ->
                val info = core.contacts.lookup(c.address)
                ConvRow(c, info?.name ?: c.address, info?.photo)
            }
        }
        selected = selected.filter { k -> rows.any { it.conv.key == k } }.toSet()
        loaded = true
    }
    // Text inside messages (and the starred ones), under the matching conversations.
    LaunchedEffect(version, query, filter) {
        val q = query.trim()
        hits = if (q.length >= 2 || filter == Filter.STARRED) withContext(Dispatchers.IO) {
            core.store.search(q, starred = filter == Filter.STARRED).map { Hit(it, core.contacts.lookup(it.address)?.name ?: it.address) }
        } else emptyList()
    }
    val shown = remember(rows, query, filter, hidden) {
        rows.filter { r ->
            r.conv.key !in hidden && when (filter) {
                Filter.ALL -> !r.conv.archived
                Filter.UNREAD -> r.conv.unread > 0 && !r.conv.archived
                Filter.ARCHIVED -> r.conv.archived
                Filter.STARRED -> false
            } && (query.isBlank() || r.name.contains(query, true) || r.conv.address.contains(query) || r.conv.last.body.contains(query, true))
        }
    }

    fun delete(keys: Set<String>) {
        if (keys.isEmpty()) return
        hidden = hidden + keys
        scope.launch {
            var undone = false
            try {
                undone = snack.showSnackbar(ctx.getString(R.string.conv_deleted, keys.size), ctx.getString(R.string.undo),
                    duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed
            } finally {
                // Also when this screen closes first: the delete was asked for and not undone.
                if (!undone) keys.forEach { core.deleteConversation(it) }
                hidden = hidden - keys
            }
        }
    }

    fun act(action: String, keys: Set<String>) {
        val sel = rows.filter { it.conv.key in keys }
        when (action) {
            ConvAction.READ -> if (sel.any { it.conv.unread > 0 }) sel.forEach { core.markRead(it.conv.key); core.notifier.cancel(it.conv.key) }
                else sel.forEach { core.store.markUnread(it.conv.key) }
            ConvAction.ARCHIVE -> core.store.setArchived(keys, !sel.all { it.conv.archived })
            ConvAction.PIN -> core.store.setPinned(keys, !sel.all { it.conv.pinned })
            ConvAction.DELETE -> delete(keys)
        }
        selected = emptySet()
    }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    val listState = rememberLazyListState()
    val expanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    Scaffold(
        topBar = {
            if (selected.isEmpty()) Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                SearchBar(query, { query = it }, onSettings)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Filter.entries.forEach { f -> FilterChip(filter == f, { filter = f }, label = { Text(stringResource(f.label)) }) }
                }
            } else TopAppBar(
                navigationIcon = { IconButton({ selected = emptySet() }) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) } },
                title = { Text(selected.size.toString()) },
                actions = {
                    val sel = rows.filter { it.conv.key in selected }
                    IconButton({ act(ConvAction.PIN, selected) }) { Icon(Icons.Outlined.PushPin, stringResource(R.string.act_pin)) }
                    IconButton({ act(ConvAction.ARCHIVE, selected) }) {
                        Icon(if (sel.all { it.conv.archived }) Icons.Outlined.Unarchive else Icons.Outlined.Archive, stringResource(R.string.act_archive))
                    }
                    IconButton({ act(ConvAction.READ, selected) }) {
                        Icon(if (sel.any { it.conv.unread > 0 }) Icons.Outlined.MarkEmailRead else Icons.Outlined.MarkEmailUnread, stringResource(R.string.act_read))
                    }
                    IconButton({ act(ConvAction.DELETE, selected) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.select_all)) }, onClick = { menu = false; selected = shown.map { it.conv.key }.toSet() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.block)) }, onClick = { menu = false; blockAsk = true })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            if (selected.isEmpty()) ExtendedFloatingActionButton(
                onClick = onNew, expanded = expanded,
                icon = { Icon(Icons.AutoMirrored.Filled.Chat, null) },
                text = { Text(stringResource(R.string.start_chat)) },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 96.dp)) {
            item { StatusBanner(status, onSettings) }
            if (loaded && shown.isEmpty() && hits.isEmpty()) item { EmptyState(query.isNotBlank() || filter != Filter.ALL) }
            items(shown, key = { it.conv.key }) { r ->
                val picked = r.conv.key in selected
                SwipeRow(swipeRight, swipeLeft, enabled = selected.isEmpty(), unread = r.conv.unread > 0, onAction = { act(it, setOf(r.conv.key)) }) {
                    ConversationRow(r, picked, Modifier.combinedClickable(
                        onClick = {
                            if (selected.isEmpty()) onOpen(r.conv.key, r.conv.address)
                            else selected = if (picked) selected - r.conv.key else selected + r.conv.key
                        },
                        onLongClick = { selected = if (picked) selected - r.conv.key else selected + r.conv.key },
                    ))
                }
            }
            if (hits.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.search_in_messages), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                items(hits, key = { "h${it.msg.id}" }) { h ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(h.msg.key, h.msg.address) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (h.msg.starred) "★ " else "") + h.msg.body, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(shortTime(h.msg.time), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (blockAsk) {
        val numbers = rows.filter { it.conv.key in selected && !isGateway(it.conv.address) && !io.github.simbridge.Group.isGroup(it.conv.address) }
            .map { it.conv.address }
        AlertDialog(
            onDismissRequest = { blockAsk = false },
            title = { Text(stringResource(R.string.block_q)) },
            text = { Text(numbers.joinToString("\n")) },
            confirmButton = {
                TextButton({
                    core.prefs.blocked = core.prefs.blocked + numbers
                    core.blockRulesChanged()
                    blockAsk = false; selected = emptySet()
                }, enabled = numbers.isNotEmpty()) { Text(stringResource(R.string.block)) }
            },
            dismissButton = { TextButton({ blockAsk = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A conversation row that can be swiped either way; the action runs and the row slides back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(right: String, left: String, enabled: Boolean, unread: Boolean, onAction: (String) -> Unit, content: @Composable () -> Unit) {
    val fire by rememberUpdatedState(onAction)
    val r by rememberUpdatedState(right)
    val l by rememberUpdatedState(left)
    val state = rememberSwipeToDismissBoxState()
    // A full swipe runs the action, then the row slides back (when the action removes the row,
    // the list drops it anyway).
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> fire(r)
            SwipeToDismissBoxValue.EndToStart -> fire(l)
            else -> return@LaunchedEffect
        }
        state.snapTo(SwipeToDismissBoxValue.Settled)
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = enabled && right != ConvAction.NONE,
        enableDismissFromEndToStart = enabled && left != ConvAction.NONE,
        backgroundContent = {
            val dir = state.dismissDirection
            val action = when (dir) { SwipeToDismissBoxValue.StartToEnd -> right; SwipeToDismissBoxValue.EndToStart -> left; else -> ConvAction.NONE }
            val color = when (action) {
                ConvAction.DELETE -> MaterialTheme.colorScheme.errorContainer
                ConvAction.ARCHIVE -> MaterialTheme.colorScheme.tertiaryContainer
                ConvAction.READ -> MaterialTheme.colorScheme.primaryContainer
                ConvAction.PIN -> MaterialTheme.colorScheme.secondaryContainer
                else -> Color.Transparent
            }
            Box(Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
                contentAlignment = if (dir == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart) {
                val icon = if (action == ConvAction.READ && !unread) Icons.Outlined.MarkEmailUnread else ConvAction.icon(action)
                icon?.let { Icon(it, stringResource(ConvAction.label(action))) }
            }
        },
    ) { content() }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(56.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(16.dp))
                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                TextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    placeholder = { Text(stringResource(R.string.search)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    modifier = Modifier.weight(1f),
                )
                if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Filled.Close, stringResource(R.string.clear)) }
                IconButton(onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.tab_settings)) }
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun StatusBanner(s: LinkStatus, onSettings: () -> Unit) {
    val (icon, text, problem) = when (s.state) {
        LinkState.CONNECTED -> Triple(Icons.Filled.Wifi, "Connected via ${s.network}", false)
        LinkState.SEARCHING -> Triple(Icons.Filled.Sync, stringResource(R.string.looking), true)
        LinkState.NO_WIFI -> Triple(Icons.Filled.WifiOff, s.detail, true)
        LinkState.NEEDS_PASSWORD -> Triple(Icons.Filled.Key, s.detail.ifBlank { stringResource(R.string.finish_setup) }, true)
    }
    val bg = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (problem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Surface(shape = CircleShape, color = bg, contentColor = fg, modifier = Modifier.clickable(onClick = onSettings)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ConversationRow(r: ConvRow, picked: Boolean, modifier: Modifier) {
    val unread = r.conv.unread > 0
    val m = r.conv.last
    // Opaque, so the swipe colour only shows where the row has moved away.
    Row(modifier.fillMaxWidth().background(if (picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (picked) Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary)
        } else Avatar(r.name, r.photo, gateway = isGateway(r.conv.address))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val prefix = when {
                !m.outgoing -> ""
                m.status == Msg.FAILED -> stringResource(R.string.not_sent_prefix)
                else -> stringResource(R.string.you_prefix)
            }
            Text(prefix + m.body.ifBlank { if (m.att.isNotBlank()) "📷 Photo" else "" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    m.outgoing && m.status == Msg.FAILED -> MaterialTheme.colorScheme.error
                    unread -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(shortTime(m.time), style = MaterialTheme.typography.labelMedium,
                color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal)
            if (unread) Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(r.conv.unread.toString()) }
            if (r.conv.pinned) Icon(Icons.Filled.PushPin, stringResource(R.string.act_pin), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState(searching: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 96.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Forum, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(if (searching) stringResource(R.string.no_matches) else stringResource(R.string.no_convs), style = MaterialTheme.typography.titleMedium)
        if (!searching) Text(stringResource(R.string.no_convs_sub),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

