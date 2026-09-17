package com.tatoh.dokushorenshu.dominio.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** La implementación real: decodifica el `.jpg` de la nota, recorta y llama al OCR.
 *
 *  Sin tests propios a propósito — toca `Bitmap` y `BitmapFactory`, que no existen en
 *  JVM plano. Lo que sí está cubierto es lo que históricamente se rompía: el escalado,
 *  con los 8 tests de `TextoOcrTest`. El resto de esta clase son tres llamadas.
 *
 *  La imagen guardada NO se toca: el recorte vive en memoria y se recicla al salir. */
class RecortadorOcrMlKit(private val ocr: OcrJapones) : RecortadorOcr {

    override fun reconocer(
        archivo: File,
        seleccion: Recorte,
        anchoDibujado: Int,
        altoDibujado: Int,
    ): String {
        val bitmap = BitmapFactory.decodeFile(archivo.path)
            ?: error("no se pudo decodificar ${archivo.name}")
        val recorte = escalarRecorte(seleccion, anchoDibujado, altoDibujado, bitmap.width, bitmap.height)
            ?: return ""
        val recortado = Bitmap.createBitmap(bitmap, recorte.left, recorte.top, recorte.ancho, recorte.alto)
        return try {
            ocr.reconocer(recortado)
        } finally {
            // createBitmap puede devolver el MISMO objeto si el recorte cubre todo el
            // bitmap: reciclarlo dos veces no rompe, pero reciclar el original mientras
            // se usa el recorte sí — por eso se libera el original sólo si son distintos.
            if (recortado !== bitmap) bitmap.recycle()
            recortado.recycle()
        }
    }
}
