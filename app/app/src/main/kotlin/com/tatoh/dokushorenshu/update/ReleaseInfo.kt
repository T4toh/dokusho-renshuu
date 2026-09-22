package com.tatoh.dokushorenshu.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lo que hace falta de una release para ofrecerla como update. Sin hash no se instala nada. */
data class ReleaseInfo(
    val version: Version,
    val apkUrl: String,
    /** Hex en minúsculas, sin el prefijo `sha256:` que trae la API. */
    val sha256: String,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parsea `GET /repos/{owner}/{repo}/releases` (lista) y devuelve la release de
         *  mayor versión que sirva: tag que parsea (descarta `db-vN`), no draft, con un
         *  asset `.apk` que traiga `digest` sha256 y URL https. Lanza si el JSON no es
         *  una lista de releases: el checker lo atrapa. */
        fun desdeReleases(cuerpo: String): ReleaseInfo? =
            json.decodeFromString<List<ReleaseJson>>(cuerpo)
                .mapNotNull(::desdeRelease)
                .maxByOrNull { it.version }

        private fun desdeRelease(r: ReleaseJson): ReleaseInfo? {
            if (r.draft) return null
            val version = Version.parse(r.tag_name) ?: return null
            val apk = r.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
            val digest = apk.digest ?: return null
            if (!digest.startsWith("sha256:")) return null
            if (!apk.browser_download_url.startsWith("https://")) return null
            return ReleaseInfo(version, apk.browser_download_url, digest.removePrefix("sha256:").lowercase())
        }
    }
}

@Serializable
private data class ReleaseJson(
    val tag_name: String,
    val draft: Boolean = false,
    val assets: List<AssetJson> = emptyList(),
)

@Serializable
private data class AssetJson(
    val name: String,
    val browser_download_url: String,
    val digest: String? = null,
)
