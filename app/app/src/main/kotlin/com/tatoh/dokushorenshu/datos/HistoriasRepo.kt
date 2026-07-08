package com.tatoh.dokushorenshu.datos

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

interface ClienteHttp {
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

/** Historias empaquetadas (assets) + descargadas (filesDir) + catálogo remoto.
 *  Una descargada con el mismo id pisa a la de assets (permite actualizar). */
class HistoriasRepo(
    private val leerAsset: (String) -> String?,
    private val listarAssetsHistorias: () -> List<String>,
    private val dirDescargas: File,
    private val http: ClienteHttp = ClienteHttpReal(),
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
        return porId.values.toList()
    }

    fun cargarHistoria(id: String): Historia? {
        val descargada = File(dirDescargas, "$id.json")
        if (descargada.exists()) {
            runCatching { ParserHistoria.parsear(descargada.readText()) }
                .onSuccess { return it }
        }
        return leerAsset("historias/$id.json")
            ?.let { runCatching { ParserHistoria.parsear(it) }.getOrNull() }
    }

    suspend fun catalogoRemoto(): Result<Catalogo> = withContext(Dispatchers.IO) {
        runCatching { ParserHistoria.parsearCatalogo(http.get(URL_CATALOGO)) }
    }

    suspend fun descargarHistoria(id: String): Result<Historia> = withContext(Dispatchers.IO) {
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
