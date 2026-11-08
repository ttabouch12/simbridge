package io.github.simbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.simbridge.LinkCore
import io.github.simbridge.LinkState
import kotlinx.coroutines.delay

/** Live view of the SIM phone, plus the switches the main phone may flip on it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimPhoneScreen(core: LinkCore, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st by core.control.status.collectAsState()
    val link by core.status.collectAsState()
    val ussd by core.control.ussd.collectAsState()
    val notice by core.control.notice.collectAsState()
    val online = link.state == LinkState.CONNECTED
    var code by remember { mutableStateOf("") }
    var ussdSim by remember { mutableStateOf<Int?>(null) }
    var waitingUssd by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(online) { while (online) { core.control.refresh(); delay(30_000) } }
    LaunchedEffect(ussd) { if (ussd != null) waitingUssd = false }
    LaunchedEffect(notice) { notice?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show(); core.control.clearNotice() } }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("SIM phone") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton({ core.control.refresh() }, enabled = online) { Icon(Icons.Filled.Refresh, "Refresh") } },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!online) Text("Not connected to the SIM phone.", color = MaterialTheme.colorScheme.error)
            val s = st
            Section(Icons.Filled.SignalCellularAlt, "Network") {
                if (s == null) Text("Waiting for the SIM phone…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (s.sims.isEmpty()) Text("No SIM reported (the gateway needs the Phone permission).")
                else s.sims.forEach { sim ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Bars(sim.level)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(listOf(if (s.sims.size > 1) "SIM ${sim.slot}" else "", sim.carrier.ifBlank { sim.name })
                                .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
                            Text(listOf(sim.network, if (sim.dbm != 0 && sim.dbm < 0) "${sim.dbm} dBm" else "",
                                if (sim.roaming) "roaming" else "").filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (s != null && s.sims.size > 1) Section(Icons.Filled.SignalCellularAlt, "New texts go out on") {
                var def by remember { mutableStateOf(core.prefs.outgoingSim) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(def == 0, { def = 0; core.prefs.outgoingSim = 0 }, label = { Text("SIM phone's default") })
                    s.sims.forEach { sim -> FilterChip(def == sim.slot, { def = sim.slot; core.prefs.outgoingSim = sim.slot }, label = { Text("SIM ${sim.slot}") }) }
                }
                Text("Replies use the SIM the conversation came in on. For calls, turn on the SIM 1 and SIM 2 accounts in the Phone app's calling accounts.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            if (s != null) Section(Icons.Filled.BatteryChargingFull, "Battery") {
                val pct = s.battery
                Text(if (pct == null) "Unknown" else "$pct%${if (s.charging) " · charging" else ""}", style = MaterialTheme.typography.bodyLarge)
                if (pct != null) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator({ pct / 100f }, Modifier.fillMaxWidth(),
                        color = if (pct <= 20 && !s.charging) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(6.dp))
                Text("Up ${uptime(s.uptimeMin)} · Gateway ${s.version}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section(Icons.Filled.SettingsRemote, "Remote control") {
                ToggleRow("Battery saver", "Restricts other apps and quiets the SIM phone", s?.powerSaver, online) { core.control.control("power-saver", it) }
                ToggleRow("Mobile data", "Data for the hotspot and the SIM phone", s?.dataOn, online) { confirm = "mobile-data" to it }
                ToggleRow("Hotspot (experimental)", "Some phones don't allow this remotely", s?.hotspot, online) { confirm = "hotspot" to it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button({ core.control.control("find", true) }, enabled = online) { Text("Ring it") }
                    OutlinedButton({ core.control.control("find", false) }, enabled = online) { Text("Stop") }
                    OutlinedButton({ core.control.control("restart", true) }, enabled = online) { Text("Restart link") }
                }
            }
            Section(Icons.Filled.Dialpad, "USSD codes") {
                Text("Check balance, data left and other carrier codes, like *100#.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if ((s?.sims?.size ?: 0) > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s!!.sims.forEach { sim -> FilterChip(ussdSim == sim.slot, { ussdSim = if (ussdSim == sim.slot) null else sim.slot },
                        label = { Text("SIM ${sim.slot}") }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(code, { v -> code = v.filter { it.isDigit() || it in "*#+" } }, singleLine = true,
                        label = { Text("Code") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    Spacer(Modifier.width(8.dp))
                    Button({ waitingUssd = core.control.ussd(code, ussdSim) }, enabled = online && code.endsWith("#") && !waitingUssd) { Text("Send") }
                }
                if (waitingUssd) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                ussd?.let { r ->
                    Spacer(Modifier.height(8.dp))
                    Text(r.text, style = MaterialTheme.typography.bodyLarge,
                        color = if (r.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    confirm?.let { (action, on) ->
        val name = if (action == "hotspot") "hotspot" else "mobile data"
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Turn ${if (on) "on" else "off"} $name?") },
            text = { Text(if (!on) "If this phone reaches the SIM phone through that $name, you'll lose the connection until it is turned back on by hand." else "The SIM phone will turn on its $name.") },
            confirmButton = { TextButton({ core.control.control(action, on); confirm = null }) { Text(if (on) "Turn on" else "Turn off") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}

private fun uptime(min: Long) = when {
    min < 60 -> "${min}m"
    min < 48 * 60 -> "${min / 60}h ${min % 60}m"
    else -> "${min / 1440}d"
}

@Composable
private fun Section(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, value: Boolean?, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value == true, onChange, enabled = enabled && value != null)
    }
}

/** Four signal bars; [level] 0..4 (-1 unknown). */
@Composable
private fun Bars(level: Int) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            androidx.compose.foundation.layout.Box(
                Modifier.width(5.dp).height((5 + i * 4).dp)
                    .background(if (level >= i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.dp)),
            )
        }
    }
}

