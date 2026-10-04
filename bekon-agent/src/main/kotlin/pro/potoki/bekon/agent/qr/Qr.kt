package pro.potoki.bekon.agent.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Render a URL (or any string) as a QR bitmap. */
fun qrBitmap(content: String, size: Int = 512): Bitmap {
    val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val w = bits.width
    val h = bits.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val off = y * w
        for (x in 0 until w) {
            pixels[off + x] = if (bits[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.RGB_565)
}
