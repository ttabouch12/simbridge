package io.github.simbridge.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.simbridge.CallQuality
import io.github.simbridge.GatewayStatus
import io.github.simbridge.LinkCore
import io.github.simbridge.LinkState
import io.github.simbridge.NightMode
import io.github.simbridge.R
import io.github.simbridge.Telecom
import io.github.simbridge.WifiLink
import io.github.simbridge.isDefaultSmsApp
import kotlinx.coroutines.delay

private fun linkText(w: WifiLink) = "${w.band} · ↓${w.rx} ↑${w.tx} Mb/s · ${w.rssi} dBm"

/** Wi-Fi of both phones at a glance, with a warning when a link is slow enough to hurt calls. */
@Composable
fun LinkHealth(core: LinkCore, s: GatewayStatus?) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5000); tick++ } }
    val mine = remember(tick) { WifiLink.local(ctx) }
    val theirs = s?.wifi
    val weak = mine?.weak == true || theirs?.weak == true
    val rows = mutableListOf<@Composable () -> Unit>()
    rows += { GroupRow(Icons.Filled.Wifi, stringResource(R.string.health_this),
            mine?.let { (it.ssid.ifBlank { "Wi-Fi" }) + " · " + linkText(it) } ?: stringResource(R.string.health_no_wifi),
            if (mine?.weak == true) Status.warn() else Status.ok(), opens = false) }
    rows += {
            GroupRow(Icons.Filled.Wifi, stringResource(R.string.health_sim),
                when {
                    s?.hotspot == true -> stringResource(R.string.health_hotspot, s.clients.size) +
                        (s.apBand?.let { b -> " · " + when (b) { 5 -> "5 GHz"; 2 -> "2.4 GHz"; else -> stringResource(R.string.hs_auto) } } ?: "")
                    theirs != null -> theirs.ssid + " · " + linkText(theirs)
                    else -> stringResource(R.string.health_no_wifi)
                }, if (theirs?.weak == true) Status.warn() else Status.ok(), opens = false)
        }
    if (weak) rows += { GroupRow(Icons.Filled.Warning, stringResource(R.string.health_weak), stringResource(R.string.health_weak_sub), Status.warn(), opens = false) }
    Group(stringResource(R.string.health_title), rows)
}

/** The last call's audio: how much was missing and the Wi-Fi it ran on. */
@Composable
fun LastCallCard(core: LinkCore) {
    val q = remember { CallQuality.parse(core.prefs.lastCall) } ?: return
    val (label, color) = when (q.verdict) {
        0 -> stringResource(R.string.cq_good) to Status.ok()
        1 -> stringResource(R.string.cq_fair) to Status.warn()
        else -> stringResource(R.string.cq_poor) to Status.bad()
    }
    Group(stringResource(R.string.cq_title), listOf {
        GroupRow(Icons.Filled.GraphicEq, label + " · " + duration((q.seconds / 60).toInt().coerceAtLeast(1)),
            stringResource(R.string.cq_detail, "%.1f".format(q.lossPct), q.late) +
                (q.wifi?.let { " · " + it.band + " ↑${it.tx} Mb/s" } ?: "") +
                (if (q.verdict > 0) "\n" + stringResource(R.string.cq_tip) else ""),
            color, opens = false)
    })
}

private data class Check(
    val ok: Boolean?, val title: String, val detail: String, val fix: (() -> Unit)? = null, val fixLabel: String? = null,
    /** The detail only says how to fix it, so it is hidden once the check passes. */
    val fixText: Boolean = fixLabel != null,
)

