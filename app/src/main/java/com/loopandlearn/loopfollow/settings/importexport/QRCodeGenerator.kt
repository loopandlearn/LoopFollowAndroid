package com.loopandlearn.loopfollow.settings.importexport

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QRCodeGenerator {
    /** Renders [string] as a QR code bitmap of [sizePx] square pixels, or null if it doesn't fit. */
    fun generateQRCode(
        string: String,
        sizePx: Int = 600,
        correctionLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
    ): Bitmap? {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to correctionLevel,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
        )
        val matrix = try {
            QRCodeWriter().encode(string, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        } catch (_: Exception) {
            return null
        }
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix[x, y]) foregroundColor else backgroundColor
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
