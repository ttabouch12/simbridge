package io.github.simbridge.ui

import androidx.compose.material.icons.filled.Notifications
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.simbridge.LinkCore
import io.github.simbridge.Place
import io.github.simbridge.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * A small OpenStreetMap view of the SIM phone's position (3×3 tiles around it). Tap to open in
 * a maps app. Tiles come from tile.openstreetmap.org, which sees the area being viewed.
 */
@Composable
fun OsmMap(lat: Double, lon: Double, accuracy: Double) {
    val ctx = LocalContext.current
    val zoom = 16
    var tiles by remember(lat, lon) { mutableStateOf<List<Triple<Int, Int, Bitmap>>?>(null) }
    val n = 1 shl zoom
    val fx = (lon + 180) / 360 * n
    val fy = (1 - ln(tan(lat * PI / 180) + 1 / cos(lat * PI / 180)) / PI) / 2 * n
    val tx = floor(fx).toInt(); val ty = floor(fy).toInt()
    LaunchedEffect(lat, lon) {
        tiles = withContext(Dispatchers.IO) {
            (-1..1).flatMap { dy -> (-1..1).map { dx -> dx to dy } }.mapNotNull { (dx, dy) ->
                runCatching {
                    val c = URL("https://tile.openstreetmap.org/$zoom/${tx + dx}/${ty + dy}.png").openConnection() as HttpURLConnection
                    c.setRequestProperty("User-Agent", "SimBridge (https://github.com/ttabouch12/simbridge)")
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    Triple(dx, dy, c.inputStream.use { BitmapFactory.decodeStream(it) })
                }.getOrNull()
            }
        }
    }
    val metersPerPx = 156543.03 * cos(lat * PI / 180) / n   // per 256-px tile pixel
    Canvas(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)).clickable {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(SIM phone)"))) }
    }) {
        drawRect(Color(0xFFE8EAED))
        // Position of the phone inside the centre tile, in tile pixels.
        val px = (fx - tx) * 256; val py = (fy - ty) * 256
        val scale = 1.4f
        tiles?.forEach { (dx, dy, bmp) ->
            val left = (size.width / 2 + ((dx * 256) - px) * scale).toInt()
            val top = (size.height / 2 + ((dy * 256) - py) * scale).toInt()
            drawImage(bmp.asImageBitmap(), dstOffset = IntOffset(left, top), dstSize = IntSize((256 * scale).toInt(), (256 * scale).toInt()))
        }
        val c = Offset(size.width / 2, size.height / 2)
        val accPx = (accuracy / metersPerPx * scale).toFloat().coerceIn(8f, size.minDimension)
        drawCircle(Tint.Red.copy(alpha = 0.15f), accPx, c)
        drawCircle(Tint.Red.copy(alpha = 0.6f), accPx, c, style = Stroke(2f))
        drawCircle(Color.White, 11f, c)
        drawCircle(Tint.Red, 8f, c)
    }
    Text("© OpenStreetMap", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
}

