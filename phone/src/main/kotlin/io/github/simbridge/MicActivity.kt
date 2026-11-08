package io.github.simbridge

import android.app.Activity
import android.os.Bundle

/**
 * Invisible screen opened from the "microphone is off" notification. While it is in front the app
 * counts as visible, so the background service can take the microphone type; then it closes and
 * the call screen comes back.
 */
class MicActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LinkService.start(this, fromUi = true)
        finish()
    }
}
