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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.simbridge.ui.LinkTheme

/** Live captions of the call, over the lock screen. Opened from the bubble or the in-call notification. */
class CaptionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = LinkApp.core(this).prefs
        setContent {
            LinkTheme {
                Scaffold { pad ->
                    Column(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeChips()
                        if (ActiveCall.current == null) Text(stringResource(R.string.tr_no_call), style = MaterialTheme.typography.bodyMedium)
                        CaptionList(Modifier.weight(1f), large = prefs.captionLarge)
                    }
                }
            }
        }
    }
}

/** Off / Captions / Captions + voice for the call in progress. */
@Composable
internal fun ModeChips() {
    val call = ActiveCall.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(TranslateMode.OFF to R.string.tr_off, TranslateMode.CAPTIONS to R.string.tr_captions,
            TranslateMode.VOICE to R.string.tr_voice).forEach { (m, label) ->
            FilterChip(Captions.mode == m, { call?.translator?.mode = m }, { Text(stringResource(label)) }, enabled = call != null)
        }
    }
}

/**
 * Status line plus the scrolling caption chat; newest at the bottom. Laid out bottom-up so a line
 * growing (its translation arriving) stays in view; follows new lines unless I scrolled back to read.
 */
@Composable
internal fun CaptionList(modifier: Modifier, large: Boolean, compact: Boolean = false) {
    val list = rememberLazyListState()
    val newest = Captions.lines.asReversed()
    LaunchedEffect(Captions.lines.size) { if (list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0) }
    Column(modifier) {
        if (Captions.info.isNotEmpty()) Text(Captions.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.fillMaxWidth(), state = list, reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp, Alignment.Bottom)) {
            items(newest, key = { it.id }) { CaptionLine(it, large, compact) }
        }
    }
}

@Composable
private fun CaptionLine(c: Caption, large: Boolean, compact: Boolean) {
    val bg = if (c.fromCaller) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer
    val fg = if (c.fromCaller) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimaryContainer
    // Big line: what I need to read (caller's words in my language; my words as I said them).
    val main = if (c.fromCaller) c.translated ?: c.heard else c.heard
    val sub = if (c.fromCaller) (if (c.translated == null) "…" else c.heard) else c.translated ?: "…"
    val big = when { compact -> 16.sp; large -> 26.sp; else -> 21.sp }
    val small = when { compact -> 13.sp; large -> 19.sp; else -> 16.sp }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (c.fromCaller) Alignment.CenterStart else Alignment.CenterEnd) {
        Card(Modifier.fillMaxWidth(0.9f), colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
            Column(Modifier.padding(if (compact) 8.dp else 12.dp)) {
                Text(main, fontSize = big, lineHeight = big * 1.25f, fontWeight = FontWeight.Medium, color = fg)
                Text(sub, fontSize = small, lineHeight = small * 1.25f, color = fg.copy(alpha = 0.85f))
            }
        }
    }
}
