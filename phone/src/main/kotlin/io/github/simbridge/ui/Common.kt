package io.github.simbridge.ui

import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.simbridge.LinkApp
import io.github.simbridge.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun LinkTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme(primary = Color(0xFF1A73E8))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Contact photo, or a coloured circle with the first letter, like Google Messages. */
@Composable
fun Avatar(name: String, photo: Uri?, size: Dp = 48.dp, gateway: Boolean = false) {
    val ctx = LocalContext.current
    val bmp: ImageBitmap? by produceState<ImageBitmap?>(null, photo) {
        value = photo?.let { withContext(Dispatchers.IO) { LinkApp.core(ctx).contacts.photo(it)?.asImageBitmap() } }
    }
    val b = bmp
    if (b != null) {
        Image(b, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
        return
    }
    val bg = if (gateway) MaterialTheme.colorScheme.secondaryContainer else Color(Notifier.avatarColor(name))
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        val letter = name.firstOrNull { it.isLetter() }?.uppercaseChar()
        when {
            gateway -> Icon(Icons.Filled.Router, null, tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(size * 0.5f))
            letter != null -> Text(letter.toString(), color = Color.White, fontWeight = FontWeight.Medium,
                fontSize = (size.value * 0.42f).sp)
            else -> Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        }
    }
}

fun shortTime(t: Long): String {
    val now = System.currentTimeMillis()
    return when {
        DateUtils.isToday(t) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
        now - t < 6 * DateUtils.DAY_IN_MILLIS -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t))
        sameYear(t, now) -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t))
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(t))
    }
}

fun dayHeader(t: Long): String = when {
    DateUtils.isToday(t) || DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) ->
        DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
    sameYear(t, System.currentTimeMillis()) -> SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(t))
    else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(t))
}

fun clockTime(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

private fun sameYear(a: Long, b: Long): Boolean =
    Calendar.getInstance().apply { timeInMillis = a }.get(Calendar.YEAR) ==
        Calendar.getInstance().apply { timeInMillis = b }.get(Calendar.YEAR)

fun isGateway(address: String) = address == "SimBridge Gateway"
