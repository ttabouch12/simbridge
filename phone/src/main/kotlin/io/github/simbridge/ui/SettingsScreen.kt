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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.simbridge.LinkCore
import io.github.simbridge.LinkService
import io.github.simbridge.LinkState
import io.github.simbridge.SystemSms
import io.github.simbridge.isDefaultSmsApp
import io.github.simbridge.Telecom
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(core: LinkCore, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by core.status.collectAsState()
    var pw by remember { mutableStateOf(core.prefs.password) }
    var show by remember { mutableStateOf(false) }
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
                title = { Text("Settings") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val connected = status.state == LinkState.CONNECTED
            Card(Icons.Filled.Wifi, "Connection",
                if (connected) "Connected via ${status.network} (${status.host})" else status.detail.ifBlank { "Not connected" },
                ok = connected) {
                FilledTonalButton({ core.reconnect() }) { Text("Reconnect") }
            }
            Card(Icons.Filled.Phone, "Calls in the Phone app",
                if (accountOn) "On. SIM calls ring in your Phone app, and its keypad calls through the SIM phone."
                else "Turn on the \"SimBridge\" calling account so calls ring here and you can dial from the Phone app.",
                ok = accountOn) {
                if (!accountOn) Button({
                    runCatching { ctx.startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Open calling accounts") }
            }
            Card(Icons.Filled.Mic, "Call audio",
                if (LinkService.micReady) "Ready." else "Open this app once after restarting this phone so calls have sound.",
                ok = LinkService.micReady) {}
            Card(Icons.Filled.BatteryChargingFull, "Stay connected in the background",
                if (batteryOk) "On. Android won't put SimBridge to sleep."
                else "Allow SimBridge to run in the background so calls and texts arrive while the screen is off.",
                ok = batteryOk) {
                if (!batteryOk) Button({
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Allow") }
            }
            val mirrorOk = remember(tick) { SystemSms.permitted(ctx) }
            val smsDefault = remember(tick) { isDefaultSmsApp(ctx) }
            var mirrorOn by remember { mutableStateOf(core.prefs.mirrorToSystem) }
            Card(Icons.Filled.Sms, "Default SMS app",
                if (smsDefault) "SimBridge is your SMS app. Texts are saved to the phone, \"Message\" buttons in other apps open SimBridge, and the Phone app's \"Reply with message\" sends through your SIM."
                else "Optional. Make SimBridge the phone's SMS app so \"Message\" buttons in Contacts and other apps open it, and quick replies to calls work. No computer needed.",
                ok = true) {
                if (!smsDefault) FilledTonalButton({ smsRole.launch(ctx.getSystemService(android.app.role.RoleManager::class.java)
                    .createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS)) }) { Text("Make default SMS app") }
            }
            if (!smsDefault) Card(Icons.Filled.Sms, "Show texts in your Messages app",
                when {
                    !mirrorOn -> "Off. Texts are only in SimBridge."
                    mirrorOk -> "On. Texts are also copied to your default Messages app. Reply from SimBridge: the Messages app can't send without a SIM."
                    else -> "Android only lets the default Messages app add texts. Allow SimBridge once from a computer:\n" +
                        "adb shell appops set ${ctx.packageName} WRITE_SMS allow"
                }, ok = !mirrorOn || mirrorOk) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Copy texts to Messages", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(mirrorOn, { mirrorOn = it; core.prefs.mirrorToSystem = it })
                }
            }
            Card(Icons.Filled.Key, "Gateway password",
                "The password set in the SimBridge Gateway app. Your SIM phone refuses phones without it.", ok = core.prefs.password.isNotBlank()) {
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it.trim() }, singleLine = true, label = { Text("Password") },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                Button({ core.setPassword(pw) }, enabled = pw != core.prefs.password && pw.isNotBlank()) { Text("Save") }
            }
            Text("The SIM phone is found automatically on your home Wi-Fi and on its own hotspot. No addresses to enter.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

@Composable
private fun Card(icon: ImageVector, title: String, text: String, ok: Boolean, actions: @Composable () -> Unit) {
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
