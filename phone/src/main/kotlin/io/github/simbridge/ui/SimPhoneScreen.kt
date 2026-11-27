package io.github.simbridge.ui

import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.filled.Notifications
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.Settings
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.simbridge.GatewayStatus
import io.github.simbridge.LinkCore
import io.github.simbridge.LinkState
import io.github.simbridge.R
import kotlinx.coroutines.delay

/** The pages one level below the SIM phone home. */
enum class Tool(val title: Int) {
    USSD(R.string.dash_ussd), DATA(R.string.dash_data), NETWORK(R.string.tool_network), HOTSPOT(R.string.tool_hotspot),
    LOCATE(R.string.dash_locate), SPEED(R.string.speed_title), BATTERY(R.string.tool_battery), APPS(R.string.notif_title),
    CALLS(R.string.tool_calls), CHECK(R.string.chk_title),
}

/**
 * The SIM phone: its state at a glance, switches as tiles, and everything else one tap away.
 * With [tool] set, shows that page instead (opened from the home's lists).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SimPhoneScreen(core: LinkCore, tool: Tool? = null, onTool: (Tool?) -> Unit = {}, onRemote: () -> Unit = {}) {
    val pending by core.control.pending.collectAsState()
    val ctx = LocalContext.current
    val st by core.control.status.collectAsState()
    val link by core.status.collectAsState()
    val ussd by core.control.ussd.collectAsState()
    val notice by core.control.notice.collectAsState()
    val apns by core.control.apns.collectAsState()
    val loc by core.control.location.collectAsState()
    val online = link.state == LinkState.CONNECTED
    var code by remember { mutableStateOf("") }
    var ussdSim by remember { mutableStateOf<Int?>(null) }
    var waitingUssd by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var addApn by remember { mutableStateOf(false) }
    val guard = rememberGuard(core)
    LaunchedEffect(online) { while (online) { core.control.refresh(); delay(30_000) } }
    LaunchedEffect(ussd) { if (ussd != null) waitingUssd = false }
    LaunchedEffect(loc) { if (loc != null) locating = false }
    LaunchedEffect(notice) { notice?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show(); core.control.clearNotice() } }
    val s = st

    if (tool == null) PageScaffold(stringResource(R.string.tab_sim_phone), onBack = null, actions = {
        IconButton({ core.control.refresh() }, enabled = online) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
    }) {
        Hero(s, online, link.detail)
        Group(null, listOf { GroupRow(Icons.Filled.HealthAndSafety, stringResource(R.string.chk_title), stringResource(R.string.chk_sub), Tint.Blue, { onTool(Tool.CHECK) }) })
        GroupLabel(stringResource(R.string.home_controls))
        TileGrid(listOf(
            { m -> Tile(Icons.Filled.SignalCellularAlt, stringResource(R.string.dash_mobile_data), onOff(pending["mobile-data"] as Boolean? ?: s?.dataOn),
                pending["mobile-data"] as Boolean? ?: s?.dataOn == true, m, busy = pending.containsKey("mobile-data"), enabled = online,
                onLongClick = { onTool(Tool.DATA) }) { confirm = "mobile-data" to (s?.dataOn != true) } },
            { m -> Tile(Icons.Filled.WifiTethering, stringResource(R.string.hs_title),
                if (s?.hotspot == true) stringResource(R.string.home_clients, s.clients.size) else onOff(false),
                pending["hotspot"] as Boolean? ?: s?.hotspot == true, m, busy = pending.containsKey("hotspot"), enabled = online,
                onLongClick = { onTool(Tool.HOTSPOT) }) { confirm = "hotspot" to (s?.hotspot != true) } },
            { m -> Tile(Icons.Filled.BatterySaver, stringResource(R.string.home_saver), onOff(pending["power-saver"] as Boolean? ?: s?.powerSaver),
                pending["power-saver"] as Boolean? ?: s?.powerSaver == true, m, busy = pending.containsKey("power-saver"), enabled = online,
                onLongClick = { onTool(Tool.BATTERY) }) { val on = s?.powerSaver != true; guard { core.control.control("power-saver", on) } } },
            { m -> Tile(Icons.AutoMirrored.Filled.ScreenShare, stringResource(R.string.home_remote), stringResource(R.string.home_remote_sub), false, m,
                enabled = online) { guard(onRemote) } },
            { m -> Tile(Icons.Filled.NotificationsActive, stringResource(R.string.dash_ring), stringResource(R.string.home_ring_sub), false, m,
                busy = pending.containsKey("find"), enabled = online, onLongClick = { core.control.control("find", false) }) { core.control.control("find", true) } },
            { m -> Tile(Icons.Filled.RestartAlt, stringResource(R.string.dash_restart), stringResource(R.string.home_restart_sub), false, m,
                busy = pending.containsKey("restart"), enabled = online) { core.control.control("restart", true) } },
        ))
        val sims = s?.sims.orEmpty()
        Group(stringResource(R.string.home_tools), listOf(
            { GroupRow(Icons.Filled.Dialpad, stringResource(R.string.dash_ussd), stringResource(R.string.home_ussd_sub), Tint.Teal, { onTool(Tool.USSD) }, enabled = online) },
            { GroupRow(Icons.Filled.DataUsage, stringResource(R.string.dash_data),
                s?.dataToday?.let { stringResource(R.string.home_data_sub, bytes(it), bytes(s.dataMonth ?: 0)) }, Tint.Purple, { onTool(Tool.DATA) }) },
            { GroupRow(Icons.Filled.SimCard, stringResource(R.string.tool_network),
                sims.joinToString(" · ") { "SIM ${it.slot} " + it.carrier.ifBlank { it.name } + " " + it.network }.ifBlank { null }, Tint.Blue, { onTool(Tool.NETWORK) }) },
            { GroupRow(Icons.Filled.Wifi, stringResource(R.string.tool_hotspot), stringResource(R.string.places_sub), Tint.Teal, { onTool(Tool.HOTSPOT) }) },
            { GroupRow(Icons.Filled.LocationOn, stringResource(R.string.dash_locate), stringResource(R.string.dash_locate_sub), Tint.Red, { onTool(Tool.LOCATE) }, enabled = online) },
            { GroupRow(Icons.Filled.Speed, stringResource(R.string.speed_title), stringResource(R.string.home_speed_sub), Tint.Orange, { onTool(Tool.SPEED) }) },
        ))
        Group(stringResource(R.string.home_more), listOf(
            { GroupRow(Icons.Filled.Voicemail, stringResource(R.string.tool_calls),
                stringResource(if (s?.vmOn == true) R.string.home_vm_on else R.string.home_vm_off), Tint.Green, { onTool(Tool.CALLS) }) },
            { GroupRow(Icons.Filled.BatteryChargingFull, stringResource(R.string.tool_battery),
                s?.chargeLimit?.let { if (it == "off") null else stringResource(R.string.home_limit, it) } ?: stringResource(R.string.dash_charge_limit_sub), Tint.Green, { onTool(Tool.BATTERY) }) },
            { GroupRow(Icons.Filled.Notifications, stringResource(R.string.notif_title), stringResource(R.string.notif_sub), Tint.Purple, { onTool(Tool.APPS) }) },
        ))
    } else PageScaffold(stringResource(tool.title), onBack = { onTool(null) }, scroll = tool != Tool.SPEED) {
        when (tool) {
            Tool.USSD -> {
        Section(Icons.Filled.Dialpad, stringResource(R.string.dash_ussd), Tint.Teal) {
            Muted(stringResource(R.string.dash_ussd_sub))
            Spacer(Modifier.height(8.dp))
            if ((s?.sims?.size ?: 0) > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s!!.sims.forEach { sim -> FilterChip(ussdSim == sim.slot, { ussdSim = if (ussdSim == sim.slot) null else sim.slot },
                    label = { Text("SIM ${sim.slot}") }) }
            }
            // The network's answer first, like the phone's own USSD dialog; menus can be answered.
            val inMenu = ussd?.session == true
            ussd?.let { r ->
                androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(r.text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge,
                        color = if (r.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                }
            }
            // Own dial pad: no system keyboard covering the field, and always the phone layout.
            Text(code.ifEmpty { if (inMenu) "1" else "*100#" }, style = MaterialTheme.typography.headlineMedium,
                color = if (code.isEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            DialPad({ if (code.length < 40) code += it }, { code = code.dropLast(1) }, { code = "" })
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (inMenu) OutlinedButton({ core.control.ussdCancel(); code = "" }, Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                Button({
                    val c = code
                    guard { waitingUssd = if (inMenu) core.control.ussdReply(c) else core.control.ussd(c, ussdSim) }
                    code = ""
                }, enabled = online && !waitingUssd && (if (inMenu) code.isNotEmpty() else code.endsWith("#")),
                    modifier = Modifier.weight(1f)) { Text(stringResource(if (inMenu) R.string.ussd_reply else R.string.send)) }
            }
            if (waitingUssd) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }            }
            Tool.DATA -> {
                DataLimitCard(core, s, online)
        if (s != null) Section(Icons.Filled.DataUsage, stringResource(R.string.dash_data), Tint.Purple) {
            if (s.dataToday == null) Muted(stringResource(R.string.dash_data_unknown))
            else {
                Row {
                    Stat(stringResource(R.string.dash_today), bytes(s.dataToday), Modifier.weight(1f))
                    Stat(stringResource(R.string.dash_month), bytes(s.dataMonth ?: 0), Modifier.weight(1f))
                }
                val detail by core.control.dataDetail.collectAsState()
                var open by remember { mutableStateOf(false) }
                TextButton({ open = !open; if (open) core.control.loadDataDetail() }, enabled = online) {
                    Text(stringResource(if (open) R.string.dash_data_hide else R.string.dash_data_details))
                }
                if (open) detail?.let { d -> DataChart(d) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

            }
            Tool.NETWORK -> {
                PhonesCard(s)
        Section(Icons.Filled.SimCard, stringResource(R.string.dash_sims), Tint.Blue) {
            if (s == null) Muted(stringResource(R.string.dash_waiting))
            else if (s.sims.isEmpty()) Muted(stringResource(R.string.dash_no_sim))
            else s.sims.forEachIndexed { i, sim ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Bars(sim.level)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SIM ${sim.slot}" + (if (sim.esim) " (eSIM)" else "") + " · " + sim.carrier.ifBlank { sim.name },
                            style = MaterialTheme.typography.bodyLarge)
                        Muted(listOf(sim.number, sim.network, if (sim.dbm < 0) "${sim.dbm} dBm" else "",
                            if (sim.roaming) stringResource(R.string.dash_roaming) else "").filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Muted(stringResource(R.string.dash_network_mode))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("5g", "4g", "3g", "2g").forEach { m ->
                        val want = pending["net:${sim.slot}"]
                        FilterChip((want ?: sim.mode) == m, { core.control.networkMode(sim.slot, m) }, enabled = online,
                            label = { Text(m.uppercase()) },
                            trailingIcon = if (want == m) { { Spinner() } } else null)
                    }
                }
            }
            if (s != null && s.sims.size > 1) {
                Spacer(Modifier.height(12.dp))
                var def by remember { mutableStateOf(core.prefs.outgoingSim) }
                Muted(stringResource(R.string.dash_text_sim))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(def == 0, { def = 0; core.prefs.outgoingSim = 0 }, label = { Text(stringResource(R.string.dash_default)) })
                    s.sims.forEach { sim -> FilterChip(def == sim.slot, { def = sim.slot; core.prefs.outgoingSim = sim.slot }, label = { Text("SIM ${sim.slot}") }) }
                }
            }
        }

        Section(Icons.Filled.SettingsInputAntenna, stringResource(R.string.dash_apn), Tint.Blue) {
            Muted(stringResource(R.string.dash_apn_sub))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton({ core.control.loadApns() }, enabled = online) { Text(stringResource(R.string.dash_apn_load)) }
                TextButton({ addApn = true }, enabled = online) { Text(stringResource(R.string.dash_apn_add)) }
            }
            apns?.let { list ->
                if (list.isEmpty()) Muted(stringResource(R.string.dash_apn_none))
                list.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(a.preferred, { core.control.selectApn(a.id) }, enabled = online)
                        Column {
                            Text(a.name.ifBlank { a.apn }, style = MaterialTheme.typography.bodyLarge)
                            Muted("${a.apn} · ${a.type}")
                        }
                    }
                }
            }
        }

            }
            Tool.HOTSPOT -> {
                // The hotspot radio: 5 GHz is several times faster at phone distance; 2.4 GHz reaches further.
                Group(stringResource(R.string.hs_radio), listOf(
                    {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.hs_band), style = MaterialTheme.typography.titleMedium)
                            Muted(stringResource(R.string.hs_band_sub) + (if ((s?.apChannel ?: 0) > 0) " · " + stringResource(R.string.hs_channel, s!!.apChannel) else ""))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                listOf(5 to "5 GHz", 2 to "2.4 GHz", 0 to stringResource(R.string.hs_auto)).forEach { (b, label) ->
                                    FilterChip(s?.apBand == b, { guard { core.control.setHotspotBand(b) } }, enabled = online, label = { Text(label) })
                                }
                            }
                        }
                    },
                    {
                        GroupRow(null, stringResource(R.string.hs_offload), stringResource(R.string.hs_offload_sub), opens = false, trailing = {
                            androidx.compose.material3.Switch(s?.offload != false, { on -> guard { core.control.setTetherOffload(on) } }, enabled = online)
                        })
                    },
                ))
                HotspotSmartCard(core, s, online)
                LinkHealth(core, s)
                PlacesSection(core, online)
        if (s != null) Section(Icons.Filled.Devices, stringResource(R.string.dash_hotspot_clients, s.clients.size), Tint.Teal) {
            if (!s.hotspot) Muted(stringResource(R.string.dash_hotspot_off))
            else if (s.clients.isEmpty()) Muted(stringResource(R.string.dash_no_clients))
            else s.clients.forEach { c ->
                Text("${c.ip}  ·  …${c.mac}" + if (c.simbridge) "  ·  SimBridge" else "", style = MaterialTheme.typography.bodyMedium)
            }
        }

            }
            Tool.LOCATE -> {
        Section(Icons.Filled.LocationOn, stringResource(R.string.dash_locate), Tint.Red) {
            Muted(stringResource(R.string.dash_locate_sub))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button({ locating = core.control.locate() }, enabled = online && !locating) { Text(stringResource(R.string.dash_locate_now)) }
                if (locating) { Spacer(Modifier.width(12.dp)); LinearProgressIndicator(Modifier.weight(1f)) }
            }
            loc?.let { l ->
                Spacer(Modifier.height(8.dp))
                if (!l.ok) Text(stringResource(R.string.dash_locate_failed), color = MaterialTheme.colorScheme.error)
                else {
                    OsmMap(l.lat, l.lon, l.accuracy)
                    Spacer(Modifier.height(6.dp))
                    Text("%.5f, %.5f  ±%.0f m".format(l.lat, l.lon, l.accuracy), style = MaterialTheme.typography.bodyLarge)
                    TextButton({
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${l.lat},${l.lon}?q=${l.lat},${l.lon}(SIM phone)"))) }
                    }) { Text(stringResource(R.string.dash_open_map)) }
                }
            }
        }

            }
            Tool.SPEED -> SpeedTest(core, online)
            Tool.CHECK -> DiagnosticsPage(core, s, online, onTool)
            Tool.BATTERY -> {
                NightModeCard(core, s, online)
        // Samsung battery protection: stop charging at a level, for a phone that is always plugged in.
        Section(Icons.Filled.BatteryChargingFull, stringResource(R.string.dash_charge_limit), Tint.Green) {
            Muted(stringResource(R.string.dash_charge_limit_sub))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("off", "80", "85", "90", "95").forEach { v ->
                    FilterChip(s?.chargeLimit == v, { guard { core.control.setChargeLimit(v) } }, enabled = online,
                        label = { Text(if (v == "off") stringResource(R.string.off) else "$v %") })
                }
            }
            ToggleRow(stringResource(R.string.dash_report), stringResource(R.string.dash_report_sub), s?.dailyReport, online, null) {
                core.control.setDailyReport(it)
            }
        }
            }
            Tool.APPS -> AppNotifSection(core, online)
            Tool.CALLS -> {
        Section(Icons.Filled.Call, stringResource(R.string.dash_calls), Tint.Green) {
            Muted(stringResource(R.string.dash_voicemail_sub))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf(0, 15, 25, 40).forEach { sec ->
                    FilterChip(s?.noAnswerSec == sec, { core.control.setNoAnswer(sec) }, enabled = online,
                        label = { Text(if (sec == 0) stringResource(R.string.off) else "$sec s") })
                }
            }
            // SimBridge's own voicemail and call screening, run by the SIM phone.
            ToggleRow(stringResource(R.string.vm_title), stringResource(R.string.vm_sub), s?.vmOn, online, null) {
                core.control.setMachine(voicemail = it)
            }
            Muted(stringResource(R.string.screen_sub))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(0 to R.string.off, 1 to R.string.screen_unknown, 2 to R.string.screen_hidden).forEach { (m, label) ->
                    FilterChip(s?.screenMode == m, { core.control.setMachine(screen = m) }, enabled = online, label = { Text(stringResource(label)) })
                }
            }
            var editPrompts by remember { mutableStateOf(false) }
            TextButton({ editPrompts = true }, enabled = online && s != null) { Text(stringResource(R.string.vm_edit)) }
            if (editPrompts && s != null) {
                var g by remember { mutableStateOf(s.greeting) }
                var q by remember { mutableStateOf(s.screenPrompt) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { editPrompts = false },
                    title = { Text(stringResource(R.string.vm_edit)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.OutlinedTextField(g, { g = it.take(300) }, label = { Text(stringResource(R.string.vm_greeting)) })
                            androidx.compose.material3.OutlinedTextField(q, { q = it.take(300) }, label = { Text(stringResource(R.string.screen_prompt)) })
                        }
                    },
                    confirmButton = { TextButton({ core.control.setMachine(greeting = g, screenPrompt = q); editPrompts = false }) { Text(stringResource(R.string.save)) } },
                    dismissButton = { TextButton({ editPrompts = false }) { Text(stringResource(R.string.cancel)) } },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.dash_audio_test), style = MaterialTheme.typography.bodyLarge)
            Muted(stringResource(R.string.dash_audio_test_sub))
            var testNumber by remember(s?.voicemail) { mutableStateOf(s?.voicemail.orEmpty()) }
            val test by core.control.audioTest.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(testNumber, { v -> testNumber = v.filter { it.isDigit() || it in "+*#" } }, singleLine = true,
                    label = { Text(stringResource(R.string.dash_test_number)) }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                Spacer(Modifier.width(8.dp))
                Button({ core.control.runAudioTest(testNumber) }, enabled = online && testNumber.length >= 3 && test != "…") {
                    Text(stringResource(R.string.dash_run_test))
                }
            }
            test?.let { t ->
                Spacer(Modifier.height(8.dp))
                if (t == "…") LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                    Text(t, style = MaterialTheme.typography.bodyMedium)
                    TextButton({
                        val body = Uri.encode("Phone: ${s?.model}\nAndroid: ${s?.androidVersion}\nGateway: ${s?.version}\nResult: $t\n")
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/ttabouch12/simbridge/issues/new?labels=compatibility&title=" +
                                Uri.encode("Compatibility: ${s?.model}") + "&body=$body"))) }
                    }) { Text(stringResource(R.string.dash_share_result)) }
                }
            }
        }

            }
        }
    }
    confirm?.let { (action, on) ->
        val name = stringResource(if (action == "hotspot") R.string.dash_hotspot else R.string.dash_mobile_data)
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(if (on) R.string.dash_turn_on_q else R.string.dash_turn_off_q, name)) },
            text = { Text(stringResource(if (on) R.string.dash_turn_on_text else R.string.dash_turn_off_text)) },
            confirmButton = { TextButton({ guard { core.control.control(action, on) }; confirm = null }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (addApn) ApnDialog({ addApn = false }) { n, a, mcc, mnc, u, p -> core.control.addApn(n, a, mcc, mnc, u, p); addApn = false }
}

/** The SIM phone drawn as a phone, with its live battery and signal on the "screen". */
@Composable
private fun Hero(s: GatewayStatus?, online: Boolean, detail: String) {
    val pulse by rememberInfiniteTransition(label = "link").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "dot")
    val primary = MaterialTheme.colorScheme.primary
    val frame = MaterialTheme.colorScheme.onSurface
    val screenTop = MaterialTheme.colorScheme.primaryContainer
    val screenBottom = MaterialTheme.colorScheme.tertiaryContainer
    val off = MaterialTheme.colorScheme.outlineVariant
    androidx.compose.material3.Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 96.dp, height = 180.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = 18.dp.toPx()
                    drawRoundRect(frame, size = size, cornerRadius = CornerRadius(r))
                    val inset = 5.dp.toPx()
                    drawRoundRect(Brush.verticalGradient(listOf(if (online) screenTop else off, if (online) screenBottom else off)),
                        topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
                        cornerRadius = CornerRadius(r - inset))
                    drawCircle(frame, 3.dp.toPx(), Offset(size.width / 2, inset + 9.dp.toPx()))   // camera
                    val level = s?.sims?.maxOfOrNull { it.level } ?: 0
                    val w = 5.dp.toPx()
                    for (i in 1..4) {
                        val h = (4 + i * 4).dp.toPx()
                        drawRoundRect(if (level >= i) primary else off.copy(alpha = 0.6f),
                            topLeft = Offset(size.width / 2 - 2 * (w + 3) + (i - 1) * (w + 3), size.height * 0.42f - h),
                            size = Size(w, h), cornerRadius = CornerRadius(2f))
                    }
                    val bw = size.width * 0.46f; val bh = 16.dp.toPx()
                    val bx = (size.width - bw) / 2; val by = size.height * 0.58f
                    drawRoundRect(frame.copy(alpha = 0.7f), Offset(bx, by), Size(bw, bh), CornerRadius(4f), style = Stroke(2.dp.toPx()))
                    val pct = (s?.battery ?: 0) / 100f
                    drawRoundRect(if (pct < 0.2f && s?.charging != true) Color(0xFFD93025) else primary,
                        Offset(bx + 3.dp.toPx(), by + 3.dp.toPx()), Size((bw - 6.dp.toPx()) * pct, bh - 6.dp.toPx()), CornerRadius(3f))
                    if (online) drawCircle(primary.copy(alpha = 1f - pulse), 4.dp.toPx() + pulse * 10.dp.toPx(),
                        Offset(size.width / 2, size.height * 0.82f))
                }
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Text(s?.deviceName?.ifBlank { null } ?: s?.model?.ifBlank { null } ?: stringResource(R.string.dash_sim_phone), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                if (s != null && s.androidVersion.isNotBlank()) Muted("Android ${s.androidVersion} · Gateway ${s.version}")
                Spacer(Modifier.height(10.dp))
                Text(if (s?.battery != null) "${s.battery}%" else "—", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Muted(stringResource(if (s?.charging == true) R.string.dash_charging else R.string.dash_battery))
                // Time left from the SIM phone's real drain (or to a full charge).
                val mins = if (s?.charging == true) s.chargeMin else s?.batteryMin
                if (s?.charging == true && (mins == 0 || s.battery == 100)) Muted(stringResource(R.string.dash_full))
                else if (mins != null) Muted(stringResource(if (s?.charging == true) R.string.dash_full_in else R.string.dash_time_left, duration(mins)))
                Spacer(Modifier.height(10.dp))
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(if (online) stringResource(R.string.dash_connected) else detail.ifBlank { stringResource(R.string.dash_offline) },
                        if (online) Status.ok() else Status.bad())
                    // The SIM phone's own internet, from a ping to 1.1.1.1 / Google.
                    val ping = s?.pingMs
                    if (online && ping != null) StatusPill(
                        if (ping >= 0) stringResource(R.string.dash_internet_ms, ping) else stringResource(R.string.dash_no_internet),
                        if (ping < 0) Status.bad() else if (ping > 150) Status.warn() else Status.ok())
                }
                if (s != null) { Spacer(Modifier.height(6.dp)); Muted(stringResource(R.string.dash_uptime, uptime(s.uptimeMin))) }
            }
        }
    }
}

