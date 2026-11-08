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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
fun ChatScreen(core: LinkCore, key: String, address: String, initialDraft: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version by core.store.version.collectAsState()
    val status by core.status.collectAsState()
    var msgs by remember { mutableStateOf(emptyList<Msg>()) }
    var draft by remember(key) { mutableStateOf(initialDraft) }
    var menu by remember { mutableStateOf(false) }
    var pending by remember(key) { mutableStateOf(emptyList<io.github.simbridge.Attachment>()) }
    val group = io.github.simbridge.Group.isGroup(address)
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        Thread {
            val added = uris.mapNotNull { io.github.simbridge.Mms.prepareOutgoing(ctx, it) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (added.size < uris.size) Toast.makeText(ctx, "Some files are too large for a picture message", Toast.LENGTH_LONG).show()
                pending = pending + added
            }
        }.start()
    }
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
                    if (!gateway && !group && address.any { it.isDigit() }) IconButton({ call(ctx, address) }) { Icon(Icons.Filled.Call, "Call") }
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
            if (!gateway) Composer(draft, { draft = it }, connected = status.state == LinkState.CONNECTED,
                pending = pending, onAttach = {
                    picker.launch(androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, onRemove = { a -> pending = pending - a }) {
                val text = draft.trim()
                when {
                    pending.isNotEmpty() -> { core.sendMms(address, text, pending); pending = emptyList(); draft = "" }
                    text.isNotEmpty() -> { core.sendText(address, text); draft = "" }
                }
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
                    is Item.Bubble -> Bubble(item, core, group,
                        onRetry = { core.retry(item.msg) },
                        onCopy = { copy(ctx, item.msg.body) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(item: Item.Bubble, core: LinkCore, group: Boolean, onRetry: () -> Unit, onCopy: () -> Unit) {
    val m = item.msg
    val ctx = LocalContext.current
    val mms = remember(m.att) { io.github.simbridge.MmsInfo.parse(m.att) }
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
        if (group && !m.outgoing && !item.joinAbove) mms?.from?.let { f ->
            Text(remember(f) { core.contacts.lookup(f)?.name ?: f }, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
        }
        mms?.files?.forEach { a -> AttachmentView(a, m.outgoing) }
        if (m.body.isNotBlank() || mms == null) Row(verticalAlignment = Alignment.CenterVertically) {
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
                m.status == Msg.DELIVERED -> "Delivered · ${clockTime(m.time)}"
                m.outgoing -> "Sent · ${clockTime(m.time)}"
                else -> clockTime(m.time)
            } + (if (m.sim > 0) " · SIM ${m.sim}" else "")
            Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Composer(text: String, onText: (String) -> Unit, connected: Boolean,
                     pending: List<io.github.simbridge.Attachment>, onAttach: () -> Unit,
                     onRemove: (io.github.simbridge.Attachment) -> Unit, onSend: () -> Unit) {
    val ctx = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surface) {
      Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        if (pending.isNotEmpty()) Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pending.forEach { a ->
                Box {
                    val bmp = remember(a) { io.github.simbridge.Mms.thumbnail(ctx, a, 160)?.asImageBitmap() }
                    if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    androidx.compose.material3.SmallFloatingActionButton({ onRemove(a) }, Modifier.align(Alignment.TopEnd).size(24.dp)) {
                        Icon(Icons.Filled.Close, "Remove", Modifier.size(14.dp))
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onAttach, Modifier.padding(bottom = 4.dp)) { Icon(Icons.Filled.AddPhotoAlternate, "Add picture") }
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
            FilledIconButton(onClick = onSend, enabled = text.isNotBlank() || pending.isNotEmpty(), modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
      }
    }
}

/** A picture (tap to open full size) or a file chip. */
@Composable
private fun AttachmentView(a: io.github.simbridge.Attachment, outgoing: Boolean) {
    val ctx = LocalContext.current
    val open = {
        val f = io.github.simbridge.Mms.fileOf(ctx, a)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Unit
    }
    if (a.isImage) {
        val bmp = remember(a) { io.github.simbridge.Mms.thumbnail(ctx, a)?.asImageBitmap() }
        if (bmp != null) androidx.compose.foundation.Image(bmp, "Picture",
            Modifier.padding(vertical = 2.dp).widthIn(max = 260.dp).clip(RoundedCornerShape(18.dp))
                .clickable { open() }, contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
        return
    }
    androidx.compose.material3.AssistChip(onClick = open, label = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.AttachFile, null) }, modifier = Modifier.padding(vertical = 2.dp))
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
