package com.tatoh.dokushorenshu.dominio

/** Port de `historias/src/segmentador.py` (deuda del Plan 3, pagada en 4b).
 *  Spans [inicio, fin) sobre `texto`. Corta en 。！？ solo fuera de
 *  comillas/paréntesis: el diálogo 「…。…。」 queda como una sola oración.
 *  Un span "residuo" (solo puntuación/espacios) se fusiona con su vecino
 *  — cubre el 」 residual de diálogo multi-párrafo y el ？ suelto tras ！. */
object SegmentadorTexto {
    private const val FIN_ORACION = "。！？"
    private const val APERTURA = "「『（"
    private const val CIERRE = "」』）"
    private const val PUNTUACION = FIN_ORACION + APERTURA + CIERRE

    fun segmentar(texto: String): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        var inicio = 0
        var profundidad = 0
        for ((i, c) in texto.withIndex()) {
            when {
                c in APERTURA -> profundidad++
                c in CIERRE -> profundidad = maxOf(0, profundidad - 1)
                c in FIN_ORACION && profundidad == 0 -> {
                    spans.add(inicio to i + 1)
                    inicio = i + 1
                }
            }
        }
        if (texto.substring(inicio).isNotBlank()) spans.add(inicio to texto.length)
        val fusionados = mutableListOf<Pair<Int, Int>>()
        for (span in spans) {
            val ultimo = fusionados.lastOrNull()
            if (ultimo != null &&
                (esResiduo(texto.substring(span.first, span.second)) ||
                    esResiduo(texto.substring(ultimo.first, ultimo.second)))
            ) {
                fusionados[fusionados.lastIndex] = ultimo.first to span.second
            } else {
                fusionados.add(span)
            }
        }
        return fusionados
    }

    private fun esResiduo(fragmento: String): Boolean =
        fragmento.all { it in PUNTUACION || it.isWhitespace() }
}