/** "What's wrong?": every check that matters, each with what to do about it. */
@Composable
fun DiagnosticsPage(core: LinkCore, s: GatewayStatus?, online: Boolean, onTool: (Tool) -> Unit) {
    val ctx = LocalContext.current
    val link by core.status.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { core.control.refresh(); while (true) { delay(2000); tick++ } }
    val checks = remember(tick, s, link) {
        fun open(i: Intent) = { runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; Unit }
        val mine = WifiLink.local(ctx)
        val perm = { p: String -> ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        listOfNotNull(
            Check(link.state == LinkState.CONNECTED, ctx.getString(R.string.chk_link),
                if (link.state == LinkState.CONNECTED) ctx.getString(R.string.dash_connected) else link.detail.ifBlank { ctx.getString(R.string.chk_link_fix) }),
            Check(mine != null && !mine.weak, ctx.getString(R.string.chk_wifi),
                mine?.let { linkText(it) + if (it.weak) "\n" + ctx.getString(R.string.health_weak_sub) else "" } ?: ctx.getString(R.string.health_no_wifi)),
            if (mine?.stuckAx == true) Check(false, ctx.getString(R.string.chk_wifi6), ctx.getString(R.string.chk_wifi6_fix)) else null,
            if (Telecom.selfManaged(ctx) && core.prefs.fullScreenCalls) Check(ctx.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent(),
                ctx.getString(R.string.chk_fsi), ctx.getString(R.string.chk_fsi_fix),
                open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:" + ctx.packageName))), ctx.getString(R.string.chk_allow))
            else             Check(Telecom.isEnabled(ctx), ctx.getString(R.string.chk_account), ctx.getString(R.string.chk_account_fix),
                open(Intent(android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)), ctx.getString(R.string.chk_open)),
            Check(ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName), ctx.getString(R.string.chk_battery),
                ctx.getString(R.string.chk_battery_fix),
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + ctx.packageName))), ctx.getString(R.string.chk_allow)),
            Check(perm(android.Manifest.permission.RECORD_AUDIO) && perm(android.Manifest.permission.POST_NOTIFICATIONS), ctx.getString(R.string.chk_perms),
                ctx.getString(R.string.chk_perms_fix),
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName))), ctx.getString(R.string.chk_open)),
            if (!io.github.simbridge.smsRoleAvailable(ctx)) null else Check(isDefaultSmsApp(ctx).takeIf { it }, ctx.getString(R.string.chk_sms), ctx.getString(R.string.chk_sms_fix), fixText = true),
            if (s == null) Check(false, ctx.getString(R.string.chk_sim_phone), ctx.getString(R.string.dash_waiting)) else null,
            s?.let { st ->
                val low = (st.battery ?: 100) < 20 && !st.charging
                Check(!low, ctx.getString(R.string.chk_sim_battery), "${st.battery ?: "—"} %" + if (low) " · " + ctx.getString(R.string.chk_sim_battery_fix) else "")
            },
            s?.let { st ->
                val best = st.sims.maxOfOrNull { it.level } ?: -1
                Check(best > 0, ctx.getString(R.string.chk_signal),
                    if (st.sims.isEmpty()) ctx.getString(R.string.dash_no_sim) else st.sims.joinToString(" · ") { "SIM ${it.slot}: ${it.level}/4 ${it.network}" })
            },
            s?.takeIf { it.hotspot }?.let { st ->
                Check(st.apBand != 2, ctx.getString(R.string.chk_band),
                    if (st.apBand == 2) ctx.getString(R.string.chk_band_fix) else ctx.getString(R.string.chk_band_ok), { onTool(Tool.HOTSPOT) }, ctx.getString(R.string.chk_open))
            },
            s?.let { st -> Check(st.dataOn, ctx.getString(R.string.dash_mobile_data), if (st.dataOn) ctx.getString(R.string.on) else ctx.getString(R.string.chk_data_fix)) },
        )
    }
    val bad = checks.count { it.ok == false }
    Panel {
        Text(if (bad == 0) stringResource(R.string.chk_all_good) else stringResource(R.string.chk_some, bad),
            style = MaterialTheme.typography.titleLarge, color = if (bad == 0) Status.ok() else Status.warn())
    }
    Group(null, checks.map { c ->
        {
            GroupRow(
                when (c.ok) { true -> Icons.Filled.CheckCircle; false -> Icons.Filled.Error; null -> Icons.Filled.Warning },
                c.title, c.detail.takeUnless { c.ok == true && c.fixText },
                when (c.ok) { true -> Status.ok(); false -> Status.bad(); null -> Status.warn() },
                opens = false,
                trailing = if (c.ok == false && c.fix != null) ({ TextButton(c.fix) { Text(c.fixLabel ?: "") } }) else null,
            )
        }
    })
    if (!online) Muted(stringResource(R.string.chk_offline))
}

