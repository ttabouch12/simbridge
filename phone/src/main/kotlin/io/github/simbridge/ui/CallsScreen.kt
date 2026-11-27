package io.github.simbridge.ui

import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.simbridge.LinkCore
import io.github.simbridge.MessageStore
import io.github.simbridge.MissedCallLog
import io.github.simbridge.Msg
import io.github.simbridge.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Calls: recent calls (this phone's log, which every relayed call lands in), voicemails, and the
 * call features. Dialing opens the phone app with SimBridge's calling account.
 */
@Composable
fun CallsScreen(core: LinkCore, onTool: (Tool) -> Unit, onSettings: (Int) -> Unit, onBlocked: () -> Unit, onChat: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val version by core.store.version.collectAsState()
    val st by core.control.status.collectAsState()
    var calls by remember { mutableStateOf(emptyList<MissedCallLog.Entry>()) }
    var voicemails by remember { mutableStateOf(emptyList<Msg>()) }
    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            calls = MissedCallLog.all(ctx, 40)
            voicemails = core.store.voicemails(10)
        }
    }
    val own = remember { io.github.simbridge.Telecom.selfManaged(ctx) }
    var dialing by remember { mutableStateOf(false) }
    fun dial(number: String) {
        if (own) io.github.simbridge.Telecom.call(ctx, number)
        else runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))) }
    }
    if (dialing) DialDialog(onCall = { dial(it) }, onDismiss = { dialing = false })

    Box(Modifier.fillMaxSize()) {
        PageScaffold(stringResource(R.string.tab_calls), onBack = null) {
            LastCallCard(core)
            Group(stringResource(R.string.calls_features), listOf(
                { GroupRow(Icons.Filled.Voicemail, stringResource(R.string.tool_calls),
                    stringResource(if (st?.vmOn == true) R.string.home_vm_on else R.string.home_vm_off), Tint.Green, { onTool(Tool.CALLS) }) },
                { GroupRow(Icons.Filled.Translate, stringResource(R.string.calls_translate), stringResource(R.string.calls_translate_sub), Tint.Blue, { onSettings(1) }) },
                { GroupRow(Icons.Filled.NightsStay, stringResource(R.string.quiet_title),
                    stringResource(if (core.prefs.quietOn) R.string.on else R.string.off), Tint.Purple, { onSettings(1) }) },
                { GroupRow(Icons.Filled.Block, stringResource(R.string.calls_blocked), null, Tint.Red, onBlocked) },
            ))
            if (voicemails.isNotEmpty()) Group(stringResource(R.string.calls_voicemails), voicemails.map { m ->
                {
                    GroupRow(Icons.Filled.Voicemail, core.contacts.lookup(m.address)?.name ?: m.address,
                        m.body.substringAfter('\n', "").ifBlank { m.body } + " · " + shortTime(m.time), Tint.Green,
                        { onChat(MessageStore.keyOf(m.address), m.address) })
                }
            })
            if (calls.isEmpty()) {
                GroupLabel(stringResource(R.string.calls_recent))
                Muted(stringResource(R.string.calls_none))
            } else Group(stringResource(R.string.calls_recent), calls.map { c ->
                {
                    val (icon, tint) = when (c.type) {
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> Icons.AutoMirrored.Filled.CallMissed to Status.bad()
                        CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Filled.CallMade to Tint.Blue
                        else -> Icons.AutoMirrored.Filled.CallReceived to Status.ok()
                    }
                    GroupRow(icon, c.name ?: c.number.ifBlank { stringResource(R.string.missed_hidden) },
                        shortTime(c.at) + if (c.durationSec > 0) " · " + duration((c.durationSec / 60).toInt().coerceAtLeast(1)) else "",
                        tint, onClick = if (c.number.isNotBlank()) ({ dial(c.number) }) else null, opens = false,
                        trailing = if (c.number.isNotBlank()) ({
                            IconButton({ onChat(MessageStore.keyOf(c.number), c.number) }) {
                                Icon(Icons.AutoMirrored.Filled.Message, stringResource(R.string.message), tint = MaterialTheme.colorScheme.primary)
                            }
                        }) else null)
                }
            })
            VSpace(80.dp)
        }
        ExtendedFloatingActionButton(
            onClick = { if (own) dialing = true else runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL)) } },
            icon = { Icon(Icons.Filled.Dialpad, null) }, text = { Text(stringResource(R.string.calls_dial)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}
