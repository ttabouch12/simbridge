package io.github.simbridge.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.simbridge.LinkCore
import io.github.simbridge.LinkState
import io.github.simbridge.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface Item {
    data class Day(val time: Long) : Item
    data class Bubble(val msg: Msg, val joinAbove: Boolean, val joinBelow: Boolean, val showStatus: Boolean) : Item
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(core: LinkCore, key: String, address: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version by core.store.version.collectAsState()
    val status by core.status.collectAsState()
    var msgs by remember { mutableStateOf(emptyList<Msg>()) }
    var draft by remember(key) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val contact = remember(address) { core.contacts.lookup(address) }
    val name = contact?.name ?: address
    val gateway = isGateway(address)

    LaunchedEffect(version, key) {
        msgs = withContext(Dispatchers.IO) { core.store.thread(key) }
        if (msgs.any { !it.read }) withContext(Dispatchers.IO) { core.markRead(key) }
        core.notifier.cancel(key)
    }
    val items = remember(msgs) { buildItems(msgs) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name, contact?.photo, 40.dp, gateway)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (contact != null) Text(address, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (!gateway && address.any { it.isDigit() }) IconButton({ call(ctx, address) }) { Icon(Icons.Filled.Call, "Call") }
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Copy number") }, onClick = { menu = false; copy(ctx, address) })
                        DropdownMenuItem(text = { Text("Delete conversation") }, onClick = {
                            menu = false; core.deleteConversation(key); onBack()
                        })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (!gateway) Composer(draft, { draft = it }, connected = status.state == LinkState.CONNECTED) {
                val text = draft.trim()
                if (text.isNotEmpty()) { core.sendText(address, text); draft = "" }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            reverseLayout = true,
            contentPadding = PaddingValues(top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 8.dp,
                start = 12.dp, end = 12.dp),
        ) {
            items(items.asReversed(), key = { when (it) { is Item.Day -> "d${it.time}"; is Item.Bubble -> "m${it.msg.id}" } }) { item ->
                when (item) {
                    is Item.Day -> Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(dayHeader(item.time), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is Item.Bubble -> Bubble(item,
                        onRetry = { core.retry(item.msg) },
                        onCopy = { copy(ctx, item.msg.body) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(item: Item.Bubble, onRetry: () -> Unit, onCopy: () -> Unit) {
    val m = item.msg
    val big = 20.dp; val small = 4.dp
    val shape = if (m.outgoing) RoundedCornerShape(big, if (item.joinAbove) small else big, if (item.joinBelow) small else big, big)
    else RoundedCornerShape(if (item.joinAbove) small else big, big, big, if (item.joinBelow) small else big)
    val failed = m.status == Msg.FAILED
    val bg = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        m.outgoing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val fg = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        m.outgoing -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier.fillMaxWidth().padding(top = if (item.joinAbove) 2.dp else 8.dp),
        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (failed) Icon(Icons.Filled.ErrorOutline, null, Modifier.padding(end = 6.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.error)
            Surface(
                shape = shape, color = bg, contentColor = fg,
                modifier = Modifier.widthIn(max = 300.dp).combinedClickable(
                    onClick = { if (failed) onRetry() }, onLongClick = onCopy),
            ) {
                Text(m.body, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (item.showStatus || failed) {
            val label = when {
                failed -> "Not sent · Tap to try again"
                m.status == Msg.SENDING -> "Sending…"
                m.outgoing -> "Sent · ${clockTime(m.time)}"
                else -> clockTime(m.time)
            }
            Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Composer(text: String, onText: (String) -> Unit, connected: Boolean, onSend: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = text, onValueChange = onText,
                placeholder = { Text(if (connected) "Text message" else "Text message · SIM phone offline") },
                shape = RoundedCornerShape(28.dp), maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(onClick = onSend, enabled = text.isNotBlank(), modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

private fun buildItems(msgs: List<Msg>): List<Item> {
    val out = ArrayList<Item>()
    val lastOutgoing = msgs.lastOrNull { it.outgoing }?.id
    msgs.forEachIndexed { i, m ->
        val prev = msgs.getOrNull(i - 1)
        val next = msgs.getOrNull(i + 1)
        if (prev == null || !sameDay(prev.time, m.time)) out += Item.Day(m.time)
        val joinAbove = prev != null && prev.outgoing == m.outgoing && sameDay(prev.time, m.time) && m.time - prev.time < 120_000
        val joinBelow = next != null && next.outgoing == m.outgoing && sameDay(next.time, m.time) && next.time - m.time < 120_000
        val showStatus = (m.outgoing && m.id == lastOutgoing) || (!m.outgoing && !joinBelow)
        out += Item.Bubble(m, joinAbove, joinBelow, showStatus)
    }
    return out
}

private fun copy(ctx: android.content.Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

fun call(ctx: android.content.Context, number: String) {
    val granted = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    val i = Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
