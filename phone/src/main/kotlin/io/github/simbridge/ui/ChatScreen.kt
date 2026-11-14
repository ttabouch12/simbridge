package io.github.simbridge.ui

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import io.github.simbridge.R
import androidx.compose.ui.res.stringResource
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
    // Suggested replies to the last incoming text (made on this phone).
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(msgs.lastOrNull()?.id) {
        suggestions = withContext(Dispatchers.Default) { io.github.simbridge.SmartReplies.suggest(msgs) }
    }
    var draft by remember(key) { mutableStateOf(initialDraft) }
    var menu by remember { mutableStateOf(false) }
    // Long-press a bubble to select it; then tap others (copy, star, forward, details, delete).
    var picked by remember(key) { mutableStateOf(emptySet<Long>()) }
    var details by remember { mutableStateOf<Msg?>(null) }
    var find by remember(key) { mutableStateOf<String?>(null) }
    var scheduleAsk by remember { mutableStateOf(false) }
    var pending by remember(key) { mutableStateOf(emptyList<io.github.simbridge.Attachment>()) }
    val group = io.github.simbridge.Group.isGroup(address)
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        Thread {
            val added = uris.mapNotNull { io.github.simbridge.Mms.prepareOutgoing(ctx, it) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (added.size < uris.size) Toast.makeText(ctx, ctx.getString(R.string.too_large), Toast.LENGTH_LONG).show()
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
    val items = remember(msgs, find) { buildItems(find?.takeIf { it.isNotBlank() }?.let { q -> msgs.filter { it.body.contains(q, true) } } ?: msgs) }
    val scheduled = remember(version, key) { core.store.scheduled().filter { io.github.simbridge.MessageStore.keyOf(it.address) == key } }
    androidx.activity.compose.BackHandler(enabled = picked.isNotEmpty() || find != null) { picked = emptySet(); find = null }

    Scaffold(
        topBar = {
            if (picked.isNotEmpty()) {
                val sel = msgs.filter { it.id in picked }
                TopAppBar(
                    navigationIcon = { IconButton({ picked = emptySet() }) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) } },
                    title = { Text(picked.size.toString()) },
                    actions = {
                        IconButton({ copy(ctx, sel.joinToString("\n") { it.body }); picked = emptySet() }) {
                            Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy)) }
                        IconButton({ core.store.setStarred(picked, !sel.all { it.starred }); picked = emptySet() }) {
                            Icon(if (sel.all { it.starred }) Icons.Filled.Star else Icons.Outlined.StarBorder, stringResource(R.string.act_star)) }
                        IconButton({
                            runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, sel.joinToString("\n") { it.body }), null)) }
                            picked = emptySet()
                        }) { Icon(Icons.AutoMirrored.Filled.Forward, stringResource(R.string.act_forward)) }
                        if (sel.size == 1) IconButton({ details = sel.first(); picked = emptySet() }) {
                            Icon(Icons.Outlined.Info, stringResource(R.string.act_details)) }
                        IconButton({ core.deleteMessages(picked); picked = emptySet() }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
                    },
                )
            } else Column {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
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
                    if (!gateway && !group && address.any { it.isDigit() }) IconButton({ call(ctx, address) }) { Icon(Icons.Filled.Call, stringResource(R.string.call)) }
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.search_chat)) }, onClick = { menu = false; find = "" })
                        DropdownMenuItem(text = { Text(stringResource(R.string.copy_number)) }, onClick = { menu = false; copy(ctx, address) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete_conv)) }, onClick = {
                            menu = false; core.deleteConversation(key); onBack()
                        })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
            find?.let { q ->
                TextField(q, { find = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.search_chat)) },
                    trailingIcon = { IconButton({ find = null }) { Icon(Icons.Filled.Close, stringResource(R.string.clear)) } })
            }
            }
        },
        bottomBar = {
            if (!gateway) Column {
            scheduled.forEach { sc ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(scheduleTime(sc.at) + " · " + sc.body, Modifier.weight(1f).padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton({ core.store.unschedule(sc.id) }) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
                }
            }
            if (suggestions.isNotEmpty() && draft.isEmpty() && pending.isEmpty())
                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { t ->
                        androidx.compose.material3.SuggestionChip({ core.sendText(address, t); suggestions = emptyList() }, label = { Text(t) })
                    }
                }
            Composer(draft, { draft = it }, connected = status.state == LinkState.CONNECTED,
                onSchedule = { if (draft.isNotBlank() && pending.isEmpty()) scheduleAsk = true },
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
                    is Item.Bubble -> Bubble(item, core, group, selected = item.msg.id in picked,
                        onClick = {
                            val id = item.msg.id
                            when {
                                picked.isNotEmpty() -> picked = if (id in picked) picked - id else picked + id
                                item.msg.status == Msg.FAILED -> core.retry(item.msg)
                            }
                        },
                        onLongClick = { val id = item.msg.id; picked = if (id in picked) picked - id else picked + id },
                        onCopy = { copy(ctx, it) })
                }
            }
        }
    }
    details?.let { m -> DetailsDialog(m) { details = null } }
    if (scheduleAsk) ScheduleDialog(onPick = { t ->
        core.store.schedule(address, draft.trim(), t, core.simFor(address)); draft = ""; scheduleAsk = false
    }, onDismiss = { scheduleAsk = false })
}

