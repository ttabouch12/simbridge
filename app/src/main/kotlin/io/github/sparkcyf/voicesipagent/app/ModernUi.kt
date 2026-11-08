package io.github.sparkcyf.voicesipagent.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PhoneForwarded
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import android.widget.Toast
import android.graphics.Bitmap
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.rounded.MobileOff
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

private val Good = Color(0xFF1E8E3E)
private val Bad = Color(0xFFD93025)
private val Warn = Color(0xFFF29900)

@Composable
fun GatewayTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** IPv4 addresses the main phone can register to, labelled by interface type. */
private fun localAddresses(): List<Pair<String, String>> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { nif ->
        nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { addr ->
            val n = nif.name
            val label = when {
                n.startsWith("wlan") -> "Home Wi-Fi"
                n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("wigig") -> "This phone's hotspot"
                n.startsWith("rndis") || n.startsWith("usb") -> "USB tether"
                else -> null
            }
            label?.let { it to addr.hostAddress.orEmpty() }
        }.filterNotNull()
    }
}.getOrDefault(emptyList())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernScreen(rt: GatewayRuntime, setTakeover: (Boolean) -> Unit, openAdvanced: () -> Unit) {
    currentUiLang = UiLang.EN
    val h by rt.health.collectAsState()
    val cur by rt.settingsFlow.collectAsState()
    val power by rt.companionPower.collectAsState()
    val quiet by rt.quietFlags.collectAsState()
    val battery by rt.battery.collectAsState()
    val log by AppLog.tail.collectAsState()
    var addresses by remember { mutableStateOf(localAddresses()) }
    LaunchedEffect(Unit) { while (true) { addresses = localAddresses(); delay(5000) } }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("SimBridge Gateway") },
                actions = {
                    IconButton(onClick = { rt.runSetup(); rt.restartSip() }) { Icon(Icons.Rounded.Refresh, "Refresh") }
                    IconButton(onClick = openAdvanced) { Icon(Icons.Rounded.Tune, "Advanced") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { HeroCard(h, cur.takeover) }
            item {
                GroupCard("Status") {
                    val shz = h.shizuku == ShizukuConnector.State.CONNECTED
                    StatusRow(Icons.Rounded.Shield, "Shizuku helper",
                        if (shz) "Running" else "Not running — ${h.shizukuDetail.ifBlank { h.shizuku.name }}", shz)
                    if (h.shizuku == ShizukuConnector.State.PERMISSION_REQUIRED || h.shizuku == ShizukuConnector.State.PERMISSION_DENIED) {
                        FilledTonalButton(onClick = { rt.shizuku.requestPermission() }, modifier = Modifier.padding(start = 56.dp, bottom = 8.dp)) {
                            Text("Allow Shizuku access")
                        }
                    }
                    StatusRow(Icons.Rounded.Smartphone, "Main phone",
                        if (h.sip.ready) "Connected" else h.sip.detail.replaceFirstChar { it.uppercase() }, h.sip.ready)
                    StatusRow(Icons.Rounded.GraphicEq, "Call audio access",
                        if (h.audioReady) "Ready" else "Not available yet", h.audioReady)
                    val b = battery
                    StatusRow(
                        when { b == null -> Icons.Rounded.BatteryFull; b.charging -> Icons.Rounded.BatteryChargingFull; b.percent <= 20 -> Icons.Rounded.BatteryAlert; else -> Icons.Rounded.BatteryFull },
                        "Battery", b?.let { "${it.percent}%" + if (it.charging) " · charging" else " · on battery" } ?: "Unknown",
                        b == null || b.charging || b.percent > 20, neutralWhenOk = true,
                    )
                }
            }
            item {
                GroupCard("Controls") {
                    SwitchRow(Icons.AutoMirrored.Rounded.PhoneForwarded, "Forward calls & texts",
                        when { h.takeoverOffPending -> "Turning off after this call"; h.takeoverOnPending -> "Turning on after this call"
                            cur.takeover -> "Calls and SMS go to your main phone"; else -> "Off — this phone rings normally" },
                        cur.takeover, setTakeover)
                    SwitchRow(Icons.Rounded.BatterySaver, "Companion power saver",
                        if (power) "On — only calls, SMS and hotspot stay active" else "Restrict other apps, location, Bluetooth and scanning",
                        power, { rt.setCompanionPower(it) }, enabled = h.shizuku == ShizukuConnector.State.CONNECTED)
                    // Ultimate saver options, applied while the power saver is on.
                    QuietRow(Icons.Rounded.NotificationsOff, "No sounds", "Ringtones, notifications, touch and charging sounds",
                        quiet, 1, rt::setQuietFlags)
                    QuietRow(Icons.Rounded.Vibration, "No vibration", "Calls, notifications, keyboard and touch feedback",
                        quiet, 2, rt::setQuietFlags)
                    QuietRow(Icons.Rounded.MobileOff, "Keep the screen off", "No Always On Display, lift/tap to wake or notification wake-ups",
                        quiet, 4, rt::setQuietFlags)
                    QuietRow(Icons.Rounded.MobileOff, "OLED saver", "Dark theme and 60 Hz",
                        quiet, 8, rt::setQuietFlags)
                    SwitchRow(Icons.Rounded.VolumeOff, "Keep this phone silent during calls",
                        "The call is heard only on the main phone", cur.muteEarpiece, { rt.setMuteEarpiece(it) })
                }
            }
            item { NetworkCard(addresses, cur.localSipPort) }
            item { SecurityCard(rt, cur.registrarPassword) }
            item { MmsCard(rt) }
            item { ActivityCard(log) }
            item {
                TextButton(onClick = openAdvanced, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Tune, null); Spacer(Modifier.width(8.dp)); Text("Advanced settings & diagnostics")
                }
            }
        }
    }
}

