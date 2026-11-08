package io.github.simbridge.ui

import io.github.simbridge.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.simbridge.LinkCore

/** Numbers refused at the SIM phone: blocked calls never ring here, blocked texts are dropped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedScreen(core: LinkCore, onBack: () -> Unit) {
    var list by remember { mutableStateOf(core.prefs.blocked.toList()) }
    var hidden by remember { mutableStateOf(core.prefs.blockHidden) }
    var input by remember { mutableStateOf("") }
    fun save(new: List<String>) { list = new; core.prefs.blocked = new.toSet(); core.blockRulesChanged() }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.blocked_title)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.blocked_text),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                OutlinedTextField(input, { v -> input = v.filter { it.isDigit() || it in "+ " } }, singleLine = true,
                    label = { Text(stringResource(R.string.phone_number)) }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                Spacer(Modifier.width(8.dp))
                Button({ val n = input.trim(); if (n.count(Char::isDigit) >= 3 && n !in list) save(list + n); input = "" },
                    enabled = input.count(Char::isDigit) >= 3) { Text(stringResource(R.string.block)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.block_hidden), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.block_hidden_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(hidden, { hidden = it; core.prefs.blockHidden = it; core.blockRulesChanged() })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (list.isEmpty()) Text(stringResource(R.string.no_blocked), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            LazyColumn {
                items(list, key = { it }) { n ->
                    val name = remember(n) { core.contacts.lookup(n)?.name }
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Block, null, tint = MaterialTheme.colorScheme.error) },
                        headlineContent = { Text(name ?: n) },
                        supportingContent = { if (name != null) Text(n) },
                        trailingContent = { IconButton({ save(list - n) }) { Icon(Icons.Filled.Close, stringResource(R.string.unblock)) } },
                    )
                }
            }
        }
    }
}
