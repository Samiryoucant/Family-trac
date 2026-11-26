package com.example.service

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter

data class QRCodeDiagnosticResult(
    val passed: Boolean,
    val errorType: String? = null,
    val specificErrorMessage: String? = null,
    val bitmapWidth: Int? = null,
    val bitmapHeight: Int? = null,
    val durationMs: Long = 0L,
    val logSummary: String = ""
)

/**
 * Diagnostic service that validates QR bitmap generation end-to-end (including a dummy-string
 * self-test), logs specific exceptions (WriterException, IllegalArgumentException, OOM, etc.)
 * to Logcat and QrDiagnosticLogger, and returns specific actionable error messages for the UI.
 */
object QRCodeDiagnosticService {
    private const val TAG = "QRCodeDiagnosticService"
    const val DUMMY_QR_PAYLOAD = "FAMILYTRACK:DIAGNOSTIC_TEST_8X9K2P"

    fun runSelfTest(
        dummyPayload: String = DUMMY_QR_PAYLOAD,
        sizePx: Int = 256
    ): QRCodeDiagnosticResult {
        val attemptId = QrDiagnosticLogger.newAttemptId()
        Log.i(TAG, "[$attemptId] Starting QR self-test with dummy payload len=${dummyPayload.length}, sizePx=$sizePx")
        QrDiagnosticLogger.logStage(
            attemptId = attemptId,
            stage = QrDiagnosticStage.CODE_REQUEST_START,
            status = "OK",
            message = "Running QRCodeDiagnosticService self-test on dummy payload",
            payloadLength = dummyPayload.length,
            requestedSizePx = sizePx
        )
        return diagnoseGeneration(content = dummyPayload, sizePx = sizePx, attemptId = attemptId)
    }

    fun diagnoseGeneration(
        content: String,
        sizePx: Int = 480,
        attemptId: String = QrDiagnosticLogger.newAttemptId()
    ): QRCodeDiagnosticResult {
        val startMs = SystemClock.elapsedRealtime()
        return try {
            if (content.isBlank()) {
                throw IllegalArgumentException("QR payload string is empty or blank.")
            }
            if (sizePx < 64 || sizePx > 2048) {
                throw IllegalArgumentException("QR bitmap size ($sizePx px) is out of supported range (64..2048 px).")
            }

            // Explicit ZXing encode check so WriterException / IllegalArgumentException are logged directly
            val matrix = QRCodeWriter().encode(content.trim(), BarcodeFormat.QR_CODE, sizePx, sizePx)
            if (matrix.width <= 0 || matrix.height <= 0) {
                throw IllegalStateException("ZXing BitMatrix returned invalid dimensions ${matrix.width}x${matrix.height}.")
            }

            val result = QrCodeHelper.generateQrBitmapWithDiagnostics(
                content = content,
                sizePx = sizePx,
                attemptId = attemptId
            )
            val elapsed = SystemClock.elapsedRealtime() - startMs
            val bmp: Bitmap? = result.bitmap
            if (bmp != null) {
                Log.i(TAG, "[$attemptId] QR self-test/render PASSED (${bmp.width}x${bmp.height} in ${elapsed}ms)")
                QRCodeDiagnosticResult(
                    passed = true,
                    bitmapWidth = bmp.width,
                    bitmapHeight = bmp.height,
                    durationMs = elapsed,
                    logSummary = result.diagnosticReport
                )
            } else {
                val msg = result.errorMessage ?: "QR bitmap creation returned null without an exception."
                Log.e(TAG, "[$attemptId] QR bitmap creation failed: $msg")
                QRCodeDiagnosticResult(
                    passed = false,
                    errorType = "BitmapCreationNull",
                    specificErrorMessage = msg,
                    durationMs = elapsed,
                    logSummary = result.diagnosticReport
                )
            }
        } catch (e: WriterException) {
            val elapsed = SystemClock.elapsedRealtime() - startMs
            val specificMsg = "WriterException: Unable to encode QR matrix (${e.message ?: "invalid data/format"})."
            Log.e(TAG, "[$attemptId] $specificMsg", e)
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.MATRIX_ENCODE,
                status = "FAIL",
                message = specificMsg,
                payloadLength = content.length,
                requestedSizePx = sizePx,
                durationMs = elapsed,
                error = e
            )
            QRCodeDiagnosticResult(
                passed = false,
                errorType = "WriterException",
                specificErrorMessage = specificMsg,
                durationMs = elapsed,
                logSummary = QrDiagnosticLogger.formatReport(attemptId)
            )
        } catch (e: IllegalArgumentException) {
            val elapsed = SystemClock.elapsedRealtime() - startMs
            val specificMsg = "IllegalArgumentException: ${e.message ?: "Invalid QR payload or dimensions"}."
            Log.e(TAG, "[$attemptId] $specificMsg", e)
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.PAYLOAD_VALIDATION,
                status = "FAIL",
                message = specificMsg,
                payloadLength = content.length,
                requestedSizePx = sizePx,
                durationMs = elapsed,
                error = e
            )
            QRCodeDiagnosticResult(
                passed = false,
                errorType = "IllegalArgumentException",
                specificErrorMessage = specificMsg,
                durationMs = elapsed,
                logSummary = QrDiagnosticLogger.formatReport(attemptId)
            )
        } catch (e: OutOfMemoryError) {
            val elapsed = SystemClock.elapsedRealtime() - startMs
            val specificMsg = "OutOfMemoryError: Insufficient memory to allocate ${sizePx}x${sizePx} QR graphic."
            Log.e(TAG, "[$attemptId] $specificMsg", e)
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.BITMAP_ALLOCATION,
                status = "FAIL",
                message = specificMsg,
                payloadLength = content.length,
                requestedSizePx = sizePx,
                durationMs = elapsed,
                error = e
            )
            QRCodeDiagnosticResult(
                passed = false,
                errorType = "OutOfMemoryError",
                specificErrorMessage = specificMsg,
                durationMs = elapsed,
                logSummary = QrDiagnosticLogger.formatReport(attemptId)
            )
        } catch (t: Throwable) {
            val elapsed = SystemClock.elapsedRealtime() - startMs
            val typeName = t.javaClass.simpleName
            val specificMsg = "$typeName: ${t.message ?: "Unexpected QR generation error"}."
            Log.e(TAG, "[$attemptId] $specificMsg", t)
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.ERROR,
                status = "FAIL",
                message = specificMsg,
                payloadLength = content.length,
                requestedSizePx = sizePx,
                durationMs = elapsed,
                error = t
            )
            QRCodeDiagnosticResult(
                passed = false,
                errorType = typeName,
                specificErrorMessage = specificMsg,
                durationMs = elapsed,
                logSummary = QrDiagnosticLogger.formatReport(attemptId)
            )
        }
    }
}
