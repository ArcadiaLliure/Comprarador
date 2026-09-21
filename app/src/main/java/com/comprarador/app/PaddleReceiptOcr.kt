package com.comprarador.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * OCR neuronal íntegrament al dispositiu. El SDK i els models ONNX s'inclouen a l'APK.
 * No transmetem fotografies, imatges ni text reconegut a serveis externs.
 *
 * Es crea un motor per lectura i s'allibera fins i tot si es cancel·la la corrutina.
 */
object PaddleReceiptOcr {
    suspend fun recognize(context: Context, uri: Uri): String {
        val app = context.applicationContext
        if (!OpenCVUtils.init(app)) error("No s'ha pogut carregar OpenCV al dispositiu")
        val image = withContext(Dispatchers.IO) { decodePhoto(app, uri) }
        var paddle: PaddleOCR? = null
        try {
            paddle = PaddleOCR.create(app)
            val result = paddle.recognize(image)
            val segments = result.results.map { item ->
                val points = item.box.points
                ReceiptTextSegment(
                    text = item.text,
                    left = points.minOf { it.x }.roundToInt(),
                    top = points.minOf { it.y }.roundToInt(),
                    right = points.maxOf { it.x }.roundToInt(),
                    bottom = points.maxOf { it.y }.roundToInt()
                )
            }
            val text = ReceiptReadingOrder.reconstruct(segments)
            check(text.isNotBlank()) { "El model no ha pogut llegir cap línia" }
            return text
        } finally {
            image.recycle()
            withContext(NonCancellable) { paddle?.release() }
        }
    }

    /** Descodificació acotada i rotació explícita: BitmapFactory no aplica sempre l'EXIF. */
    private fun decodePhoto(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("No es pot obrir la imatge")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Imatge no vàlida" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2800) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val source = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("No es pot descodificar la imatge")
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90f
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180f
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270f
            else -> 0f
        }
        val mirror = orientation in setOf(
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE
        )
        if (rotation == 0f && !mirror) return source
        val matrix = Matrix().apply {
            if (mirror) postScale(-1f, 1f)
            postRotate(rotation)
        }
        return try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                .also { if (it !== source) source.recycle() }
        } catch (e: Exception) {
            source.recycle()
            throw e
        }
    }
}
