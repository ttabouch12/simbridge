package io.github.simbridge.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.foundation.layout.navigationBarsPadding
import kotlinx.coroutines.launch
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
    val pager = androidx.compose.foundation.pager.rememberPagerState { 4 }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }   // refresh account / battery state after returning
    val accountOn = remember(tick) { Telecom.isEnabled(ctx) }
    val batteryOk = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val smsRole = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { tick++ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_settings)) },
                navigationIcon = { onBack?.let { IconButton(it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } } },
            )
        },
        // Tabs at the bottom, within thumb reach; swipe between pages too.
        bottomBar = {
            androidx.compose.material3.PrimaryScrollableTabRow(pager.currentPage, edgePadding = 4.dp,
                modifier = Modifier.navigationBarsPadding()) {
                listOf(R.string.set_tab_connection, R.string.set_tab_calls, R.string.set_tab_messages, R.string.set_tab_app).forEachIndexed { i, r ->
                    androidx.compose.material3.Tab(pager.currentPage == i, { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(stringResource(r)) })
                }
            }
        },
    ) { pad ->
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize().padding(pad)) { tab ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val connected = status.state == LinkState.CONNECTED
            val mirrorOk = remember(tick) { SystemSms.permitted(ctx) }
            val smsDefault = remember(tick) { isDefaultSmsApp(ctx) }
            var mirrorOn by remember { mutableStateOf(core.prefs.mirrorToSystem) }
            val paired = remember(tick) { core.prefs.paired }
            var autoRec by remember { mutableStateOf(core.prefs.autoRecord) }
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
            var share by remember { mutableStateOf(core.prefs.shareContacts) }
            var remote by remember { mutableStateOf(core.prefs.remoteHost) }
            val latest by io.github.simbridge.UpdateCheck.latest.collectAsState()
            var upd by remember { mutableStateOf(core.prefs.updateCheck) }
            if (tab == 0) {
                Card(Icons.Filled.Wifi, stringResource(R.string.set_connection),
                    if (connected) stringResource(R.string.set_connected_via, status.network ?: "",status.host ?: "") else status.detail.ifBlank { stringResource(R.string.not_connected) },
                    ok = connected) {
                    FilledTonalButton({ core.reconnect() }) { Text(stringResource(R.string.reconnect)) }
                }
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
                Card(Icons.Filled.Contacts, stringResource(R.string.set_more_title), stringResource(R.string.set_more_text), ok = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.set_share_contacts), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(share, { share = it; core.prefs.shareContacts = it; if (it) core.control.syncContacts() })
                    }
                    OutlinedTextField(remote, { remote = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.set_remote)) }, supportingText = { Text(stringResource(R.string.set_remote_help)) })
                    TextButton({ core.prefs.remoteHost = remote; core.reconnect() }) { Text(stringResource(R.string.save)) }
                }
                Text(stringResource(R.string.set_footer),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp))
            }
            if (tab == 1) {
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
                Card(Icons.Filled.Mic, stringResource(R.string.set_recording),
                    stringResource(R.string.set_recording_text), ok = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.set_record_all), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(autoRec, { autoRec = it; core.prefs.autoRecord = it })
                    }
                    TextButton(onRecordings) { Text(stringResource(R.string.recordings)) }
                }
                TranslationCard(core)
                NavRow(Icons.Filled.Warning, stringResource(R.string.blocked_title), stringResource(R.string.blocked_sub), onBlocked)
            }
            if (tab == 2) {
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
                var otp by remember { mutableStateOf(core.prefs.otpCopy) }
                Card(Icons.Filled.Sms, stringResource(R.string.set_otp), stringResource(R.string.set_otp_text), ok = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.set_otp_switch), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(otp, { otp = it; core.prefs.otpCopy = it })
                    }
                }
                Card(Icons.Filled.Sms, stringResource(R.string.set_backup),
                    stringResource(R.string.set_backup_text), ok = true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton({ exportLauncher.launch("simbridge-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.json") }) { Text("Export") }
                        TextButton({ importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import") }
                    }
                }
            }
            if (tab == 3) {
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
            }
        }
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

