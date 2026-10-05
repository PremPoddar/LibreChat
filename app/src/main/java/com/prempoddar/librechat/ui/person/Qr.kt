package com.prempoddar.librechat.ui.person

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.crypto.ContactCard
import com.prempoddar.librechat.engine.Messenger
import kotlinx.coroutines.launch

fun qrBitmap(text: String, size: Int = 640, dark: Int = 0xFF0B0F0D.toInt()): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) dark else 0 }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Scans someone's LibreChat code: adds them as a verified contact, or confirms who they are. */
@Composable
fun rememberContactScanner(expectedPeer: String? = null, onResult: (Messenger.VerifyResult) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val card = ContactCard.decode(text)
        if (card == null) {
            Toast.makeText(context, "That isn't a LibreChat code", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        if (expectedPeer != null && card.identity.peerId.value != expectedPeer) {
            Toast.makeText(context, "That code belongs to someone else. Not verified.", Toast.LENGTH_LONG).show()
            onResult(Messenger.VerifyResult.MISMATCH)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val r = AppGraph.messenger.verify(card.identity, card.name)
            Toast.makeText(
                context,
                when (r) {
                    Messenger.VerifyResult.VERIFIED -> "${card.name} verified"
                    Messenger.VerifyResult.ADDED -> "${card.name} added and verified"
                    Messenger.VerifyResult.MISMATCH -> "The keys don't match. Not verified."
                },
                Toast.LENGTH_LONG,
            ).show()
            onResult(r)
        }
    }
    return {
        launcher.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan their LibreChat code")
                .setBeepEnabled(false).setOrientationLocked(true),
        )
    }
}
