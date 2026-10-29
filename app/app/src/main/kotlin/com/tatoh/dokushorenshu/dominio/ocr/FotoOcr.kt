package com.tatoh.dokushorenshu.dominio.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/** Lado máximo de la foto que se reconoce y se guarda. Una foto de 12 MP queda en
 *  ~2000 px: de sobra para el OCR de una página, y sin decodificar 48 MB de ARGB. */
private const val LADO_MAX = 3000

/** Foto de la cámara → texto reconocido + JPEG para el recorte.
 *
 *  Reconoce la foto ENTERA: recortar una zona ya existe en la nota ("Rescan area"),
 *  así que no hace falta un overlay de selección acá.
 *
 *  Si hubo texto deja en [destino] la foto derecha y achicada (JPEG 90, igual que la
 *  captura de pantalla) y lo devuelve; null si no hubo texto o si el guardado falló —
 *  perder la imagen nunca puede costar el texto. Bloquea y puede lanzar (ML Kit tiene
 *  timeout de 15 s): se llama desde IO. Sin tests propios por lo mismo que
 *  RecortadorOcrMlKit: toca `Bitmap`; el muestreo está cubierto en `MuestreoTest`. */
fun reconocerFoto(ocr: OcrJapones, foto: File, destino: File): Pair<String, File?> {
    destino.delete()  // un huérfano de una foto anterior no puede pasar por la de ahora
    val bitmap = cargarDerecha(foto)
    // Si reconocer() lanza, el bitmap NO se recicla: tras un timeout ML Kit sigue
    // leyendo sus píxeles (mismo motivo que en CapturaService). El GC lo levanta.
    val texto = ocr.reconocer(bitmap)
    return try {
        val conImagen = texto.isNotBlank() && runCatching {
            // compress() avisa que falló devolviendo false, no lanzando.
            destino.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }.getOrDefault(false)
        texto to destino.takeIf { conImagen }
    } finally {
        bitmap.recycle()
    }
}

/** Decodifica achicando y aplica la rotación EXIF: la mayoría de las cámaras guardan
 *  los píxeles como salen del sensor y anotan el giro aparte. Sin esto, una foto en
 *  vertical llega acostada al OCR y a la nota. */
private fun cargarDerecha(foto: File): Bitmap {
    val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(foto.path, limites)
    val crudo = BitmapFactory.decodeFile(
        foto.path,
        BitmapFactory.Options().apply { inSampleSize = muestreo(limites.outWidth, limites.outHeight, LADO_MAX) },
    ) ?: error("no se pudo decodificar ${foto.name}")
    val grados = when (ExifInterface(foto.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> return crudo  // ponytail: los EXIF espejados (FLIP_*) no los genera una cámara normal
    }
    val derecha = Bitmap.createBitmap(crudo, 0, 0, crudo.width, crudo.height, Matrix().apply { postRotate(grados) }, true)
    if (derecha !== crudo) crudo.recycle()
    return derecha
}
