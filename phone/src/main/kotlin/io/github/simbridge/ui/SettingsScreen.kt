package io.github.simbridge.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Refresh
import io.github.simbridge.R
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.simbridge.LinkCore
import io.github.simbridge.PairedGateway
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import io.github.simbridge.LinkService
import io.github.simbridge.LinkState
import io.github.simbridge.SystemSms
import io.github.simbridge.isDefaultSmsApp
import io.github.simbridge.Telecom
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(core: LinkCore, onSimPhone: () -> Unit = {}, onBlocked: () -> Unit = {}, onRecordings: () -> Unit = {},
                   onBack: (() -> Unit)?) {
    val ctx = LocalContext.current
    val status by core.status.collectAsState()
    var pasting by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PairedGateway?>(null) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }   // refresh account / battery state after returning
    val accountOn = remember(tick) { Telecom.isEnabled(ctx) }
    val batteryOk = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val smsRole = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { tick++ }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.tab_settings)) },
                navigationIcon = { onBack?.let { IconButton(it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } } },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val connected = status.state == LinkState.CONNECTED
            Card(Icons.Filled.Wifi, stringResource(R.string.set_connection),
                if (connected) stringResource(R.string.set_connected_via, status.network ?: "",status.host ?: "") else status.detail.ifBlank { stringResource(R.string.not_connected) },
                ok = connected) {
                FilledTonalButton({ core.reconnect() }) { Text(stringResource(R.string.reconnect)) }
            }
            NavRow(Icons.Filled.Warning, stringResource(R.string.blocked_title), stringResource(R.string.blocked_sub), onBlocked)
            Card(Icons.Filled.Phone, stringResource(R.string.set_calls),
                if (accountOn) stringResource(R.string.set_calls_on)
                else stringResource(R.string.set_calls_off),
                ok = accountOn) {
                if (!accountOn) Button({
                    runCatching { ctx.startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text(stringResource(R.string.set_open_accounts)) }
            }
            Card(Icons.Filled.Mic, stringResource(R.string.set_audio),
                if (LinkService.micReady) stringResource(R.string.ready) else stringResource(R.string.set_audio_off),
                ok = LinkService.micReady) {}
            Card(Icons.Filled.BatteryChargingFull, stringResource(R.string.set_battery),
                if (batteryOk) stringResource(R.string.set_battery_on)
                else stringResource(R.string.set_battery_off),
                ok = batteryOk) {
                if (!batteryOk) Button({
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text(stringResource(R.string.allow)) }
            }
            val mirrorOk = remember(tick) { SystemSms.permitted(ctx) }
            val smsDefault = remember(tick) { isDefaultSmsApp(ctx) }
            var mirrorOn by remember { mutableStateOf(core.prefs.mirrorToSystem) }
            Card(Icons.Filled.Sms, stringResource(R.string.set_sms),
                if (smsDefault) stringResource(R.string.set_sms_on)
                else stringResource(R.string.set_sms_off),
                ok = true) {
                if (!smsDefault) FilledTonalButton({ smsRole.launch(ctx.getSystemService(android.app.role.RoleManager::class.java)
                    .createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS)) }) { Text(stringResource(R.string.set_sms_make)) }
            }
            if (!smsDefault) Card(Icons.Filled.Sms, stringResource(R.string.set_mirror),
                when {
                    !mirrorOn -> stringResource(R.string.set_mirror_off)
                    mirrorOk -> stringResource(R.string.set_mirror_on)
                    else -> stringResource(R.string.set_mirror_adb) +
                        "adb shell appops set ${ctx.packageName} WRITE_SMS allow"
                }, ok = !mirrorOn || mirrorOk) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_mirror_switch), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(mirrorOn, { mirrorOn = it; core.prefs.mirrorToSystem = it })
                }
            }
            val paired = remember(tick) { core.prefs.paired }
            Card(Icons.Filled.QrCodeScanner, stringResource(R.string.set_pair),
                if (paired != null) stringResource(R.string.set_paired, paired.fingerprint.take(8))
                else stringResource(R.string.set_pair_how),
                ok = paired != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ scanPairingCode(ctx) { pending = it } }) { Text(if (paired == null) stringResource(R.string.scan_code) else stringResource(R.string.scan_again)) }
                    TextButton({ pasting = !pasting }) { Text(stringResource(R.string.paste_link)) }
                    if (paired != null) TextButton({ confirmUnpair = true }) { Text(stringResource(R.string.unpair)) }
                }
                if (pasting) {
                    OutlinedTextField(link, { link = it.trim() }, singleLine = true, label = { Text("simbridge://pair?…") },
                        modifier = Modifier.fillMaxWidth())
                    Button({ pending = PairedGateway.parse(link) ?: run {
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.not_pair_link), android.widget.Toast.LENGTH_SHORT).show(); null
                    } }, enabled = link.isNotEmpty()) { Text(stringResource(R.string.pair)) }
                }
            }
            var autoRec by remember { mutableStateOf(core.prefs.autoRecord) }
            Card(Icons.Filled.Mic, stringResource(R.string.set_recording),
                stringResource(R.string.set_recording_text), ok = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_record_all), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(autoRec, { autoRec = it; core.prefs.autoRecord = it })
                }
                TextButton(onRecordings) { Text(stringResource(R.string.recordings)) }
            }
            val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
                uri ?: return@rememberLauncherForActivityResult
                val r = runCatching { io.github.simbridge.Backup.export(ctx, uri) }
                android.widget.Toast.makeText(ctx, r.fold({ "Exported $it texts" }, { "Export failed: ${it.message}" }),
                    android.widget.Toast.LENGTH_LONG).show()
            }
            val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
                uri ?: return@rememberLauncherForActivityResult
                val r = runCatching { io.github.simbridge.Backup.import(ctx, uri) }
                android.widget.Toast.makeText(ctx, r.fold({ "Imported $it texts" }, { "Import failed: ${it.message}" }),
                    android.widget.Toast.LENGTH_LONG).show()
            }
            var web by remember { mutableStateOf(core.prefs.webTexts) }
            var webCode by remember { mutableStateOf(core.web.code) }
            Card(Icons.Filled.Computer, stringResource(R.string.set_web_title), stringResource(R.string.set_web_text), ok = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_web_switch), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(web, { on -> web = on; core.prefs.webTexts = on; if (on) { webCode = core.web.newCode(); core.web.start() } else core.web.stop() })
                }
                if (web) {
                    val ip = remember { localIp() }
                    Text("https://${ip ?: "…"}:${core.web.port}", style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.set_web_code, webCode), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.set_web_warning), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            var share by remember { mutableStateOf(core.prefs.shareContacts) }
            var remote by remember { mutableStateOf(core.prefs.remoteHost) }
            Card(Icons.Filled.Contacts, stringResource(R.string.set_more_title), stringResource(R.string.set_more_text), ok = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_share_contacts), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(share, { share = it; core.prefs.shareContacts = it; if (it) core.control.syncContacts() })
                }
                OutlinedTextField(remote, { remote = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.set_remote)) }, supportingText = { Text(stringResource(R.string.set_remote_help)) })
                TextButton({ core.prefs.remoteHost = remote; core.reconnect() }) { Text(stringResource(R.string.save)) }
            }
            val latest by io.github.simbridge.UpdateCheck.latest.collectAsState()
            var upd by remember { mutableStateOf(core.prefs.updateCheck) }
            Card(Icons.Filled.Refresh, stringResource(R.string.set_updates),
                latest?.let { stringResource(R.string.set_update_available, it) } ?: stringResource(R.string.set_updates_text, io.github.simbridge.BuildConfig.VERSION_NAME),
                ok = latest == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_update_daily), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(upd, { upd = it; core.prefs.updateCheck = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ io.github.simbridge.UpdateCheck.maybeCheck(ctx, force = true) }) { Text(stringResource(R.string.set_check_now)) }
                    if (latest != null) FilledTonalButton({
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(io.github.simbridge.UpdateCheck.RELEASES))) }
                    }) { Text(stringResource(R.string.set_download)) }
                }
            }
            Card(Icons.Filled.Sms, stringResource(R.string.set_backup),
                stringResource(R.string.set_backup_text), ok = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ exportLauncher.launch("simbridge-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.json") }) { Text("Export") }
                    TextButton({ importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import") }
                }
            }
            Text(stringResource(R.string.set_footer),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
    ConfirmPairing(core, pending) { pending = null; pasting = false; link = ""; tick++ }
    if (confirmUnpair) AlertDialog(
        onDismissRequest = { confirmUnpair = false },
        title = { Text(stringResource(R.string.unpair_q)) },
        text = { Text(stringResource(R.string.unpair_text)) },
        confirmButton = { TextButton({ core.unpair(); confirmUnpair = false; tick++ }) { Text(stringResource(R.string.unpair)) } },
        dismissButton = { TextButton({ confirmUnpair = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmPairing(core: LinkCore, g: PairedGateway?, onDone: () -> Unit) {
    if (g == null) return
    AlertDialog(
        onDismissRequest = onDone,
        icon = { Icon(Icons.Filled.QrCodeScanner, null) },
        title = { Text(stringResource(R.string.pair_q)) },
        text = { Text(stringResource(R.string.pair_q_text, g.id.take(6),g.fingerprint.take(8))) },
        confirmButton = { TextButton({ core.pair(g); onDone() }) { Text(stringResource(R.string.pair)) } },
        dismissButton = { TextButton(onDone) { Text(stringResource(R.string.cancel)) } },
    )
}



@Composable
internal fun NavRow(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    ElevatedCard(onClick, Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp).graphicsLayer { rotationZ = 180f },
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun Card(icon: ImageVector, title: String, text: String, ok: Boolean, actions: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.size(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            actions()
        }
    }
}

/** This phone's Wi-Fi / LAN IPv4 address, for the browser link. */
private fun localIp(): String? = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
}.getOrNull()