@Composable
private fun TranslationCard(core: LinkCore) {
    val ctx = LocalContext.current
    val prefs = core.prefs
    var mode by remember { mutableStateOf(prefs.translateMode) }
    var engine by remember { mutableStateOf(prefs.translateEngine) }
    var caller by remember { mutableStateOf(prefs.callerLang) }
    var mine by remember { mutableStateOf(prefs.myLang) }
    var pause by remember { mutableIntStateOf(prefs.pauseMs) }
    var onDevice by remember { mutableStateOf(prefs.translateOnDevice) }
    var speakToMe by remember { mutableStateOf(prefs.speakToMe) }
    var bubble by remember { mutableStateOf(prefs.captionBubble) }
    var large by remember { mutableStateOf(prefs.captionLarge) }
    var groq by remember { mutableStateOf(prefs.groqKey) }
    var cerebras by remember { mutableStateOf(prefs.cerebrasKey) }
    var gemini by remember { mutableStateOf(prefs.geminiKey) }
    var model by remember { mutableStateOf(prefs.translateModel) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Card(Icons.Filled.Translate, stringResource(R.string.set_translate), stringResource(R.string.set_translate_text), ok = true) {
        Text(stringResource(R.string.set_tr_start), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(io.github.simbridge.TranslateMode.OFF to R.string.tr_off, io.github.simbridge.TranslateMode.CAPTIONS to R.string.tr_captions,
                io.github.simbridge.TranslateMode.VOICE to R.string.tr_voice).forEach { (m, label) ->
                androidx.compose.material3.FilterChip(mode == m, { mode = m; prefs.translateMode = m }, { Text(stringResource(label)) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LangPicker(stringResource(R.string.set_tr_caller), caller, Modifier.weight(1f)) { caller = it; prefs.callerLang = it }
            LangPicker(stringResource(R.string.set_tr_me), mine, Modifier.weight(1f)) { mine = it; prefs.myLang = it }
        }

        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.set_tr_engine), style = MaterialTheme.typography.labelLarge)
        io.github.simbridge.Engine.entries.forEach { e ->
            Row(Modifier.fillMaxWidth().clickable { engine = e; prefs.translateEngine = e }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(engine == e, { engine = e; prefs.translateEngine = e })
                Column(Modifier.weight(1f)) {
                    Text(stringResource(e.title), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(e.note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!onDevice) {
            KeyField(stringResource(R.string.set_groq_key), groq, "https://console.groq.com/keys", ::open) { groq = it; prefs.groqKey = it }
            if (engine == io.github.simbridge.Engine.CEREBRAS)
                KeyField(stringResource(R.string.set_cerebras_key), cerebras, engine.keyUrl, ::open) { cerebras = it; prefs.cerebrasKey = it }
            if (engine == io.github.simbridge.Engine.GEMINI)
                KeyField(stringResource(R.string.set_gemini_key), gemini, engine.keyUrl, ::open) { gemini = it; prefs.geminiKey = it }
            if (engine != io.github.simbridge.Engine.ON_DEVICE)
                OutlinedTextField(model, { model = it; prefs.translateModel = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.set_translate_model)) }, placeholder = { Text(engine.model) })
        }

        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.set_tr_pause), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(450 to R.string.set_tr_fast, 600 to R.string.set_tr_normal, 900 to R.string.set_tr_patient).forEach { (ms, label) ->
                androidx.compose.material3.FilterChip(pause == ms, { pause = ms; prefs.pauseMs = ms }, { Text(stringResource(label)) })
            }
        }
        SwitchRow(stringResource(R.string.set_tr_bubble), bubble) {
            bubble = it; prefs.captionBubble = it
            if (it && !overlayOk)
                runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))) }
        }
        if (bubble && !overlayOk) TextButton({
            runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))) }
        }) { Text(stringResource(R.string.set_tr_bubble_allow)) }
        SwitchRow(stringResource(R.string.set_tr_speak_me), speakToMe) { speakToMe = it; prefs.speakToMe = it }
        SwitchRow(stringResource(R.string.set_tr_large), large) { large = it; prefs.captionLarge = it }
        SwitchRow(stringResource(R.string.set_translate_offline), onDevice) {
            onDevice = it; prefs.translateOnDevice = it
            if (it) { io.github.simbridge.OfflineTranslate.prepare(ctx, caller, mine); io.github.simbridge.OnDeviceStt.prepare(ctx, listOf(caller, mine)) }
        }
    }
}

@Composable
private fun SwitchRow(text: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(on, set)
    }
}

@Composable
private fun KeyField(label: String, value: String, url: String, open: (String) -> Unit, set: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(value, set, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } })
    TextButton({ open(url) }) { Text(stringResource(R.string.set_get_key, Uri.parse(url).host ?: url)) }
}

@Composable
private fun LangPicker(label: String, code: String, modifier: Modifier, pick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        androidx.compose.material3.OutlinedButton({ open = true }, Modifier.fillMaxWidth()) {
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(io.github.simbridge.Langs.label(code))
            }
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            io.github.simbridge.Langs.all.forEach { c ->
                androidx.compose.material3.DropdownMenuItem({ Text(io.github.simbridge.Langs.label(c) + "  ·  " + io.github.simbridge.Langs.name(c)) },
                    { pick(c); open = false })
            }
        }
    }
}
