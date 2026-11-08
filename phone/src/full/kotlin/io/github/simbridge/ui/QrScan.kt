package io.github.simbridge.ui

import io.github.simbridge.PairedGateway

/** Scans the QR with Google's code scanner (no camera permission; runs in Play services). */
fun scanPairingCode(ctx: android.content.Context, onResult: (PairedGateway) -> Unit) {
    val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
    com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(ctx, opts).startScan()
        .addOnSuccessListener { b ->
            val g = b.rawValue?.let(PairedGateway::parse)
            if (g != null) onResult(g)
            else android.widget.Toast.makeText(ctx, "That isn't a SimBridge pairing code", android.widget.Toast.LENGTH_SHORT).show()
        }
        .addOnFailureListener {
            android.widget.Toast.makeText(ctx, "Scanner unavailable: use Paste link", android.widget.Toast.LENGTH_LONG).show()
        }
}
