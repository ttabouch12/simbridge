package io.github.simbridge.ui

import io.github.simbridge.PairedGateway

/**
 * F-Droid build: no Google scanner. Any camera or QR app that opens the simbridge://pair link
 * hands it to SimBridge (MainActivity handles the link and asks to confirm).
 */
fun scanPairingCode(ctx: android.content.Context, @Suppress("UNUSED_PARAMETER") onResult: (PairedGateway) -> Unit) {
    android.widget.Toast.makeText(ctx, "Scan the code with your camera app: it opens SimBridge. Or use Paste link.",
        android.widget.Toast.LENGTH_LONG).show()
}
