package io.github.simbridge.ui

import io.github.simbridge.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.simbridge.Recordings

/** Call recordings saved by SimBridge; tap to play in the phone's audio player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val items = remember { Recordings.list(ctx) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.recordings)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { pad ->
        if (items.isEmpty()) Text(stringResource(R.string.no_recordings),
            Modifier.padding(pad).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(items, key = { it.uri.toString() }) { r ->
                ListItem(
                    modifier = Modifier.clickable {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(r.uri, "audio/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    },
                    leadingContent = { Icon(Icons.Filled.Mic, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(r.name.removeSuffix(".m4a")) },
                    supportingContent = { Text("${r.durationMs / 60000}:${"%02d".format((r.durationMs / 1000) % 60)} · ${r.size / 1024} KB") },
                    trailingContent = {
                        IconButton({
                            runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4")
                                .putExtra(Intent.EXTRA_STREAM, r.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), ctx.getString(R.string.share_recording))) }
                        }) { Icon(Icons.Filled.Share, stringResource(R.string.share)) }
                    },
                )
            }
        }
    }
}
