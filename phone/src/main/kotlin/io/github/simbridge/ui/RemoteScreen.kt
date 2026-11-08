package io.github.simbridge.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.simbridge.LinkCore
import io.github.simbridge.R
import io.github.simbridge.ScreenClient

/** Quality presets: max width (px) and H.264 bit rate. */
private val QUALITY = listOf(720 to 3_000_000, 1080 to 6_000_000, 1440 to 12_000_000)

/**
 * Live remote control of the SIM phone: hardware-decoded H.264 video, optional sound, and direct
 * touch/key input over one encrypted LAN socket (scrcpy-style). The SIM phone's panel can stay
 * dark while it is controlled. Runs only while this screen is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(core: LinkCore, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val offer by core.control.screen.collectAsState()
    var client by remember { mutableStateOf<ScreenClient?>(null) }
    var real by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var surface by remember { mutableStateOf<android.view.Surface?>(null) }
    var view by remember { mutableStateOf<SurfaceView?>(null) }
    var quality by remember { mutableStateOf(core.prefs.remoteQuality) }
    var dark by remember { mutableStateOf(core.prefs.remoteDark) }
    var sound by remember { mutableStateOf(core.prefs.remoteSound) }
    var full by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }   // "unlock", "type", "options"

    // (Re)start the stream when the options change or the drawing surface is new: each offer
    // opens one connection, so a new surface needs a new offer.
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(quality, dark, sound, surface, retry) {
        client?.close(); client = null; real = null
        if (surface == null) return@LaunchedEffect
        val (w, rate) = QUALITY[quality]
        core.control.startScreen(w, rate, (if (dark) 1 else 0) or (if (sound) 2 else 0))
    }
    DisposableEffect(Unit) { onDispose { client?.close(); core.control.stopScreen() } }
    // A dropped stream (Wi-Fi hiccup, SIM phone busy) reconnects by itself.
    LaunchedEffect(error) { if (error != null && retry < 20) { kotlinx.coroutines.delay(1500); retry++ } }
    androidx.activity.compose.BackHandler(enabled = full) { full = false }
    LaunchedEffect(offer) {
        val o = offer ?: return@LaunchedEffect
        val sf = surface ?: return@LaunchedEffect
        client?.close()
        error = null
        client = ScreenClient(core.prefs.lastHost.orEmpty(), o, sf,
            onSize = { w, h -> real = w to h },
            onEnd = { e -> error = e }).also { it.start() }
    }
    // Full screen hides the phone's own bars too.
    DisposableEffect(full) {
        val w = (ctx as? Activity)?.window
        val c = w?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (full) { c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; c?.hide(WindowInsetsCompat.Type.systemBars()) }
        onDispose { c?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    fun key(code: Int) = client?.send("k $code")

    val video: @Composable (Modifier) -> Unit = { mod ->
        Box(mod, contentAlignment = Alignment.Center) {
            val r = real
            AndroidView(
                factory = { c ->
                    SurfaceView(c).apply {
                        view = this
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) { surface = h.surface }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) { surface = null }
                        })
                    }
                },
                modifier = Modifier.aspectRatio(r?.let { it.first.toFloat() / it.second } ?: 0.45f)
                    .pointerInput(r, client) {
                        val (w, h) = r ?: return@pointerInput
                        // Every pointer change goes straight to the SIM phone, in its real pixels.
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                val p = e.changes.firstOrNull() ?: continue
                                val x = (p.position.x / size.width * w).toInt().coerceIn(0, w - 1)
                                val y = (p.position.y / size.height * h).toInt().coerceIn(0, h - 1)
                                val c = when (e.type) { PointerEventType.Press -> 'd'; PointerEventType.Release -> 'u'; PointerEventType.Move -> 'm'; else -> null }
                                if (c != null) { client?.send("$c $x $y"); p.consume() }
                            }
                        }
                    },
            )
            when {
                error != null -> Text(error!!, color = Color.White)
                real == null -> Text(stringResource(R.string.remote_waiting), color = Color.White)
            }
        }
    }
    val keys: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Key(Icons.AutoMirrored.Filled.ArrowBack, R.string.back) { key(4) }
            Key(Icons.Filled.Home, R.string.remote_home) { key(3) }
            Key(Icons.Filled.CropSquare, R.string.remote_recents) { key(187) }
            Key(Icons.Filled.VolumeDown, R.string.remote_vol_down) { key(25) }
            Key(Icons.Filled.VolumeUp, R.string.remote_vol_up) { key(24) }
            Key(Icons.Filled.PowerSettingsNew, R.string.remote_power) { key(26) }
            Key(Icons.Filled.Keyboard, R.string.remote_type) { dialog = "type" }
            Key(Icons.Filled.PhotoCamera, R.string.remote_screenshot) { view?.let { screenshot(ctx, it) } }
            Key(if (full) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, R.string.remote_full) { full = !full }
        }
    }

    // One layout for both modes, so the video surface (and its stream) survives the switch.
    Scaffold(topBar = {
        if (!full) TopAppBar(title = { Text(stringResource(R.string.dash_remote_screen)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton({ dialog = "unlock" }, enabled = client != null) { Icon(Icons.Filled.LockOpen, stringResource(R.string.remote_unlock)) }
                IconButton({ dialog = "options" }) { Icon(Icons.Filled.Tune, stringResource(R.string.remote_options)) }
            })
    }, containerColor = if (full) Color.Black else MaterialTheme.colorScheme.surface) { pad ->
        Box(Modifier.fillMaxSize().padding(if (full) androidx.compose.foundation.layout.PaddingValues(0.dp) else pad)) {
            Column(Modifier.fillMaxSize().padding(if (full) 0.dp else 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                video(Modifier.weight(1f).fillMaxWidth().background(Color.Black, RoundedCornerShape(if (full) 0.dp else 12.dp)))
                if (!full) keys()
            }
            if (full) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(8.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(28.dp)).padding(horizontal = 4.dp)) { keys() }
        }
    }

    when (dialog) {
        "unlock", "type" -> {
            val unlock = dialog == "unlock"
            var t by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(if (unlock) R.string.remote_unlock else R.string.remote_type)) },
                text = {
                    Column {
                        if (unlock) Text(stringResource(R.string.remote_unlock_text))
                        OutlinedTextField(t, { t = it.take(200) }, singleLine = true,
                            visualTransformation = if (unlock) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = if (unlock) KeyboardType.NumberPassword else KeyboardType.Text),
                            label = { Text(stringResource(if (unlock) R.string.remote_pin else R.string.remote_type)) })
                    }
                },
                confirmButton = {
                    TextButton({
                        dialog = null
                        if (unlock) doUnlock(client, real, t) else if (t.isNotEmpty()) client?.send("s $t")
                    }) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        "options" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.remote_options)) },
            text = {
                Column {
                    Text(stringResource(R.string.remote_quality), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(R.string.remote_fast, R.string.remote_balanced, R.string.remote_sharp).forEachIndexed { i, r ->
                            FilterChip(quality == i, { quality = i; core.prefs.remoteQuality = i }, label = { Text(stringResource(r)) })
                        }
                    }
                    OptionRow(stringResource(R.string.remote_dark), dark) { dark = it; core.prefs.remoteDark = it }
                    OptionRow(stringResource(R.string.remote_sound), sound) { sound = it; core.prefs.remoteSound = it }
                }
            },
            confirmButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun Key(icon: ImageVector, label: Int, onClick: () -> Unit) =
    IconButton(onClick) { Icon(icon, stringResource(label), tint = MaterialTheme.colorScheme.primary) }

@Composable
private fun OptionRow(text: String, on: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(on, onChange)
    }

/** Saves what the stream shows to Pictures/SimBridge. */
private fun screenshot(ctx: Context, v: SurfaceView) {
    if (v.width == 0 || v.height == 0) return
    val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
    PixelCopy.request(v, bmp, { r ->
        if (r != PixelCopy.SUCCESS) return@request
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SimBridge-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SimBridge")
        }
        val ok = runCatching {
            ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!.let { uri ->
                ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }.isSuccess
        Toast.makeText(ctx, ctx.getString(if (ok) R.string.remote_saved else R.string.remote_save_failed), Toast.LENGTH_SHORT).show()
    }, Handler(Looper.getMainLooper()))
}

/**
 * The lock screen is hidden from screen capture (it shows black), so unlock blind: wake, swipe
 * up, type the PIN, Enter. The PIN goes over the encrypted stream and is never stored.
 */
private fun doUnlock(c: ScreenClient?, real: Pair<Int, Int>?, pin: String) {
    c ?: return
    val (w, h) = real ?: (1080 to 2340)
    Thread {
        c.send("k 224"); Thread.sleep(400)
        val x = w / 2
        c.send("d $x ${h * 85 / 100}")
        for (i in 1..8) { c.send("m $x ${h * (85 - i * 6) / 100}"); Thread.sleep(15) }
        c.send("u $x ${h * 37 / 100}"); Thread.sleep(600)
        if (pin.isNotEmpty()) { c.send("s $pin"); Thread.sleep(200); c.send("k 66") }
    }.start()
}
