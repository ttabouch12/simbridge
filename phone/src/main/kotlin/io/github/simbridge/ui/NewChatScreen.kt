package io.github.simbridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.simbridge.ContactEntry
import io.github.simbridge.LinkCore
import io.github.simbridge.MessageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(core: LinkCore, onBack: () -> Unit, onPick: (String, String) -> Unit) {
    var all by remember { mutableStateOf(emptyList<ContactEntry>()) }
    var q by remember { mutableStateOf("") }
    var dialpad by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { core.contacts.all() }; runCatching { focus.requestFocus() } }
    val digits = q.filter { it.isDigit() || it == '+' }
    val shown = remember(all, q) {
        if (q.isBlank()) all else all.filter {
            it.name.contains(q, true) || (digits.length >= 2 && it.number.filter { c -> c.isDigit() || c == '+' }.contains(digits))
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text("New conversation") },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("To", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    placeholder = { Text("Type a name or phone number") },
                    keyboardOptions = KeyboardOptions(keyboardType = if (dialpad) KeyboardType.Phone else KeyboardType.Text),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                IconButton({ dialpad = !dialpad }) {
                    Icon(if (dialpad) Icons.Filled.Keyboard else Icons.Filled.Dialpad, "Switch keyboard")
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = pad.calculateBottomPadding() + 16.dp)) {
                if (digits.length >= 3) item {
                    PickRow(digits, "Send to $digits", null) { onPick(MessageStore.keyOf(digits), digits) }
                }
                if (!core.contacts.allowed()) item {
                    Text("Allow contacts access to pick from your contacts.", Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(shown, key = { it.name + it.number }) { c ->
                    PickRow(c.name, "${c.label} · ${c.number}", c) { onPick(MessageStore.keyOf(c.number), c.number) }
                }
            }
        }
    }
}

@Composable
private fun PickRow(title: String, subtitle: String, c: ContactEntry?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (c != null) Avatar(c.name, c.photo, 44.dp)
        else Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Filled.Dialpad, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
