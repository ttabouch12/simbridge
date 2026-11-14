package io.github.simbridge.ui

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

/** Dashboard tab: the SIM phone at a glance, plus everything the main phone can control on it. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SimPhoneScreen(
    core: LinkCore,
    onRemote: () -> Unit = {},
    onSettings: () -> Unit = {},
    onOpenChat: (String, String) -> Unit = { _, _ -> },
    onNewChat: () -> Unit = {},
    startPage: Int = 1,
) {
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
    // Messages, Overview, Controls, USSD, Data, Places, Speed: swipe between them, tabs at the bottom.
    val pages = listOf(R.string.tab_messages, R.string.dash_tab_overview, R.string.dash_tab_controls, R.string.dash_ussd,
        R.string.dash_tab_data, R.string.dash_tab_places, R.string.speed_title)
    val pager = androidx.compose.foundation.pager.rememberPagerState(startPage) { pages.size }
    val guard = rememberGuard(core)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(online) { while (online) { core.control.refresh(); delay(30_000) } }
    LaunchedEffect(ussd) { if (ussd != null) waitingUssd = false }
    LaunchedEffect(loc) { if (loc != null) locating = false }
    LaunchedEffect(notice) { notice?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show(); core.control.clearNotice() } }

    Scaffold(
        bottomBar = {
            androidx.compose.material3.PrimaryScrollableTabRow(pager.currentPage, edgePadding = 4.dp,
                modifier = Modifier.navigationBarsPadding()) {
                pages.forEachIndexed { i, t ->
                    androidx.compose.material3.Tab(pager.currentPage == i, { scope.launch { pager.animateScrollToPage(i) } },
                        text = { Text(stringResource(t)) })
                }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize().padding(pad), beyondViewportPageCount = 1) { page ->
        if (page == 0) ConversationsScreen(core, onOpenChat, onNewChat, onSettings)
        else Column(Modifier.fillMaxSize()) {
        val tab = page - 1
        TopAppBar(
            title = { Text(stringResource(pages[page])) },
            actions = {
                IconButton({ core.control.refresh() }, enabled = online) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
                IconButton(onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.tab_settings)) }
            },
        )
        if (tab == 5) SpeedTest(core, online)
        else Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val s = st
            if (tab == 0) Hero(s, online, link.detail)

            if (tab == 0) Section(Icons.Filled.SimCard, stringResource(R.string.dash_sims), Tint.Blue) {
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

            if (tab == 3 && s != null) Section(Icons.Filled.DataUsage, stringResource(R.string.dash_data), Tint.Purple) {
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

            if (tab == 0) MissedCalls(core)
            if (tab == 0 && s != null) Section(Icons.Filled.Devices, stringResource(R.string.dash_hotspot_clients, s.clients.size), Tint.Teal) {
                if (!s.hotspot) Muted(stringResource(R.string.dash_hotspot_off))
                else if (s.clients.isEmpty()) Muted(stringResource(R.string.dash_no_clients))
                else s.clients.forEach { c ->
                    Text("${c.ip}  ·  …${c.mac}" + if (c.simbridge) "  ·  SimBridge" else "", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (tab == 1) Section(Icons.Filled.SettingsRemote, stringResource(R.string.dash_remote), Tint.Orange) {
                ToggleRow(stringResource(R.string.dash_saver), stringResource(R.string.dash_saver_sub), s?.powerSaver, online,
                    pending["power-saver"] as Boolean?) { on -> guard { core.control.control("power-saver", on) } }
                ToggleRow(stringResource(R.string.dash_mobile_data), stringResource(R.string.dash_mobile_data_sub), s?.dataOn, online,
                    pending["mobile-data"] as Boolean?) { confirm = "mobile-data" to it }
                ToggleRow(stringResource(R.string.dash_hotspot), stringResource(R.string.dash_hotspot_sub), s?.hotspot, online,
                    pending["hotspot"] as Boolean?) { confirm = "hotspot" to it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button({ core.control.control("find", true) }, enabled = online) {
                        if (pending.containsKey("find")) { Spinner(MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                        Text(stringResource(R.string.dash_ring))
                    }
                    OutlinedButton({ core.control.control("find", false) }, enabled = online) { Text(stringResource(R.string.dash_stop)) }
                    OutlinedButton({ core.control.control("restart", true) }, enabled = online) {
                        if (pending.containsKey("restart")) { Spinner(); Spacer(Modifier.width(8.dp)) }
                        Text(stringResource(R.string.dash_restart))
                    }
                    androidx.compose.material3.FilledTonalButton({ guard(onRemote) }, enabled = online) { Text(stringResource(R.string.dash_remote_screen)) }
                }
            }
            // Samsung battery protection: stop charging at a level, for a phone that is always plugged in.
            if (tab == 1) Section(Icons.Filled.BatteryChargingFull, stringResource(R.string.dash_charge_limit), Tint.Green) {
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
            if (tab == 1) AppNotifSection(core, online)
            if (tab == 4) PlacesSection(core, online)

            if (tab == 1) Section(Icons.Filled.Call, stringResource(R.string.dash_calls), Tint.Green) {
                Muted(stringResource(R.string.dash_voicemail_sub))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf(0, 15, 25, 40).forEach { sec ->
                        FilterChip(s?.noAnswerSec == sec, { core.control.setNoAnswer(sec) }, enabled = online,
                            label = { Text(if (sec == 0) stringResource(R.string.off) else "$sec s") })
                    }
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

            if (tab == 4) Section(Icons.Filled.LocationOn, stringResource(R.string.dash_locate), Tint.Red) {
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

            if (tab == 3) Section(Icons.Filled.SettingsInputAntenna, stringResource(R.string.dash_apn), Tint.Blue) {
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

            if (tab == 2) Section(Icons.Filled.Dialpad, stringResource(R.string.dash_ussd), Tint.Teal) {
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
    ElevatedCard(Modifier.fillMaxWidth()) {
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
                if (mins != null) Muted(stringResource(if (s?.charging == true) R.string.dash_full_in else R.string.dash_time_left, duration(mins)))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.background(if (online) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                    RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(if (online) stringResource(R.string.dash_connected) else detail.ifBlank { stringResource(R.string.dash_offline) },
                        style = MaterialTheme.typography.labelMedium)
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
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(tint.copy(alpha = 0.15f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
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
