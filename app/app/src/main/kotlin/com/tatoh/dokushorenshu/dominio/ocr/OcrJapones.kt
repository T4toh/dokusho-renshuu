package com.tatoh.dokushorenshu.dominio.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/** OCR japonés on-device. El TextRecognizer es caro de crear: una instancia por
 *  app (vive en el Contenedor). */
class OcrJapones(
    private val reconocedor: TextRecognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
) {
    /** Bloquea hasta tener el texto. ML Kit devuelve un Task asíncrono y Tasks.await()
     *  LANZA si se llama desde el main thread — el llamador (ScreenCaptureService)
     *  tiene que invocarlo desde un hilo de fondo.
     *  Timeout de 15 s: Tasks.await() lanza TimeoutException si el modelo se traba, o
     *  ExecutionException si el reconocimiento falla — esta función no las atrapa a
     *  propósito; el llamador (que corre en un hilo de fondo) decide si sustituye por
     *  texto vacío. */
    fun reconocer(bitmap: Bitmap): String {
        val entrada = InputImage.fromBitmap(bitmap, 0)
        val resultado = Tasks.await(reconocedor.process(entrada), 15, TimeUnit.SECONDS)
        return unirBloques(
            resultado.textBlocks.map { bloque -> BloqueOcr(bloque.lines.map { it.text }) }
        )
    }

    fun cerrar() = reconocedor.close()
}
