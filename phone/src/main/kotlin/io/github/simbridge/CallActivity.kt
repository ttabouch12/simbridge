package io.github.simbridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.simbridge.ui.DialPad
import io.github.simbridge.ui.LinkTheme
import kotlinx.coroutines.delay

/**
 * SimBridge's own call screen, used on tablets and other devices without Google's Phone app.
 * Shows the caller, a timer, mute / speaker / keypad / translation, and answer or hang up.
 */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            LinkTheme {
                val ui by ActiveCall.ui.collectAsState()
                LaunchedEffect(ui) { if (ui == null) finish() }
                ui?.let { CallScreen(it) }
            }
        }
    }

    companion object {
        fun open(context: Context) = runCatching {
            context.startActivity(Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}

@Composable
private fun CallScreen(ui: CallUi) {
    val call = ActiveCall.shown
    var keypad by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.state) { while (ui.state == CallUi.State.ACTIVE) { now = System.currentTimeMillis(); delay(1000) } }
    val status = when (ui.state) {
        CallUi.State.RINGING -> stringResource(R.string.call_incoming)
        CallUi.State.DIALING -> stringResource(R.string.call_dialing)
        CallUi.State.ENDED -> stringResource(R.string.call_ended)
        CallUi.State.ACTIVE -> ((now - ui.since).coerceAtLeast(0) / 1000).let { "%d:%02d".format(it / 60, it % 60) }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            Box(Modifier.size(96.dp).background(Color(Notifier.avatarColor(ui.who)), CircleShape), contentAlignment = Alignment.Center) {
                Text(ui.who.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", color = Color.White, fontSize = 40.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text(ui.who.ifBlank { stringResource(R.string.call_unknown) }, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            if (ui.number.isNotBlank() && ui.number != ui.who) Text(ui.number, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.weight(1f))
            if (ui.state == CallUi.State.ACTIVE) {
                if (keypad) DialPad(Modifier.fillMaxWidth().padding(bottom = 16.dp)) { call?.dtmf(it) }
                Row(Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Toggle(Icons.Filled.MicOff, R.string.call_mute, ui.muted) { call?.toggleMute() }
                    Toggle(Icons.AutoMirrored.Filled.VolumeUp, R.string.call_speaker, ui.speaker) { call?.setSpeaker(!ui.speaker) }
                    Toggle(Icons.Filled.Dialpad, R.string.call_keypad, keypad) { keypad = !keypad }
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Toggle(Icons.Filled.Translate, R.string.translate, false) {
                        ctx.startActivity(Intent(ctx, CaptionActivity::class.java))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (ui.state == CallUi.State.RINGING) Round(Icons.Filled.Call, R.string.call_answer, Color(0xFF1E8E3E)) { call?.answerFromScreen() }
                if (ui.state != CallUi.State.ENDED) Round(Icons.Filled.CallEnd, if (ui.state == CallUi.State.RINGING) R.string.call_decline else R.string.call_end, Color(0xFFD93025)) { call?.endFromScreen() }
            }
        }
    }
}

@Composable
private fun Toggle(icon: ImageVector, label: Int, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconToggleButton(on, { onClick() }, Modifier.size(64.dp)) { Icon(icon, stringResource(label)) }
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Round(icon: ImageVector, label: Int, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick, Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = color)) {
            Icon(icon, stringResource(label), tint = Color.White)
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}
