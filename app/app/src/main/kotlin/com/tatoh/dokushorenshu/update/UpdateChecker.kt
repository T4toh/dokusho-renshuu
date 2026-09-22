package com.tatoh.dokushorenshu.update

import android.util.Log
import com.tatoh.dokushorenshu.datos.progreso.PrefsRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/** Pregunta a GitHub si hay una release de la app más nueva que la instalada.
 *  Nunca lanza: sin red, rate limit, JSON raro o release sin digest → `null`.
 *  Excepción: `CancellationException` se propaga. */
class UpdateChecker(
    private val versionInstalada: String,
    private val prefs: PrefsRepo,
    private val fetch: suspend (String) -> String = ::fetchHttp,
    private val ahora: () -> Long = System::currentTimeMillis,
) {
    /** La release nueva, o `null` si no hay, si el último chequeo fue hace menos de
     *  24 h, o si algo falló. */
    suspend fun chequear(): ReleaseInfo? {
        val local = Version.parse(versionInstalada) ?: run {
            Log.w(TAG, "versionName \"$versionInstalada\" no parsea")
            return null
        }
        val t = ahora()
        val ultimo = prefs.ultimoChequeoUpdate()
        if (ultimo != null && t - ultimo < INTERVALO_MS) return null
        // Se graba ANTES del resultado: una falla también cuenta como intento y se
        // reintenta al día siguiente. Evita martillar la API sin red.
        prefs.setUltimoChequeoUpdate(t)
        return try {
            val info = ReleaseInfo.desdeReleases(fetch(URL))
            when {
                info == null -> { Log.w(TAG, "ninguna release con .apk y digest"); null }
                info.version > local -> info
                else -> { Log.i(TAG, "sin novedades (${info.version} vs $local)"); null }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo chequear la release", e)
            null
        }
    }

    companion object {
        private const val TAG = "Updater"
        /** Lista, no `/latest`: `latest` excluye prereleases y en este repo devuelve `db-v2`. */
        const val URL = "https://api.github.com/repos/T4toh/dokusho-renshuu/releases?per_page=20"
        const val INTERVALO_MS = 24L * 60 * 60 * 1000

        /** GitHub rechaza requests sin `User-Agent` con 403. Distinto de `ClienteHttpReal`
         *  (HistoriasRepo) solo por los headers; no vale la pena generalizar aquél. */
        suspend fun fetchHttp(url: String): String = withContext(Dispatchers.IO) {
            val conexion = URI(url).toURL().openConnection() as HttpURLConnection
            conexion.connectTimeout = 10_000
            conexion.readTimeout = 15_000
            conexion.setRequestProperty("User-Agent", "dokusho-renshuu")
            conexion.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                require(conexion.responseCode == 200) { "HTTP ${conexion.responseCode} en $url" }
                conexion.inputStream.use { it.readBytes().decodeToString() }
            } finally {
                conexion.disconnect()
            }
        }
    }
}