/** "Tue 14:30" for today/this week, else with the date. */
private fun scheduleTime(t: Long): String =
    java.text.SimpleDateFormat(if (sameDay(t, System.currentTimeMillis())) "HH:mm" else "EEE d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))

@Composable
private fun DetailsDialog(m: Msg, onDismiss: () -> Unit) {
    val whenText = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(m.time))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.act_details)) },
        text = {
            Text(listOfNotNull(
                stringResource(if (m.att.isNotBlank()) R.string.details_mms else R.string.details_sms),
                (if (m.outgoing) stringResource(R.string.details_to) else stringResource(R.string.details_from)) + " " + m.address,
                whenText,
                if (m.sim > 0) "SIM ${m.sim}" else null,
                if (m.outgoing) stringResource(when (m.status) {
                    Msg.DELIVERED -> R.string.details_delivered; Msg.FAILED -> R.string.details_failed
                    Msg.SENDING -> R.string.sending; else -> R.string.details_sent
                }) else null,
            ).joinToString("\n"))
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

/** Pick when a text goes out: a few usual times, or any date and time. */
@Composable
private fun ScheduleDialog(onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    fun at(dayOffset: Int, hour: Int) = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
        set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
    }.timeInMillis
    val choices = listOf(now + 3_600_000L, at(0, 18), at(1, 8), at(1, 13)).filter { it > now + 60_000 }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Schedule, null) },
        title = { Text(stringResource(R.string.schedule_title)) },
        text = {
            Column {
                choices.forEach { t -> TextButton({ onPick(t) }) { Text(scheduleTime(t)) } }
                TextButton({
                    val c = java.util.Calendar.getInstance()
                    android.app.DatePickerDialog(ctx, { _, y, mo, d ->
                        android.app.TimePickerDialog(ctx, { _, h, mi ->
                            c.set(y, mo, d, h, mi, 0)
                            if (c.timeInMillis > System.currentTimeMillis()) onPick(c.timeInMillis)
                            else Toast.makeText(ctx, ctx.getString(R.string.schedule_past), Toast.LENGTH_SHORT).show()
                        }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), true).show()
                    }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show()
                }) { Text(stringResource(R.string.schedule_pick)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The message text with links, e-mail addresses and phone numbers that open, and codes that copy. */
@Composable
private fun linked(text: String, onCopy: (String) -> Unit): AnnotatedString {
    val copyNow by rememberUpdatedState(onCopy)
    return remember(text) {
        buildAnnotatedString {
            append(text)
            val styles = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
            io.github.simbridge.Linkify.spans(text).forEach { sp ->
                if (sp.kind == io.github.simbridge.Linkify.Kind.CODE)
                    addLink(LinkAnnotation.Clickable(tag = "code", styles = styles, linkInteractionListener = { copyNow(sp.target) }), sp.start, sp.end)
                else addLink(LinkAnnotation.Url(sp.target, styles), sp.start, sp.end)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(item: Item.Bubble, core: LinkCore, group: Boolean, selected: Boolean,
                   onClick: () -> Unit, onLongClick: () -> Unit, onCopy: (String) -> Unit) {
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
        Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .padding(top = if (item.joinAbove) 2.dp else 8.dp),
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
                modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick),
            ) {
                Text(linked(m.body, onCopy), Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (item.showStatus || failed || m.starred) {
            val label = (if (m.starred) "★ " else "") + when {
                failed -> stringResource(R.string.not_sent_retry)
                m.status == Msg.SENDING -> stringResource(R.string.sending)
                m.status == Msg.DELIVERED -> "Delivered · ${clockTime(m.time)}"
                m.outgoing -> "Sent · ${clockTime(m.time)}"
                else -> clockTime(m.time)
            } + (if (m.sim > 0) " · SIM ${m.sim}" else "")
            Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Composer(text: String, onText: (String) -> Unit, connected: Boolean, onSchedule: () -> Unit,
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
                        Icon(Icons.Filled.Close, stringResource(R.string.remove), Modifier.size(14.dp))
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onAttach, Modifier.padding(bottom = 4.dp)) { Icon(Icons.Filled.AddPhotoAlternate, stringResource(R.string.add_picture)) }
            TextField(
                value = text, onValueChange = onText,
                placeholder = { Text(if (connected) stringResource(R.string.text_hint) else stringResource(R.string.text_hint_offline)) },
                shape = RoundedCornerShape(28.dp), maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.weight(1f),
            )
            // Tap sends; hold to choose a later time.
            val canSend = text.isNotBlank() || pending.isNotEmpty()
            Box(Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                .combinedClickable(enabled = canSend, onClick = onSend, onLongClick = onSchedule), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send),
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
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
        if (bmp != null) androidx.compose.foundation.Image(bmp, stringResource(R.string.picture),
            Modifier.padding(vertical = 2.dp).widthIn(max = 260.dp).clip(RoundedCornerShape(18.dp))
                .clickable { open() }, contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
        return
    }
    if (a.type.startsWith("audio/")) {
        // Voicemail and voice notes play right here.
        var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
        androidx.compose.runtime.DisposableEffect(a) { onDispose { player?.release() } }
        androidx.compose.material3.AssistChip(onClick = {
            val p = player
            if (p != null) { p.release(); player = null }
            else player = runCatching {
                android.media.MediaPlayer().apply {
                    setDataSource(io.github.simbridge.Mms.fileOf(ctx, a).path); prepare(); start()
                    setOnCompletionListener { it.release(); player = null }
                }
            }.getOrNull()
        }, label = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(if (player != null) Icons.Filled.Stop else Icons.Filled.PlayArrow, null) },
            modifier = Modifier.padding(vertical = 2.dp))
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
    Toast.makeText(ctx, ctx.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}

fun call(ctx: android.content.Context, number: String) {
    val granted = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    val i = Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
