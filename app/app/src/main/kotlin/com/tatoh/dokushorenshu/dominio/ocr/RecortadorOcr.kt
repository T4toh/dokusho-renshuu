package com.tatoh.dokushorenshu.dominio.ocr

import java.io.File

/** Recorta un pedazo de una imagen guardada y le pasa el OCR.
 *
 *  Es una interfaz y no una función concreta porque su implementación toca `Bitmap`, y
 *  los tests de este repo son JVM planos (sin Robolectric) donde `Bitmap` no existe: así
 *  el ViewModel se testea con un fake y la parte Android queda del otro lado del borde,
 *  igual que `escalarRecorte` quedó como función pura para poder cubrirla en JVM. */
fun interface RecortadorOcr {
    /** [seleccion] viene en coordenadas del área DIBUJADA en pantalla, no del bitmap:
     *  quien implementa esto escala con [escalarRecorte] usando [anchoDibujado] y
     *  [altoDibujado]. Devuelve el texto reconocido, vacío si no había ninguno.
     *  Bloquea y puede lanzar (ML Kit tiene timeout de 15 s): se llama desde IO. */
    fun reconocer(archivo: File, seleccion: Recorte, anchoDibujado: Int, altoDibujado: Int): String
}