@Composable
private fun HeroCard(h: HealthState, takeover: Boolean) {
    val ok = takeover && h.cellularReady && h.sip.ready
    val (title, sub) = when {
        !takeover -> "Forwarding is off" to "Calls and texts stay on this phone"
        ok -> "Forwarding to your main phone" to "Calls, SMS and keypad tones are bridged"
        !h.sip.ready -> "Main phone not connected" to "Open SimBridge on the main phone and check Wi-Fi"
        h.shizuku != ShizukuConnector.State.CONNECTED -> "Shizuku isn't running" to "Calls can't be bridged until it starts"
        else -> "Setting up" to h.readinessDetail
    }
    val tint = if (ok) Good else if (!takeover) MaterialTheme.colorScheme.outline else Bad
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = tint, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                if (h.session != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("● Call in progress", color = Good, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun GroupCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
            content()
        }
    }
}

@Composable
private fun StatusRow(icon: ImageVector, title: String, detail: String, ok: Boolean, neutralWhenOk: Boolean = false) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = {
            if (!(ok && neutralWhenOk)) Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) Good else Bad))
        },
    )
}

@Composable
private fun QuietRow(icon: ImageVector, title: String, detail: String, flags: Int, bit: Int, set: (Int) -> Unit) {
    val on = flags and bit != 0
    ListItem(
        modifier = Modifier.clickable { set(if (on) flags and bit.inv() else flags or bit) }.padding(start = 24.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(title) },
        supportingContent = { Text(detail + " · with power saver on") },
        trailingContent = { Checkbox(checked = on, onCheckedChange = { set(if (it) flags or bit else flags and bit.inv()) }) },
    )
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
    )
}

@Composable
private fun NetworkCard(addresses: List<Pair<String, String>>, port: Int) {
    val ctx = LocalContext.current
    GroupCard("Main phone connects to") {
        if (addresses.isEmpty()) {
            StatusRow(Icons.Rounded.Wifi, "No network", "Connect this phone to Wi-Fi or turn on its hotspot", false)
        }
        addresses.forEach { (label, ip) ->
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(if (label.contains("hotspot")) Icons.Rounded.WifiTethering else Icons.Rounded.Wifi, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                headlineContent = { Text(label) },
                supportingContent = { Text("$ip:$port", fontFamily = FontFamily.Monospace) },
                trailingContent = {
                    IconButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("gateway", "sip:$ip:$port;transport=udp"))
                    }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                },
            )
        }
    }
}

