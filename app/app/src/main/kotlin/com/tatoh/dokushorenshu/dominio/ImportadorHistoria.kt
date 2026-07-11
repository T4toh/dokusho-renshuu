package com.tatoh.dokushorenshu.dominio

import com.tatoh.dokushorenshu.datos.Historia
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.Oracion
import com.tatoh.dokushorenshu.datos.Parrafo

/** Pipeline de import (Plan 4b): texto plano → párrafos (una línea no vacía =
 *  un párrafo, mismo criterio que Aozora) → oraciones (SegmentadorTexto) →
 *  furigana Kuromoji persistida → Historia schema v2 guardada en el repo.
 *  Corre en ioDispatcher del caller (Kuromoji tarda en textos largos). */
class ImportadorHistoria(
    private val generadorFurigana: GeneradorFurigana,
    private val historiasRepo: HistoriasRepo,
) {
    fun importar(titulo: String, autor: String, dificultad: String, texto: String): Historia {
        require(titulo.isNotBlank()) { "título vacío" }
        require(dificultad in setOf("facil", "media", "dificil")) {
            "dificultad inválida: '$dificultad'"
        }
        val parrafos = texto.lines()
            .map { it.trim().trim('　') }
            .filter { it.isNotEmpty() }
            .map { linea ->
                Parrafo(SegmentadorTexto.segmentar(linea).map { (inicio, fin) ->
                    val oracion = linea.substring(inicio, fin)
                    Oracion(oracion, generadorFurigana.generar(oracion))
                })
            }
            .filter { it.oraciones.isNotEmpty() }
        require(parrafos.isNotEmpty()) { "texto sin contenido" }
        val historia = Historia(
            id = generarId(titulo),
            titulo = titulo.trim(),
            autor = autor.trim(),
            fuente = "import",
            licencia = "texto del usuario",
            dificultad = dificultad,
            version = 2,
            parrafos = parrafos,
        )
        historiasRepo.guardarImportada(historia)
        return historia
    }

    /** Id = título sanitizado para filesystem/URL de GUID; el japonés se
     *  conserva (los filesystems Android son UTF-8). Colisión → sufijo -2, -3… */
    private fun generarId(titulo: String): String {
        val base = titulo.trim()
            .replace(Regex("""[\\/:*?"<>|.\s　]+"""), "_")
            .trim('_')
            .ifEmpty { "importada" }
        val existentes = historiasRepo.idsLocales()
        if (base !in existentes) return base
        var n = 2
        while ("$base-$n" in existentes) n++
        return "$base-$n"
    }
}
