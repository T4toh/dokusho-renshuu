package com.tatoh.dokushorenshu.dominio.ocr

/** Rectángulo en píxeles. Tipo propio y no android.graphics.Rect para que el
 *  escalado —la parte que más veces rompió— se pueda testear en JVM plano, sin
 *  Robolectric. */
data class Recorte(val left: Int, val top: Int, val ancho: Int, val alto: Int)

/** Un TextBlock de ML Kit reducido a lo único que se usa: el texto de sus líneas. */
data class BloqueOcr(val lineas: List<String>)

/** Lleva la selección hecha sobre el overlay a las coordenadas del bitmap capturado.
 *  Los dos tamaños difieren: el overlay no cubre las barras de sistema y el bitmap
 *  sí. Sin este escalado el recorte sale corrido.
 *  Lo que se sale del bitmap se recorta contra sus bordes en vez de descartarse: antes
 *  un desborde de un píxel devolvía null y el llamador terminaba haciendo OCR de la
 *  pantalla entera, tirando a la basura la selección del usuario.
 *  Devuelve null sólo si la entrada no sirve para nada: overlay de tamaño cero, o una
 *  selección que después del clamp queda degenerada (ancho o alto ≤ 0) — el llamador
 *  usa el bitmap entero. */
fun escalarRecorte(
    seleccion: Recorte,
    anchoOverlay: Int,
    altoOverlay: Int,
    anchoBitmap: Int,
    altoBitmap: Int,
): Recorte? {
    if (anchoOverlay <= 0 || altoOverlay <= 0) return null
    val escalaX = anchoBitmap.toDouble() / anchoOverlay
    val escalaY = altoBitmap.toDouble() / altoOverlay
    val left = (seleccion.left * escalaX).toInt().coerceIn(0, anchoBitmap)
    val top = (seleccion.top * escalaY).toInt().coerceIn(0, altoBitmap)
    val right = ((seleccion.left + seleccion.ancho) * escalaX).toInt().coerceIn(0, anchoBitmap)
    val bottom = ((seleccion.top + seleccion.alto) * escalaY).toInt().coerceIn(0, altoBitmap)
    if (right - left <= 0 || bottom - top <= 0) return null
    return Recorte(left, top, right - left, bottom - top)
}

/** Une la salida de ML Kit en texto plano con el formato que espera
 *  ImportadorHistoria (una línea no vacía = un párrafo).
 *
 *  Líneas del mismo bloque van pegadas sin separador: el japonés no usa espacios
 *  y en texto vertical cada columna del mismo globo es una Line.
 *
 *  ponytail: se asume que ML Kit devuelve las Line de un bloque en orden de
 *  lectura. No está garantizado para vertical derecha-a-izquierda; si en uso real
 *  aparecen columnas desordenadas, ordenar por boundingBox antes de unir. Mientras
 *  tanto el texto queda editable en ImportScreen. */
fun unirBloques(bloques: List<BloqueOcr>): String =
    bloques
        .map { bloque -> bloque.lineas.joinToString("").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
