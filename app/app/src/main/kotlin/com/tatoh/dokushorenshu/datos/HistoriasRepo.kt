package com.tatoh.dokushorenshu.datos

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

fun interface ClienteHttp {
    /** Devuelve el cuerpo como texto o lanza IOException. */
    fun get(url: String): String
}

class ClienteHttpReal : ClienteHttp {
    override fun get(url: String): String {
        val conexion = URI(url).toURL().openConnection() as HttpURLConnection
        conexion.connectTimeout = 10_000
        conexion.readTimeout = 10_000
        try {
            require(conexion.responseCode == 200) { "HTTP ${conexion.responseCode} en $url" }
            return conexion.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conexion.disconnect()
        }
    }
}

/** Historias empaquetadas (assets) + descargadas (filesDir) + importadas (filesDir)
 *  + catálogo remoto. Prioridad al resolver un id: descargada > importada > asset
 *  (permite actualizar); una importada NUNCA pisa un id ya existente. */
class HistoriasRepo(
    private val leerAsset: (String) -> String?,
    private val listarAssetsHistorias: () -> List<String>,
    private val dirDescargas: File,
    private val dirImportadas: File,
    private val http: ClienteHttp = ClienteHttpReal(),
    // Inyectable solo para tests: permite compartir el TestDispatcher del ViewModel y
    // que `advanceUntilIdle()` sea determinístico (con Dispatchers.IO real, la resolución
    // del coroutine ocurre en un hilo de fondo fuera del control del scheduler virtual).
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        const val URL_CATALOGO =
            "https://raw.githubusercontent.com/T4toh/dokusho-renshuu/main/catalogo/catalogo.json"

        fun urlHistoria(id: String): String =
            "https://raw.githubusercontent.com/T4toh/dokusho-renshuu/main/catalogo/historias/$id.json"

        fun desde(contexto: Context): HistoriasRepo = HistoriasRepo(
            leerAsset = { nombre ->
                try {
                    contexto.assets.open(nombre).use { it.readBytes().decodeToString() }
                } catch (e: Exception) {
                    null
                }
            },
            listarAssetsHistorias = {
                contexto.assets.list("historias")?.toList() ?: emptyList()
            },
            dirDescargas = File(contexto.filesDir, "historias").apply { mkdirs() },
            dirImportadas = File(contexto.filesDir, "importadas").apply { mkdirs() },
        )
    }

    fun historiasLocales(): List<Historia> {
        val porId = linkedMapOf<String, Historia>()
        for (nombre in listarAssetsHistorias()) {
            leerAsset("historias/$nombre")?.let { crudo ->
                runCatching { ParserHistoria.parsear(crudo) }
                    .onSuccess { porId[it.id] = it }
            }
        }
        dirDescargas.listFiles { archivo -> archivo.extension == "json" }?.forEach { archivo ->
            runCatching { ParserHistoria.parsear(archivo.readText()) }
                .onSuccess { porId[it.id] = it }  // descargada pisa asset
        }
        dirImportadas.listFiles { archivo -> archivo.extension == "json" }?.forEach { archivo ->
            runCatching { ParserHistoria.parsear(archivo.readText()) }
                .onSuccess { porId.putIfAbsent(it.id, it) }  // importada NUNCA pisa
        }
        return porId.values.toList()
    }

    /** Catálogo embebido en assets (copiado por copiarHistorias en cada build):
     *  metadata offline-first para portada/cards, sin depender de red. */
    fun catalogoLocal(): Catalogo? =
        leerAsset("catalogo.json")?.let { runCatching { ParserHistoria.parsearCatalogo(it) }.getOrNull() }

    fun cargarHistoria(id: String): Historia? {
        for (dir in listOf(dirDescargas, dirImportadas)) {
            val archivo = File(dir, "$id.json")
            if (archivo.exists()) {
                runCatching { ParserHistoria.parsear(archivo.readText()) }
                    .onSuccess { return it }
            }
        }
        return leerAsset("historias/$id.json")
            ?.let { runCatching { ParserHistoria.parsear(it) }.getOrNull() }
    }

    fun idsLocales(): Set<String> = historiasLocales().map { it.id }.toSet()

    fun esImportada(id: String): Boolean = File(dirImportadas, "$id.json").exists()

    /** Serializa y valida con round-trip ANTES de escribir (mismo criterio que
     *  descargarHistoria: nunca guardar JSON a medias); escritura atómica.
     *  Si el id ya existe como asset o descargada, no-op: una importada NUNCA
     *  pisa (mismo criterio que historiasLocales/cargarHistoria). */
    fun guardarImportada(historia: Historia) {
        val yaExiste = File(dirDescargas, "${historia.id}.json").exists() ||
            leerAsset("historias/${historia.id}.json") != null
        if (yaExiste) return
        val crudo = SerializadorHistoria.serializar(historia)
        ParserHistoria.parsear(crudo)
        dirImportadas.mkdirs()
        val tmp = File(dirImportadas, "${historia.id}.json.tmp")
        try {
            tmp.writeText(crudo)
            check(tmp.renameTo(File(dirImportadas, "${historia.id}.json"))) {
                "no se pudo renombrar $tmp"
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    fun borrarImportada(id: String): Boolean = File(dirImportadas, "$id.json").delete()

    suspend fun catalogoRemoto(): Result<Catalogo> = withContext(ioDispatcher) {
        runCatching { ParserHistoria.parsearCatalogo(http.get(URL_CATALOGO)) }
    }

    suspend fun descargarHistoria(id: String): Result<Historia> = withContext(ioDispatcher) {
        runCatching {
            val crudo = http.get(urlHistoria(id))
            val historia = ParserHistoria.parsear(crudo)  // validar ANTES de guardar
            val tmp = File(dirDescargas, "$id.json.tmp")
            tmp.writeText(crudo)
            check(tmp.renameTo(File(dirDescargas, "$id.json"))) { "no se pudo renombrar $tmp" }
            historia
        }.onFailure {
            File(dirDescargas, "$id.json.tmp").delete()  // nunca dejar basura a medias
        }
    }
}
