package io.github.simbridge

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.simbridge.ui.LinkTheme

/** Live captions of the call, over the lock screen. Opened from the in-call notification. */
class CaptionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = LinkApp.core(this).prefs
        setContent { LinkTheme { CaptionScreen(prefs) } }
    }
}

@Composable
private fun CaptionScreen(prefs: Prefs) {
    var mode by remember { mutableStateOf(ActiveCall.current?.translator?.mode ?: prefs.translateMode) }
    val list = rememberLazyListState()
    LaunchedEffect(Captions.lines.size) { if (Captions.lines.isNotEmpty()) list.animateScrollToItem(Captions.lines.size - 1) }
    Scaffold { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TranslateMode.OFF to R.string.tr_off, TranslateMode.CAPTIONS to R.string.tr_captions,
                    TranslateMode.VOICE to R.string.tr_voice).forEach { (m, label) ->
                    FilterChip(mode == m, { mode = m; ActiveCall.current?.translator?.mode = m }, { Text(stringResource(label)) })
                }
            }
            if (ActiveCall.current == null) Text(stringResource(R.string.tr_no_call), style = MaterialTheme.typography.bodySmall)
            if (Captions.info.isNotEmpty()) Text(Captions.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Captions.lines) { CaptionLine(it) }
            }
        }
    }
}

@Composable
private fun CaptionLine(c: Caption) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (c.fromCaller) Alignment.CenterStart else Alignment.CenterEnd) {
        Card(Modifier.fillMaxWidth(0.88f), colors = CardDefaults.cardColors(
            containerColor = if (c.fromCaller) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text(if (c.fromCaller) c.translated else c.heard, style = MaterialTheme.typography.titleLarge)
                Text(if (c.fromCaller) c.heard else c.translated, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
