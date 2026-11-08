package io.github.simbridge.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.simbridge.R
import io.github.simbridge.Screen

/** Messages · Dashboard · Settings. */
@Composable
fun BottomTabs(current: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar {
        NavigationBarItem(current == Screen.List, { onSelect(Screen.List) },
            icon = { Icon(Icons.AutoMirrored.Filled.Chat, null) }, label = { Text(stringResource(R.string.tab_messages)) })
        NavigationBarItem(current == Screen.SimPhone, { onSelect(Screen.SimPhone) },
            icon = { Icon(Icons.Filled.PhoneAndroid, null) }, label = { Text(stringResource(R.string.tab_dashboard)) })
        NavigationBarItem(current == Screen.Settings, { onSelect(Screen.Settings) },
            icon = { Icon(Icons.Filled.Settings, null) }, label = { Text(stringResource(R.string.tab_settings)) })
    }
}
