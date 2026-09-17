package com.tatoh.dokushorenshu.dominio

import com.tatoh.dokushorenshu.datos.Oracion
import com.tatoh.dokushorenshu.datos.Parrafo
import com.tatoh.dokushorenshu.datos.Recorte
import com.tatoh.dokushorenshu.datos.RecortesRepo
import java.io.File

/** Texto plano → Recorte persistido. Es el pipeline de ImportadorHistoria sin el
 *  formulario: una línea no vacía = un párrafo, SegmentadorTexto corta oraciones,
 *  Kuromoji genera la furigana.
 *
 *  Corre en el ioDispatcher del caller: Kuromoji tarda en textos largos. */
class CreadorRecortes(
    private val generadorFurigana: GeneradorFurigana,
    private val recortesRepo: RecortesRepo,
    private val ahora: () -> Long = System::currentTimeMillis,
) {
    fun crear(texto: String, imagenPendiente: File? = null): Recorte {
        val parrafos = parrafosDe(texto)
        val timestamp = ahora()
        val id = recortesRepo.idLibre(timestamp)
        return recortesRepo.guardar(
            Recorte(
                id = id,
                texto = texto,
                parrafos = parrafos,
                timestamp = timestamp,
                tieneImagen = false,  // lo resuelve guardar() según la imagen pendiente
            ),
            imagenPendiente,
        )
    }

    /** Reescribe el texto de un recorte ya guardado conservando su id (y por lo tanto
     *  su imagen y su vocabulario ya registrado). Es la válvula para el OCR de texto
     *  vertical, que puede equivocar el orden de las columnas. */
    fun editar(recorte: Recorte, texto: String): Recorte =
        recortesRepo.guardar(recorte.copy(texto = texto, parrafos = parrafosDe(texto)))

    private fun parrafosDe(texto: String): List<Parrafo> {
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
        return parrafos
    }
}
