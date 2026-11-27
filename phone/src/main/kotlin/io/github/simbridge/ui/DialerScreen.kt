package io.github.simbridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.simbridge.ContactEntry
import io.github.simbridge.LinkCore
import io.github.simbridge.MessageStore
import io.github.simbridge.R
import io.github.simbridge.Telecom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LETTERS = mapOf('2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '0' to "+")
private val T9 = LETTERS.flatMap { (d, l) -> l.map { it to d } }.toMap()

/** A USSD code such as *123# or #100*1#: sent through the SIM phone instead of called. */
fun isUssd(n: String) = n.length >= 3 && (n.first() == '*' || n.first() == '#') && n.last() == '#'

/**
 * The keypad page, laid out like Google's Phone app: the number at the top, matching contacts
 * under it, a big keypad and the green call button. USSD codes (*123#) run on the SIM phone and
 * their reply shows here, with a box to answer menus.
 */
@Composable
fun DialerScreen(core: LinkCore, initial: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var number by remember { mutableStateOf(initial) }
    var all by remember { mutableStateOf(emptyList<ContactEntry>()) }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { core.contacts.all() } }
    val st by core.control.status.collectAsState()
    val sims = st?.sims.orEmpty()
    var sim by remember { mutableStateOf(0) }
    val ussd by core.control.ussd.collectAsState()
    var ussdBusy by remember { mutableStateOf(false) }
    // Only replies to codes sent from this page (not one left from the USSD tool earlier).
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(ussd) { if (ussd != null) ussdBusy = false }

    val digits = number.filter { it.isDigit() }
    val matches = remember(number, all) {
        if (digits.length < 2) emptyList() else all.filter { c ->
            MessageStore.keyOf(c.number).contains(digits) || c.number.filter(Char::isDigit).contains(digits) ||
                c.name.split(' ').any { w -> w.length >= digits.length && w.take(digits.length).uppercase().map { T9[it] ?: it }.joinToString("") == digits }
        }.take(6)
    }

    fun press(c: Char) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); number += c }
    fun go() {
        if (number.isBlank()) return
        if (isUssd(number)) { asked = true; ussdBusy = core.control.ussd(number, sim.takeIf { it > 0 }); return }
        Telecom.call(ctx, number)
        onBack()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Text(stringResource(R.string.dial_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            // Contacts that match the digits (number or letters on the keys), like the Phone app.
            LazyColumn(Modifier.weight(1f).fillMaxWidth().widthIn(max = 560.dp)) {
                items(matches, key = { it.name + it.number }) { c ->
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { number = c.number }).padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Surface(shape = CircleShape, color = Color(io.github.simbridge.Notifier.avatarColor(c.name)), modifier = Modifier.size(40.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text(c.name.take(1).uppercase(), color = Color.White) }
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${c.label} · ${c.number}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton({ Telecom.call(ctx, c.number); onBack() }) { Icon(Icons.Filled.Call, stringResource(R.string.dial_call), tint = Color(0xFF1E8E3E)) }
                    }
                }
            }
            // USSD reply, with a box to answer a menu.
            AnimatedVisibility(asked && (ussd != null || ussdBusy), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                UssdCard(core, ussdBusy) { ussdBusy = it; if (!it) asked = false }
            }
            Text(number.ifEmpty { " " }, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
            Column(Modifier.widthIn(max = 420.dp).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (row in listOf("123", "456", "789", "*0#")) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (k in row) Key(k, LETTERS[k], Modifier.weight(1f), onLong = if (k == '0') ({ number += '+' }) else null) { press(k) }
                }
            }
            if (sims.size > 1) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sims.forEach { s -> FilterChip(sim == s.slot, { sim = if (sim == s.slot) 0 else s.slot }, { Text("SIM ${s.slot}") }) }
            }
            Row(Modifier.fillMaxWidth().widthIn(max = 420.dp).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                LargeFloatingActionButton(::go, shape = CircleShape, containerColor = Color(0xFF1E8E3E), contentColor = Color.White) {
                    Icon(if (isUssd(number)) Icons.Filled.Dialpad else Icons.Filled.Call, stringResource(R.string.dial_call), Modifier.size(36.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (number.isNotEmpty()) Box(Modifier.combinedClickable(onClick = { number = number.dropLast(1) }, onLongClick = { number = "" }).padding(16.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Backspace, stringResource(R.string.delete))
                    }
                }
            }
        }
    }
}

/** One round keypad key: the digit and its letters, shrinking a little while pressed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Key(digit: Char, letters: String?, modifier: Modifier, onLong: (() -> Unit)?, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "key")
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.aspectRatio(1.6f).scale(scale)) {
        Column(Modifier.fillMaxSize().combinedClickable(src, androidx.compose.material3.ripple(), onClick = onClick, onLongClick = onLong),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(digit.toString(), fontSize = 30.sp, style = MaterialTheme.typography.headlineMedium)
            if (letters != null) Text(letters, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UssdCard(core: LinkCore, busy: Boolean, setBusy: (Boolean) -> Unit) {
    val r by core.control.ussd.collectAsState()
    var answer by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy && r == null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.dial_ussd_wait), Modifier.padding(start = 12.dp))
            }
            r?.let { u ->
                Text(u.text.ifBlank { u.code }, style = MaterialTheme.typography.bodyLarge)
                if (u.session) Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(answer, { answer = it }, Modifier.weight(1f), singleLine = true)
                    TextButton({ setBusy(core.control.ussdReply(answer)); answer = "" }, enabled = answer.isNotBlank()) { Text(stringResource(R.string.send)) }
                }
                Row { Spacer(Modifier.weight(1f)); TextButton({ core.control.ussdCancel(); setBusy(false) }) { Text(stringResource(R.string.close)) } }
            }
        }
    }
}
