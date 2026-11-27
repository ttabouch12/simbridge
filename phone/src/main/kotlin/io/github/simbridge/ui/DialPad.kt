package io.github.simbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.simbridge.R

private val KEYS = listOf("123", "456", "789", "*0#")

/** A phone keypad: 12 round keys. */
@Composable
fun DialPad(modifier: Modifier = Modifier, onKey: (Char) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in KEYS) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (k in row) FilledTonalButton({ onKey(k) }, Modifier.size(64.dp)) { Text(k.toString(), fontSize = 24.sp) }
        }
    }
}

/** Number pad that calls through the SIM phone, for devices without a Phone app. */
@Composable
fun DialDialog(initial: String = "", onCall: (String) -> Unit, onDismiss: () -> Unit) {
    var number by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dial_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(number.ifEmpty { " " }, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    IconButton({ number = number.dropLast(1) }, enabled = number.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Backspace, stringResource(R.string.delete))
                    }
                }
                DialPad(Modifier.fillMaxWidth()) { number += it }
            }
        },
        confirmButton = {
            FilledTonalButton({ onCall(number); onDismiss() }, enabled = number.isNotBlank()) {
                Icon(Icons.Filled.Call, null); Text(stringResource(R.string.dial_call))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
