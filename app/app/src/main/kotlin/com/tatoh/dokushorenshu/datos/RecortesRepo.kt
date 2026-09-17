package com.tatoh.dokushorenshu.datos

import android.content.Context
import java.io.File

/** Recortes en `filesDir/recortes/`: `<id>.json` con el texto y `<id>.jpg` con la
 *  imagen original de la captura.
 *
 *  Repo aparte y no un quinto origen dentro de HistoriasRepo: ese ya maneja assets,
 *  descargas, catálogo remoto e importadas. */
class RecortesRepo(private val dir: File) {

    companion object {
        /** Slot fijo donde el Service deja la imagen recién capturada. Uno solo, porque
         *  `isCapturing` garantiza una captura en vuelo a la vez: si el usuario cancela,
         *  el huérfano se pisa en la próxima en vez de quedar acumulándose. */
        const val NOMBRE_PENDIENTE = "captura-pendiente.jpg"

        fun desde(contexto: Context): RecortesRepo =
            RecortesRepo(File(contexto.filesDir, "recortes"))

        fun imagenPendiente(contexto: Context): File =
            File(contexto.filesDir, NOMBRE_PENDIENTE)
    }

    private fun json(id: String) = File(dir, "$id.json")
    private fun jpg(id: String) = File(dir, "$id.jpg")

    /** android.util.Log no existe en los tests de JVM plano (sin Robolectric): logueamos
     *  best-effort porque un log que revienta no puede tumbar la lectura, que es
     *  justamente lo que este catch existe para proteger. */
    private fun log(msg: String, e: Exception) {
        try {
            android.util.Log.w("RecortesRepo", msg, e)
        } catch (_: Throwable) {
            // sin logger disponible: no hay nada más que hacer
        }
    }

    /** Descendente por timestamp: la última captura arriba. Un JSON corrupto se
     *  saltea — mismo criterio que historiasLocales(): nunca tumbar la lista entera
     *  por un archivo roto. */
    fun listar(): List<Recorte> = (dir.listFiles() ?: emptyArray())
        .filter { it.name.endsWith(".json") }
        .mapNotNull { archivo ->
            try {
                ParserRecorte.parsear(archivo.readText())
            } catch (e: Exception) {
                log("recorte corrupto, se saltea: ${archivo.name}", e)
                null
            }
        }
        .sortedByDescending { it.timestamp }

    fun cargar(id: String): Recorte? = try {
        json(id).takeIf { it.exists() }?.let { ParserRecorte.parsear(it.readText()) }
    } catch (e: Exception) {
        log("recorte corrupto: $id", e)
        null
    }

    /** Mueve `imagenPendiente` a `<id>.jpg` si viene, y escribe el JSON de forma
     *  atómica (tmp → rename), igual que guardarImportada().
     *
     *  Si el movimiento de la imagen falla, el recorte se guarda igual con
     *  `tieneImagen = false`: perder la imagen NUNCA puede costar el texto. */
    fun guardar(recorte: Recorte, imagenPendiente: File? = null): Recorte {
        dir.mkdirs()
        val conImagen = if (imagenPendiente != null && imagenPendiente.exists()) {
            moverImagen(imagenPendiente, jpg(recorte.id))
        } else {
            false
        }
        val definitivo = recorte.copy(tieneImagen = conImagen)
        val crudo = SerializadorRecorte.serializar(definitivo)
        ParserRecorte.parsear(crudo)  // round-trip antes de escribir: nunca JSON a medias
        val tmp = File(dir, "${recorte.id}.json.tmp")
        try {
            tmp.writeText(crudo)
            check(tmp.renameTo(json(recorte.id))) { "no se pudo renombrar $tmp" }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        return definitivo
    }

    private fun moverImagen(origen: File, destino: File): Boolean = try {
        // renameTo falla entre dispositivos de archivo distintos; ambos están en
        // filesDir, pero el copy+delete es el fallback barato y seguro.
        if (origen.renameTo(destino)) true
        else {
            origen.copyTo(destino, overwrite = true)
            origen.delete()
            true
        }
    } catch (e: Exception) {
        log("no se pudo guardar la imagen", e)
        false
    }

    fun borrar(id: String): Boolean {
        jpg(id).delete()
        return json(id).delete()
    }

    /** Borra solo la imagen y reescribe el recorte con `tieneImagen = false`. */
    fun quitarImagen(id: String): Boolean {
        val recorte = cargar(id) ?: return false
        jpg(id).delete()
        guardar(recorte.copy(tieneImagen = false), null)
        return true
    }

    fun archivoImagen(id: String): File? = jpg(id).takeIf { it.exists() }

    /** El id es el timestamp en millis; si ya está tomado se incrementa. Dos capturas
     *  en el mismo milisegundo es prácticamente imposible, pero pisar un recorte del
     *  usuario no es un riesgo que valga la pena correr por una línea. */
    fun idLibre(timestamp: Long): String {
        var candidato = timestamp
        while (json(candidato.toString()).exists()) candidato++
        return candidato.toString()
    }
}