/**
 * Home / work: Wi-Fi networks where both phones should use Wi-Fi instead of the hotspot. When
 * this phone sees one, the SIM phone joins it and turns its hotspot off; when the SIM phone
 * loses it, it turns the hotspot back on.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlacesSection(core: LinkCore, online: Boolean) {
    val ctx = LocalContext.current
    var places by remember { mutableStateOf(core.prefs.places) }
    var auto by remember { mutableStateOf(core.prefs.placesAuto) }
    // The place being added (empty) or edited; null when the dialog is closed.
    var editing by remember { mutableStateOf<Place?>(null) }
    fun save(p: List<Place>) {
        places = p; core.prefs.places = p
        io.github.simbridge.Places.apply(ctx, core)
        core.control.pushPlaces()
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Wifi, null, tint = Tint.Green)
                Text(stringResource(R.string.places_title), Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                Switch(auto, { auto = it; core.prefs.placesAuto = it; io.github.simbridge.Places.apply(ctx, core); core.control.pushPlaces() },
                    enabled = places.isNotEmpty())
            }
            Text(stringResource(R.string.places_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            places.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name.ifBlank { p.ssid }, style = MaterialTheme.typography.bodyLarge)
                        Text(p.ssid, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ editing = p }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                    IconButton({ save(places - p) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
                }
            }
            TextButton({ editing = Place("", "", ""); core.control.loadSavedWifi() }, enabled = online) { Text(stringResource(R.string.places_add)) }
        }
    }
    editing?.let { old ->
        val isNew = old.ssid.isEmpty()
        var name by remember(old) { mutableStateOf(old.name) }
        var ssid by remember(old) { mutableStateOf(old.ssid.ifEmpty { io.github.simbridge.Places.currentSsid(ctx) ?: "" }) }
        var pw by remember(old) { mutableStateOf(old.password) }
        var showPw by remember(old) { mutableStateOf(false) }
        val saved by core.control.savedWifi.collectAsState()
        // Networks the SIM phone already knows first, then what this phone sees nearby.
        val choices = remember(saved) { (saved + io.github.simbridge.Places.nearby(ctx)).distinct().take(12) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (isNew) R.string.places_add else R.string.edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.places_name)) }, singleLine = true)
                    OutlinedTextField(ssid, { ssid = it }, label = { Text(stringResource(R.string.places_ssid)) }, singleLine = true)
                    if (choices.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        choices.forEach { c ->
                            FilterChip(ssid == c, { ssid = c }, label = { Text(c) },
                                leadingIcon = if (c in saved) { { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) } } else null)
                        }
                    }
                    OutlinedTextField(pw, { pw = it }, label = { Text(stringResource(R.string.apn_password)) }, singleLine = true,
                        visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = { IconButton({ showPw = !showPw }) { Icon(if (showPw) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } })
                    Text(stringResource(R.string.places_pw_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton({
                    val p = Place(name.trim(), ssid.trim(), pw)
                    save(if (isNew) places + p else places.map { if (it == old) p else it }); editing = null
                }, enabled = ssid.isNotBlank()) { Text(stringResource(if (isNew) R.string.add else R.string.save)) }
            },
            dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

}

/** Recent missed calls (this phone's call log, which the SIM phone's reports fill in), with call back. */
@Composable
fun MissedCalls(core: LinkCore) {
    val ctx = LocalContext.current
    val version by core.store.version.collectAsState()
    val calls = remember(version) { io.github.simbridge.MissedCallLog.recent(ctx, 6) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PhoneMissed, null, tint = Tint.Red)
                Text(stringResource(R.string.missed_title), Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
            if (calls.isEmpty()) Text(stringResource(R.string.missed_none), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            calls.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name ?: c.number.ifBlank { stringResource(R.string.missed_hidden) }, style = MaterialTheme.typography.bodyLarge)
                        Text(android.text.format.DateUtils.getRelativeTimeSpanString(c.at).toString(),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (c.number.isNotBlank()) IconButton({
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + c.number))) }
                    }) { Icon(Icons.Filled.Call, stringResource(R.string.missed_call_back), tint = Tint.Green) }
                }
            }
        }
    }
}

/** Pick apps on the SIM phone whose notifications come here as messages. */
@Composable
fun AppNotifSection(core: LinkCore, online: Boolean) {
    val apps by core.control.apps.collectAsState()
    var open by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Set<String>?>(null) }
    Section(Icons.Filled.Notifications, stringResource(R.string.notif_title), Tint.Purple) {
        Muted(stringResource(R.string.notif_sub))
        TextButton({ core.control.loadApps(); picked = null; open = true }, enabled = online) { Text(stringResource(R.string.notif_choose)) }
    }
    if (open) {
        val list = apps
        val sel = picked ?: list?.second ?: emptySet()
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.notif_title)) },
            text = {
                if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list.first.size) { i ->
                        val (pkg, name) = list.first[i]
                        Row(Modifier.fillMaxWidth().clickable { picked = if (pkg in sel) sel - pkg else sel + pkg },
                            verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(pkg in sel, { picked = if (it) sel + pkg else sel - pkg })
                            Text(name)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ core.control.setNotifApps(sel); open = false }, enabled = list != null) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
