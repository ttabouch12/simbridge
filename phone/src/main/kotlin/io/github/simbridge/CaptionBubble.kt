package io.github.simbridge

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.simbridge.ui.LinkTheme

/**
 * Floating translate button over the call screen (needs "Display over other apps").
 * Tap: a small chat window with the mode buttons; drag to move. Main thread only.
 */
class CaptionBubble(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = 360 }

    fun show() {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        val o = OverlayOwner().also { owner = it }
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
        }
        v.setContent { LinkTheme { Bubble(::move, ::openFull) } }
        if (runCatching { wm.addView(v, lp) }.isSuccess) view = v else o.destroy()
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        owner?.destroy(); owner = null
    }

    private fun move(dx: Float, dy: Float) {
        val v = view ?: return
        lp.x = (lp.x - dx.toInt()).coerceAtLeast(0)   // gravity END: x counts from the right edge
        lp.y = (lp.y + dy.toInt()).coerceAtLeast(0)
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun openFull() {
        runCatching { ctx.startActivity(Intent(ctx, CaptionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@androidx.compose.runtime.Composable
private fun Bubble(move: (Float, Float) -> Unit, openFull: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val drag = Modifier.pointerInput(Unit) { detectDragGestures { change, d -> change.consume(); move(d.x, d.y) } }
    if (!open) {
        Surface(drag.size(56.dp).clickable { open = true }, shape = CircleShape,
            color = MaterialTheme.colorScheme.primary, shadowElevation = 6.dp) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Translate, stringResource(R.string.translate), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    } else {
        Surface(Modifier.width(310.dp).heightIn(max = 420.dp), shape = RoundedCornerShape(20.dp), shadowElevation = 8.dp) {
            Column(Modifier.padding(10.dp)) {
                Row(drag.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.translate), Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleMedium)
                    IconButton(openFull) { Icon(Icons.Filled.OpenInFull, null) }
                    IconButton({ open = false }) { Icon(Icons.Filled.Close, null) }
                }
                ModeChips()
                CaptionList(Modifier.weight(1f, fill = false).padding(top = 6.dp), large = false, compact = true)
            }
        }
    }
}

/** Lifecycle for a Compose view that lives in a window instead of an activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}
