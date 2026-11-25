package com.example.service

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeHelper {
    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        return runCatching {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val offset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565).apply {
                setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            }
        }.getOrNull()
    }

    fun decodeYuvQr(
        yuvBytes: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int
    ): String? {
        return runCatching {
            val dataWidth = maxOf(rowStride, width)
            val source = PlanarYUVLuminanceSource(yuvBytes, dataWidth, height, 0, 0, width, height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = MultiFormatReader().decode(bitmap)
            result.text
        }.getOrNull()
    }

    /**
     * Supports raw 8-char codes or "FAMILYTRACK:CODE" payload format.
     */
    fun extractPairingCode(rawQrText: String): String {
        val trimmed = rawQrText.trim()
        return if (trimmed.startsWith("FAMILYTRACK:", ignoreCase = true)) {
            trimmed.substringAfter(":").trim().uppercase()
        } else {
            trimmed.uppercase()
        }
    }
}