@Composable
private fun SecurityCard(rt: GatewayRuntime, current: String) {
    val ctx = LocalContext.current
    var showQr by remember { mutableStateOf(false) }
    var qrPassword by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var legacy by remember { mutableStateOf(rt.pairing.legacyUdp) }
    var sipApp by remember { mutableStateOf<SipAppProvisioning.Account?>(null) }
    val fp = remember(current) { runCatching { rt.pairing.fingerprint() }.getOrDefault("") }
    GroupCard("Security") {
        val ok = current.isNotEmpty()
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(if (ok) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = if (ok) Good else Warn) },
            headlineContent = { Text(if (ok) "Encrypted link" else "Not paired yet") },
            supportingContent = { Text(if (ok) "TLS + SRTP. Only phones that scanned this phone's code can connect. Key ${fp.take(8)}"
                else "Pair your main phone to start") },
        )
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                qrPassword = if (current.isEmpty()) rt.resetPairing() else current
                showQr = true
            }) { Icon(Icons.Rounded.QrCode2, null); Spacer(Modifier.width(8.dp)); Text("Pair a phone") }
            TextButton(onClick = { confirmReset = true }) { Text("New key") }
        }
        TextButton(onClick = { sipApp = SipAppProvisioning.start(rt, lanAddress() ?: "this phone's IP") },
            enabled = current.isNotEmpty(), modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Use another SIP app (iPhone, computer)")
        }
        ListItem(
            modifier = Modifier.clickable { legacy = !legacy; rt.setLegacyUdp(legacy) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Rounded.Warning, null, tint = if (legacy) Warn else MaterialTheme.colorScheme.onSurfaceVariant) },
            headlineContent = { Text("Allow other SIP apps") },
            supportingContent = { Text("Plain SIP/UDP on port 5062 for apps like baresip. Unencrypted: leave off unless you need it.") },
            trailingContent = { Switch(legacy, { legacy = it; rt.setLegacyUdp(it) }) },
        )
    }
    if (showQr) {
        val uri = remember(qrPassword) { rt.pairing.pairingUri(qrPassword) }
        val bmp = remember(uri) { qrBitmap(uri, 720) }
        AlertDialog(
            onDismissRequest = { showQr = false },
            icon = { Icon(Icons.Rounded.QrCode2, null) },
            title = { Text("Scan with SimBridge") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(bmp.asImageBitmap(), "Pairing code", Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.height(12.dp))
                    Text("On your main phone: SimBridge → Settings → Pair with SIM phone. " +
                        "This code contains the password: don't share it.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton({ showQr = false }) { Text("Done") } },
            dismissButton = { TextButton({ copy(ctx, uri); Toast.makeText(ctx, "Pairing link copied", Toast.LENGTH_SHORT).show() }) { Text("Copy link") } },
        )
    }
    sipApp?.let { a ->
        val bmp = remember(a.url) { qrBitmap(a.url, 720) }
        AlertDialog(
            onDismissRequest = { sipApp = null },
            title = { Text("Another SIP app") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Linphone (iPhone, Android, Mac, Windows, Linux): Settings → Assistant → Fetch remote configuration → scan this code. It works once, for 10 minutes.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Image(bmp.asImageBitmap(), "Linphone setup code", Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.height(8.dp))
                    Text("Other apps (Acrobits, Zoiper, …):\nServer ${a.host}:${a.port} · transport TLS\nUser ${a.user} · password ${a.password}\n" +
                        "Media encryption SRTP (SDES), required · don't verify the server certificate",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("iPhone apps are paused in the background, so incoming calls only ring while the app is open, unless the app has its own push service.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton({ sipApp = null }) { Text("Done") } },
        )
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        icon = { Icon(Icons.Rounded.Warning, null) },
        title = { Text("Make a new key?") },
        text = { Text("Every paired phone is disconnected and must scan the new code. Use this if you lost a paired phone.") },
        confirmButton = { TextButton({ qrPassword = rt.resetPairing(); confirmReset = false; showQr = true }) { Text("New key") } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } },
    )
}

/** Picture and group messages need this app to be the SIM phone's SMS app. */
@Composable
private fun MmsCard(rt: GatewayRuntime) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val isDefault = remember(tick) { MmsGateway.isDefaultSmsApp(ctx) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { tick++ }
    GroupCard("Picture & group messages") {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Rounded.QrCode2, null, tint = if (isDefault) Good else MaterialTheme.colorScheme.onSurfaceVariant) },
            headlineContent = { Text(if (isDefault) "On" else "Off") },
            supportingContent = { Text(if (isDefault) "SimBridge Gateway is this phone's SMS app, so MMS and group texts reach your main phone."
                else "Android only gives MMS to the default SMS app. Make SimBridge Gateway the SMS app here; texts still reach your main phone either way.") },
        )
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isDefault) Button(onClick = {
                val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
                runCatching { launcher.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS)) }
                    .onFailure { rt.control.smsApp(true) }
            }) { Text("Make SMS app") }
            else TextButton(onClick = {
                runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            }) { Text("Change default apps") }
        }
    }
}

/** This phone's address on Wi-Fi or its hotspot (what other devices on the LAN reach). */
private fun lanAddress(): String? = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && listOf("rmnet", "ccmni", "dummy", "v4-").none { p -> it.name.startsWith(p) } }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
}.getOrNull()

private fun qrBitmap(text: String, size: Int): Bitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
private fun ActivityCard(log: List<String>) {
    var open by remember { mutableStateOf(false) }
    GroupCard("Recent activity") {
        ListItem(
            modifier = Modifier.clickable { open = !open },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.AutoMirrored.Rounded.ReceiptLong, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            headlineContent = { Text(log.lastOrNull()?.substringAfter(' ')?.take(60) ?: "Nothing yet") },
            supportingContent = { Text(if (open) "Tap to hide" else "Tap to show the log") },
            trailingContent = { Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) },
        )
        AnimatedVisibility(open) {
            Text(log.takeLast(40).reversed().joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                lineHeight = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedScaffold(onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("Advanced") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { pad -> content(Modifier.padding(pad)) }
}

private fun copy(ctx: Context, text: String) =
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("gateway", text))
