package io.github.simbridge.ui

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationsScreen(core: LinkCore, onOpen: (String, String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val version by core.store.version.collectAsState()
    val status by core.status.collectAsState()
    var rows by remember { mutableStateOf(emptyList<ConvRow>()) }
    var loaded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<ConvRow?>(null) }
    LaunchedEffect(version) {
        rows = withContext(Dispatchers.IO) {
            core.store.conversations().map { c ->
                val info = core.contacts.lookup(c.address)
                ConvRow(c, info?.name ?: c.address, info?.photo)
            }
        }
        loaded = true
    }
    val shown = remember(rows, query) {
        if (query.isBlank()) rows else rows.filter {
            it.name.contains(query, true) || it.conv.address.contains(query) || it.conv.last.body.contains(query, true)
        }
    }
    val listState = rememberLazyListState()
    val expanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    Scaffold(
        topBar = { SearchBar(query, { query = it }, onSettings) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew, expanded = expanded,
                icon = { Icon(Icons.AutoMirrored.Filled.Chat, null) },
                text = { Text(stringResource(R.string.start_chat)) },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 96.dp)) {
            item { StatusBanner(status, onSettings) }
            if (loaded && shown.isEmpty()) item { EmptyState(query.isNotBlank()) }
            items(shown, key = { it.conv.key }) { r ->
                ConversationRow(r, Modifier.combinedClickable(
                    onClick = { onOpen(r.conv.key, r.conv.address) },
                    onLongClick = { toDelete = r },
                ))
            }
        }
    }
    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text(stringResource(R.string.delete_conv_q)) },
            text = { Text(stringResource(R.string.delete_conv_text, r.name)) },
            confirmButton = { TextButton({ core.deleteConversation(r.conv.key); toDelete = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
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
private fun ConversationRow(r: ConvRow, modifier: Modifier) {
    val unread = r.conv.unread > 0
    val m = r.conv.last
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(r.name, r.photo, gateway = isGateway(r.conv.address))
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

