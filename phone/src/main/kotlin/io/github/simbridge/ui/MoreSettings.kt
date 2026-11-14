package io.github.simbridge.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.simbridge.LinkCore
import io.github.simbridge.R
import io.github.simbridge.Rules

private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

/** Settings > Messages: spam filter and rules for who notifies, stays silent, is archived or blocked. */
@Composable
fun RulesCard(core: LinkCore) {
    val prefs = core.prefs
    var spam by remember { mutableStateOf(prefs.spamFilter) }
    var rules by remember { mutableStateOf(Rules.load(prefs.rules)) }
    var adding by remember { mutableStateOf(false) }
    fun save(l: List<Rules.Rule>) { rules = l; prefs.rules = Rules.save(l) }
    Card(Icons.Filled.FilterAlt, stringResource(R.string.rules_title), stringResource(R.string.rules_text), ok = true) {
        Column {
            SwitchRow(stringResource(R.string.rules_spam), spam) { spam = it; prefs.spamFilter = it }
            rules.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull(r.from.ifBlank { null }, r.word.ifBlank { null }?.let { "“$it”" }, if (r.sim > 0) "SIM ${r.sim}" else null)
                        .joinToString(" · ").ifEmpty { stringResource(R.string.rules_anyone) } + "  →  " + stringResource(actionLabel(r.action)),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton({ save(rules - r) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
                }
            }
            TextButton({ adding = true }) { Text(stringResource(R.string.rules_add)) }
        }
    }
    if (adding) {
        var from by remember { mutableStateOf("") }
        var word by remember { mutableStateOf("") }
        var sim by remember { mutableStateOf(0) }
        var action by remember { mutableStateOf(Rules.Action.SILENT) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.rules_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(from, { from = it }, singleLine = true, label = { Text(stringResource(R.string.rules_from)) })
                    OutlinedTextField(word, { word = it }, singleLine = true, label = { Text(stringResource(R.string.rules_word)) })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 1, 2).forEach { n -> FilterChip(sim == n, { sim = n }, label = { Text(if (n == 0) stringResource(R.string.rules_any_sim) else "SIM $n") }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Rules.Action.entries.forEach { a -> FilterChip(action == a, { action = a }, label = { Text(stringResource(actionLabel(a))) }) }
                    }
                }
            },
            confirmButton = {
                TextButton({ save(rules + Rules.Rule(from.trim(), word.trim(), sim, action)); adding = false },
                    enabled = from.isNotBlank() || word.isNotBlank()) { Text(stringResource(R.string.add)) }
            },
            dismissButton = { TextButton({ adding = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun actionLabel(a: Rules.Action) = when (a) {
    Rules.Action.ALERT -> R.string.rules_alert
    Rules.Action.SILENT -> R.string.rules_silent
    Rules.Action.ARCHIVE -> R.string.act_archive
    Rules.Action.BLOCK -> R.string.block
}

/** Settings > Calls: quiet hours and the VIP contacts who still get through. */
@Composable
fun QuietCard(core: LinkCore) {
    val ctx = LocalContext.current
    val prefs = core.prefs
    var on by remember { mutableStateOf(prefs.quietOn) }
    var start by remember { mutableStateOf(prefs.quietStart) }
    var end by remember { mutableStateOf(prefs.quietEnd) }
    var vip by remember { mutableStateOf(prefs.vip) }
    var adding by remember { mutableStateOf(false) }
    fun pick(m: Int, set: (Int) -> Unit) = TimePickerDialog(ctx, { _, h, mi -> set(h * 60 + mi) }, m / 60, m % 60, true).show()
    Card(Icons.Filled.NightsStay, stringResource(R.string.quiet_title), stringResource(R.string.quiet_text), ok = true) {
        Column {
            SwitchRow(stringResource(R.string.quiet_on), on) { on = it; prefs.quietOn = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ pick(start) { start = it; prefs.quietStart = it } }) { Text(stringResource(R.string.quiet_from, hm(start))) }
                TextButton({ pick(end) { end = it; prefs.quietEnd = it } }) { Text(stringResource(R.string.quiet_to, hm(end))) }
            }
            Text(stringResource(R.string.quiet_vip), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            vip.forEach { n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(core.contacts.lookup(n)?.name ?: n, Modifier.weight(1f))
                    IconButton({ vip = vip - n; prefs.vip = vip }) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
                }
            }
            TextButton({ adding = true }) { Text(stringResource(R.string.quiet_add_vip)) }
        }
    }
    if (adding) {
        var n by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.quiet_add_vip)) },
            text = { OutlinedTextField(n, { n = it }, singleLine = true, label = { Text(stringResource(R.string.quiet_number)) }) },
            confirmButton = { TextButton({ vip = vip + n.trim(); prefs.vip = vip; adding = false }, enabled = n.count { it.isDigit() } >= 3) { Text(stringResource(R.string.add)) } },
            dismissButton = { TextButton({ adding = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Settings > App: the fingerprint lock, and the list of changes made on the SIM phone. */
@Composable
fun SecurityExtrasCard(core: LinkCore) {
    val ctx = LocalContext.current
    var lock by remember { mutableStateOf(core.prefs.lockControls) }
    var showLog by remember { mutableStateOf(false) }
    val audit by core.control.audit.collectAsState()
    Card(Icons.Filled.Lock, stringResource(R.string.guard_card), stringResource(R.string.guard_card_text), ok = true) {
        Column {
            SwitchRow(stringResource(R.string.guard_switch), lock) { lock = it; core.prefs.lockControls = it }
            TextButton({ core.control.loadAudit(); showLog = true }) {
                Icon(Icons.Filled.History, null); Text("  " + stringResource(R.string.audit_title))
            }
        }
    }
    if (showLog) AlertDialog(
        onDismissRequest = { showLog = false },
        title = { Text(stringResource(R.string.audit_title)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                if (audit.isEmpty()) item { Text(stringResource(R.string.audit_none)) }
                items(audit.reversed().size) { i ->
                    val e = audit.reversed()[i]
                    Text(android.text.format.DateUtils.formatDateTime(ctx, e.at,
                        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME) + "  " + e.what +
                        (if (e.from.isNotBlank()) "  ·  " + e.from else ""), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
                }
            }
        },
        confirmButton = { TextButton({ showLog = false }) { Text(stringResource(R.string.ok)) } },
    )
}