@Composable
private fun ApnDialog(onDismiss: () -> Unit, onAdd: (String, String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var apn by remember { mutableStateOf("") }
    var mcc by remember { mutableStateOf("") }; var mnc by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dash_apn_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.apn_name)) }, singleLine = true)
                OutlinedTextField(apn, { apn = it }, label = { Text("APN") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(mcc, { mcc = it.filter(Char::isDigit).take(3) }, label = { Text("MCC") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(mnc, { mnc = it.filter(Char::isDigit).take(3) }, label = { Text("MNC") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.apn_user)) }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.apn_password)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ onAdd(name, apn, mcc, mnc, user, pass) }, enabled = apn.isNotBlank() && mcc.length == 3 && mnc.length >= 2) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun bytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

private fun uptime(min: Long) = when {
    min < 60 -> "${min}m"
    min < 48 * 60 -> "${min / 60}h ${min % 60}m"
    else -> "${min / 1440}d"
}

@Composable
internal fun Muted(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Muted(label)
    }
}

/** Section colours, so each part of the dashboard is easy to spot. */
internal object Tint {
    val Blue = Color(0xFF1A73E8); val Green = Color(0xFF1E8E3E); val Orange = Color(0xFFE8710A)
    val Purple = Color(0xFF9334E6); val Teal = Color(0xFF12B5CB); val Red = Color(0xFFD93025)
}