/** Night mode on the SIM phone: mobile data off and battery saver on between two times. */
@Composable
fun NightModeCard(core: LinkCore, s: GatewayStatus?, online: Boolean) {
    val ctx = LocalContext.current
    val n = s?.night ?: NightMode()
    fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    fun pick(m: Int, set: (Int) -> Unit) = TimePickerDialog(ctx, { _, h, mi -> set(h * 60 + mi) }, m / 60, m % 60, true).show()
    Group(stringResource(R.string.night_title), listOf(
        { GroupRow(null, stringResource(R.string.night_on), stringResource(R.string.night_sub), opens = false, enabled = online,
            trailing = { Switch(n.on, { core.control.setNight(n.copy(on = it)) }, enabled = online) }) },
        {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ pick(n.start) { core.control.setNight(n.copy(start = it)) } }, enabled = online) { Text(stringResource(R.string.quiet_from, hm(n.start))) }
                TextButton({ pick(n.end) { core.control.setNight(n.copy(end = it)) } }, enabled = online) { Text(stringResource(R.string.quiet_to, hm(n.end))) }
            }
        },
        { GroupRow(null, stringResource(R.string.night_data), null, opens = false, enabled = online,
            trailing = { Switch(n.data, { core.control.setNight(n.copy(data = it)) }, enabled = online) }) },
        { GroupRow(null, stringResource(R.string.night_saver), null, opens = false, enabled = online,
            trailing = { Switch(n.saver, { core.control.setNight(n.copy(saver = it)) }, enabled = online) }) },
    ))
}

/** Monthly data limit: a text from the SIM phone at 80 % and 100 %. */
@Composable
fun DataLimitCard(core: LinkCore, s: GatewayStatus?, online: Boolean) {
    Group(stringResource(R.string.limit_title), listOf {
        Column(Modifier.padding(16.dp)) {
            Muted(stringResource(R.string.limit_sub))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOf(0.0, 5.0, 10.0, 20.0, 50.0).forEach { gb ->
                    FilterChip(s?.dataLimitGb == gb, { core.control.setDataLimit(gb) }, enabled = online,
                        label = { Text(if (gb == 0.0) stringResource(R.string.off) else "${gb.toInt()} GB") })
                }
            }
        }
    })
}

/** Smart hotspot and the carrier-friendly proxy, on the Hotspot page. */
@Composable
fun HotspotSmartCard(core: LinkCore, s: GatewayStatus?, online: Boolean) {
    val ctx = LocalContext.current
    val gw = remember(s?.hotspot) { WifiLink.gatewayIp(ctx) }
    val rows = mutableListOf<@Composable () -> Unit>()
    rows += { GroupRow(null, stringResource(R.string.smart_on), stringResource(R.string.smart_sub), opens = false, enabled = online,
            trailing = { Switch(s?.smartHotspot == true, { core.control.setSmartHotspot(it) }, enabled = online) }) }
    rows += { GroupRow(null, stringResource(R.string.proxy_on), stringResource(R.string.proxy_sub), opens = false, enabled = online,
            trailing = { Switch(s?.proxy == true, { core.control.setProxy(it) }, enabled = online) }) }
    if (s?.proxy == true) rows += {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.proxy_how), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.proxy_values, gw ?: "—", s.proxyPort), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 6.dp))
                TextButton({ runCatching { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                    Text(stringResource(R.string.proxy_open))
                }
            }
        }
    Group(stringResource(R.string.smart_title), rows)
}

/** The SIM phone's own internet speed next to this phone's, to tell carrier throttling from Wi-Fi trouble. */
@Composable
fun SimSpeedCard(core: LinkCore, online: Boolean) {
    val sim by core.control.simSpeed.collectAsState()
    Section(Icons.Filled.Wifi, stringResource(R.string.simspeed_title), Tint.Blue) {
        Muted(stringResource(R.string.simspeed_sub))
        val v = sim
        when {
            v == null -> {}
            v < 0 -> LinearProgressIndicator(Modifier.padding(vertical = 8.dp))
            else -> Text("%.0f Mb/s".format(v), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
        }
        Button({ core.control.measureSimSpeed() }, enabled = online && v?.let { it >= 0 } != false) { Text(stringResource(R.string.speed_run)) }
    }
}

/** Main phones connected to the SIM phone right now. */
@Composable
fun PhonesCard(s: GatewayStatus?) {
    val list = s?.phones.orEmpty()
    Group(stringResource(R.string.phones_title), listOf {
        GroupRow(null, stringResource(R.string.phones_count, list.size),
            (list.joinToString(" · ").ifBlank { null }?.let { it + "\n" } ?: "") + stringResource(R.string.phones_sub), opens = false)
    })
}