@Composable
internal fun Spinner(color: Color = MaterialTheme.colorScheme.primary) =
    androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)

@Composable
internal fun Section(icon: ImageVector, title: String, tint: Color = Tint.Blue, content: @Composable () -> Unit) {
    // A flat, rounded panel in the surface tone: same look as the grouped lists (see Design.kt).
    androidx.compose.material3.Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(tint.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, value: Boolean?, enabled: Boolean, pending: Boolean? = null, onChange: (Boolean) -> Unit) {
    val shown = pending ?: value
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Muted(sub)
        }
        // Green "On" / grey "Off" badge; a spinner while the SIM phone is still switching.
        if (pending != null) Spinner() else if (value != null) StateBadge(value)
        Spacer(Modifier.width(8.dp))
        Switch(shown == true, onChange, enabled = enabled && value != null && pending == null)
    }
}

@Composable
private fun StateBadge(on: Boolean) {
    Box(Modifier.background(if (on) Tint.Green.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
        .padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(stringResource(if (on) R.string.on else R.string.off), style = MaterialTheme.typography.labelSmall,
            color = if (on) Tint.Green else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    }
}

/** 0-9 * # dial pad for USSD codes, like the Phone app's. */
@Composable
private fun DialPad(onKey: (String) -> Unit, onBack: () -> Unit, onClear: () -> Unit) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.forEach { k ->
                    androidx.compose.material3.FilledTonalButton({ onKey(k) }, Modifier.weight(1f).height(52.dp)) {
                        Text(k, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClear, Modifier.weight(1f)) { Text(stringResource(R.string.clear)) }
            OutlinedButton(onBack, Modifier.weight(1f)) { Text("⌫") }
        }
    }
}

/** Last 30 days as bars, then the apps that used most. */
@Composable
private fun DataChart(d: io.github.simbridge.DataDetail) {
    val max = (d.days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val bar = Tint.Purple
    Spacer(Modifier.height(8.dp))
    Muted(stringResource(R.string.dash_data_30))
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val n = d.days.size.coerceAtLeast(1)
        val w = size.width / n
        d.days.forEachIndexed { i, (_, b) ->
            val h = size.height * b / max
            drawRoundRect(bar, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h), CornerRadius(3f))
        }
    }
    Spacer(Modifier.height(8.dp))
    d.apps.take(8).forEach { (name, b) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(bytes(b), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Four signal bars; [level] 0..4 (-1 unknown). */
@Composable
private fun Bars(level: Int) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            Box(Modifier.width(5.dp).height((5 + i * 4).dp)
                .background(if (level >= i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.dp)))
        }
    }
}

/** "2 d 4 h", "14 h 20 min" or "35 min". */
internal fun duration(minutes: Int): String = when {
    minutes >= 48 * 60 -> "${minutes / 1440} d ${minutes % 1440 / 60} h"
    minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
    else -> "$minutes min"
}

@Composable
private fun onOff(on: Boolean?): String = stringResource(if (on == true) R.string.on else R.string.off)
