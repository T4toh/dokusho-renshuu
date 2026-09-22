# Updater in-app — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dokusho avisa cuando hay una release nueva en GitHub y la descarga, verifica (sha256) e instala desde la app, con gate de 24 h.

**Architecture:** Paquete `com.tatoh.dokushorenshu.update` con cuatro piezas: `Version`/`ReleaseInfo` (semver con prerelease + parser del JSON de releases, Kotlin puro), `UpdateChecker` (gate 24 h en la tabla `prefs` de Room + fetch HTTP), `Instalador` (port de `UpdaterPlugin.kt` de Pulpero: DownloadManager, hash, intent del instalador) y `UpdateViewModel` + `UpdateBanner` (máquina de estados en un ViewModel a nivel Activity para sobrevivir rotaciones; el composable solo pinta). Se enchufa en `MainActivity` arriba del `NavHost`. Además: versionado real en gradle, firma fijada con `key.properties`, `release.sh` que valida antes de buildear.

**Tech Stack:** Kotlin 2.3, Compose M3 (BOM 2026.06.01), Room 2.8, kotlinx.serialization 1.11, `HttpURLConnection`, `DownloadManager`. Tests: JUnit 4 + Robolectric 4.16. Sin dependencias nuevas salvo `androidx.lifecycle:lifecycle-runtime-compose` (ya está en el árbol transitivo, misma versión 2.10.0 que `lifecycle-viewmodel-compose`).

**Spec:** `docs/superpowers/specs/2026-09-22-updater-app-design.md`

## Global Constraints

- Proyecto gradle en `app/` (rootProject), módulo Android en `app/app/`. Todos los comandos `./gradlew` se corren desde `app/`.
- `minSdk = 26`, `compileSdk = targetSdk = 36`, JDK 17+ (hay JDK 21 en `/opt/homebrew/opt/openjdk@21`).
- Textos de UI en **inglés**; comentarios, nombres y docs en español rioplatense como el resto del repo.
- Nunca lanzar excepciones desde el checker: cualquier falla es `null` + `Log.w`.
- Repo GitHub: `T4toh/dokusho-renshuu`. Endpoint: `https://api.github.com/repos/T4toh/dokusho-renshuu/releases?per_page=20`. **No** usar `/releases/latest` (devuelve `db-v2`).
- Versión para la beta.5: `versionName = "0.1.0-beta.5"`, `versionCode = 5`.
- Firma: copia de `~/.android/debug.keystore` en `~/dokusho-release.jks` (alias `androiddebugkey`, pass `android`). `app/key.properties` y `*.jks` **nunca** entran al repo.
- Commits sin `Co-Authored-By`. Rama `feat/updater`. Nadie corre `gh release create` ni crea tags: eso lo hace el usuario.
- Los tests se corren con `./gradlew :app:testDebugUnitTest --tests '<clase>'` (Robolectric ya configurado, `maxHeapSize 2g`).

---

### Task 1: `Version` y `ReleaseInfo` (Kotlin puro)

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/update/Version.kt`
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/update/ReleaseInfo.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/update/VersionTest.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/update/ReleaseInfoTest.kt`

**Interfaces:**
- Produces: `data class Version(major: Int, minor: Int, patch: Int, prerelease: List<String>) : Comparable<Version>` con `companion fun parse(texto: String): Version?`; `data class ReleaseInfo(version: Version, apkUrl: String, sha256: String)` con `companion fun desdeReleases(cuerpo: String): ReleaseInfo?`.

- [ ] **Step 1: Test de `Version`**

```kotlin
package com.tatoh.dokushorenshu.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    private fun v(s: String) = Version.parse(s) ?: error("no parsea: $s")

    @Test
    fun `parsea con y sin v, con y sin prerelease`() {
        assertEquals(Version(0, 1, 0), Version.parse("0.1.0"))
        assertEquals(Version(0, 1, 0), Version.parse("v0.1.0"))
        assertEquals(Version(0, 1, 0, listOf("beta", "4")), Version.parse("v0.1.0-beta.4"))
        assertEquals(Version(1, 2, 3, listOf("rc", "1")), Version.parse(" 1.2.3-rc.1 "))
    }

    @Test
    fun `rechaza lo que no es una version de la app`() {
        assertNull(Version.parse("db-v2"))
        assertNull(Version.parse(""))
        assertNull(Version.parse("1.2"))
        assertNull(Version.parse("1.2.3+4"))   // metadata de build: no se usa en este repo
        assertNull(Version.parse("1.2.3-"))
    }

    @Test
    fun `orden por major minor patch`() {
        assertTrue(v("1.0.0") > v("0.9.9"))
        assertTrue(v("0.2.0") > v("0.1.9"))
        assertTrue(v("0.1.1") > v("0.1.0"))
        assertEquals(0, v("0.1.0").compareTo(v("v0.1.0")))
    }

    @Test
    fun `sin prerelease es mayor que con prerelease`() {
        assertTrue(v("0.1.0") > v("0.1.0-beta.9"))
        assertTrue(v("0.1.0-beta.1") < v("0.1.0"))
    }

    @Test
    fun `prereleases se comparan por identificador`() {
        assertTrue(v("0.1.0-beta.5") > v("0.1.0-beta.4"))
        assertTrue(v("0.1.0-beta.10") > v("0.1.0-beta.9"))   // numérico, no lexicográfico
        assertTrue(v("1.2.0-rc.1") > v("1.2.0-beta.3"))     // alfanumérico
        assertTrue(v("1.0.0-alpha.1") > v("1.0.0-alpha"))   // prefijo más corto es menor
        assertTrue(v("1.0.0-alpha.beta") > v("1.0.0-alpha.1")) // numérico < alfanumérico
    }

    @Test
    fun `toString vuelve al texto sin v`() {
        assertEquals("0.1.0-beta.5", v("v0.1.0-beta.5").toString())
        assertEquals("1.0.0", v("1.0.0").toString())
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests 'com.tatoh.dokushorenshu.update.VersionTest'`
Expected: falla de compilación, `Unresolved reference: Version`.

- [ ] **Step 3: Implementar `Version.kt`**

```kotlin
package com.tatoh.dokushorenshu.update

/** Semver 2.0 sin metadata de build. Los tags de las releases son `vX.Y.Z-beta.N`;
 *  `db-vN` (releases del diccionario) no parsea a propósito. */
data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String> = emptyList(),
) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
            .let { if (it != 0) return it }
        // Semver §11: a igual X.Y.Z, la versión SIN prerelease es la mayor.
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return other.prerelease.size.compareTo(prerelease.size)
        }
        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val a = prerelease[i]
            val b = other.prerelease[i]
            val na = a.toIntOrNull()
            val nb = b.toIntOrNull()
            val c = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1 // numérico < alfanumérico
                nb != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (prerelease.isEmpty()) "" else "-" + prerelease.joinToString(".")

    companion object {
        private val PATRON = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$""")

        /** `v` opcional, `X.Y.Z` o `X.Y.Z-ident(.ident)*`. Cualquier otra cosa → null. */
        fun parse(texto: String): Version? {
            val m = PATRON.matchEntire(texto.trim()) ?: return null
            val (major, minor, patch, pre) = m.destructured
            return Version(
                major.toInt(), minor.toInt(), patch.toInt(),
                if (pre.isEmpty()) emptyList() else pre.split('.'),
            )
        }
    }
}
```

- [ ] **Step 4: Correr y ver que pasa**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests 'com.tatoh.dokushorenshu.update.VersionTest'`
Expected: `BUILD SUCCESSFUL`, 6 tests pasan.

- [ ] **Step 5: Test de `ReleaseInfo`**

El JSON imita la respuesta real de `GET /releases`: una beta con digest, una beta más vieja, `db-v2`, un draft con versión mayor, y una release sin digest con versión mayor.

```kotlin
package com.tatoh.dokushorenshu.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseInfoTest {
    private fun release(
        tag: String,
        draft: Boolean = false,
        assets: String = "",
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":true,"assets":[$assets]}"""

    private fun apk(tag: String, digest: String? = "sha256:ABCDEF0123", url: String = "https://github.com/T4toh/dokusho-renshuu/releases/download/$tag/dokusho-renshuu-$tag.apk") =
        "{\"name\":\"dokusho-renshuu-$tag.apk\",\"browser_download_url\":\"$url\"" +
            (if (digest != null) ",\"digest\":\"$digest\"" else "") + "}"

    @Test
    fun `elige la mayor version con apk y digest`() {
        val json = "[" + listOf(
            release("v0.1.0-beta.4", assets = apk("v0.1.0-beta.4")),
            release("v0.1.0-beta.5", assets = apk("v0.1.0-beta.5", digest = "sha256:DEADBEEF")),
            release("v0.1.0-beta.3", assets = apk("v0.1.0-beta.3")),
        ).joinToString(",") + "]"
        val info = ReleaseInfo.desdeReleases(json)!!
        assertEquals(Version(0, 1, 0, listOf("beta", "5")), info.version)
        assertEquals("https://github.com/T4toh/dokusho-renshuu/releases/download/v0.1.0-beta.5/dokusho-renshuu-v0.1.0-beta.5.apk", info.apkUrl)
        assertEquals("deadbeef", info.sha256) // sin prefijo, en minúsculas
    }

    @Test
    fun `ignora db-vN, drafts, sin apk, sin digest y http plano`() {
        val json = "[" + listOf(
            release("db-v2", assets = """{"name":"diccionario-v2.db","browser_download_url":"https://x/y.db","digest":"sha256:00"}"""),
            release("v9.0.0", draft = true, assets = apk("v9.0.0")),
            release("v8.0.0"),                                   // sin assets
            release("v7.0.0", assets = apk("v7.0.0", digest = null)),
            release("v6.0.0", assets = apk("v6.0.0", digest = "md5:00")),
            release("v5.0.0", assets = apk("v5.0.0", url = "http://github.com/x.apk")),
            release("v0.1.0-beta.4", assets = apk("v0.1.0-beta.4")),
        ).joinToString(",") + "]"
        assertEquals(Version(0, 1, 0, listOf("beta", "4")), ReleaseInfo.desdeReleases(json)!!.version)
    }

    @Test
    fun `sin candidatas devuelve null`() {
        assertNull(ReleaseInfo.desdeReleases("[]"))
        assertNull(ReleaseInfo.desdeReleases("[" + release("db-v2") + "]"))
    }

    @Test
    fun `campos desconocidos no molestan`() {
        val json = """[{"tag_name":"v1.0.0","draft":false,"html_url":"x","author":{"login":"t"},"assets":[${apk("v1.0.0")}]}]"""
        assertEquals(Version(1, 0, 0), ReleaseInfo.desdeReleases(json)!!.version)
    }
}
```

- [ ] **Step 6: Correr y ver que falla**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests 'com.tatoh.dokushorenshu.update.ReleaseInfoTest'`
Expected: `Unresolved reference: ReleaseInfo`.

- [ ] **Step 7: Implementar `ReleaseInfo.kt`**

```kotlin
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
```

- [ ] **Step 8: Correr todos los tests del paquete**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests 'com.tatoh.dokushorenshu.update.*'`
Expected: `BUILD SUCCESSFUL`, 10 tests pasan.

- [ ] **Step 9: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/update/Version.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/update/ReleaseInfo.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/update/VersionTest.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/update/ReleaseInfoTest.kt
git commit -m "feat(update): Version semver con prerelease y parser de releases de GitHub"
```

---

### Task 2: `UpdateChecker` con gate de 24 h en `PrefsRepo`

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/progreso/PrefsRepo.kt`
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/update/UpdateChecker.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/update/UpdateCheckerTest.kt`

**Interfaces:**
- Consumes: `Version.parse`, `ReleaseInfo.desdeReleases` (Task 1); `PrefsRepo(dao: ProgresoDao)` con `dao.pref(clave): String?` y `dao.guardarPref(Pref(clave, valor))`.
- Produces: `PrefsRepo.ultimoChequeoUpdate(): Long?`, `PrefsRepo.setUltimoChequeoUpdate(millis: Long)`; `class UpdateChecker(versionInstalada: String, prefs: PrefsRepo, fetch: suspend (String) -> String = ::fetchHttp, ahora: () -> Long = System::currentTimeMillis)` con `suspend fun chequear(): ReleaseInfo?`.

- [ ] **Step 1: Test**

```kotlin
package com.tatoh.dokushorenshu.update

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tatoh.dokushorenshu.datos.progreso.PrefsRepo
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDb
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {
    private val prefs = PrefsRepo(
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgresoDb::class.java)
            .allowMainThreadQueries().build().dao()
    )

    private val beta5 = """[{"tag_name":"v0.1.0-beta.5","draft":false,"assets":[{"name":"a.apk",
        "browser_download_url":"https://github.com/T4toh/dokusho-renshuu/releases/download/v0.1.0-beta.5/a.apk",
        "digest":"sha256:ab"}]}]"""

    private fun checker(
        instalada: String = "0.1.0-beta.4",
        ahora: Long = 1_000_000L,
        fetch: suspend (String) -> String = { beta5 },
    ) = UpdateChecker(instalada, prefs, fetch, { ahora })

    @Test
    fun `hay version nueva`() = runTest {
        val info = checker().chequear()
        assertNotNull(info)
        assertEquals("0.1.0-beta.5", info!!.version.toString())
    }

    private var urlPedida: String? = null
    private val fetchQueAnota: suspend (String) -> String = { urlPedida = it; beta5 }

    @Test
    fun `pide la lista de releases, no latest`() = runTest {
        checker(fetch = fetchQueAnota).chequear()
        assertEquals("https://api.github.com/repos/T4toh/dokusho-renshuu/releases?per_page=20", urlPedida)
    }

    @Test
    fun `misma version o menor no avisa`() = runTest {
        assertNull(checker(instalada = "0.1.0-beta.5").chequear())
        prefs.setUltimoChequeoUpdate(0)
        assertNull(checker(instalada = "0.1.0").chequear()) // 0.1.0 > 0.1.0-beta.5
    }

    @Test
    fun `gate de 24 horas`() = runTest {
        assertNotNull(checker(ahora = 1_000_000L).chequear())
        assertNull(checker(ahora = 1_000_000L + UpdateChecker.INTERVALO_MS - 1).chequear())
        assertNotNull(checker(ahora = 1_000_000L + UpdateChecker.INTERVALO_MS).chequear())
    }

    @Test
    fun `una falla tambien cuenta como intento`() = runTest {
        var llamadas = 0
        val roto: suspend (String) -> String = { llamadas++; throw java.io.IOException("sin red") }
        assertNull(checker(fetch = roto).chequear())
        assertNull(checker(fetch = roto).chequear()) // dentro del gate: no vuelve a pegar
        assertEquals(1, llamadas)
        assertEquals(1_000_000L, prefs.ultimoChequeoUpdate())
    }

    @Test
    fun `json raro o version instalada invalida devuelven null`() = runTest {
        assertNull(checker(fetch = { "<html>rate limit</html>" }).chequear())
        prefs.setUltimoChequeoUpdate(0)
        assertNull(checker(instalada = "0.1.0", fetch = { "[]" }).chequear())
        prefs.setUltimoChequeoUpdate(0)
        assertNull(checker(instalada = "lo que sea").chequear())
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests 'com.tatoh.dokushorenshu.update.UpdateCheckerTest'`
Expected: `Unresolved reference: UpdateChecker` (y `ultimoChequeoUpdate`).

- [ ] **Step 3: Agregar los dos métodos a `PrefsRepo`**

En `PrefsRepo.kt`, antes de `private companion object`:

```kotlin
    /** Epoch millis del último chequeo de update de la app (gate de 24 h del updater). */
    suspend fun ultimoChequeoUpdate(): Long? = dao.pref(CLAVE_UPDATE_CHECK)?.toLongOrNull()

    suspend fun setUltimoChequeoUpdate(millis: Long) {
        dao.guardarPref(Pref(CLAVE_UPDATE_CHECK, millis.toString()))
    }
```

Y dentro del companion:

```kotlin
        const val CLAVE_UPDATE_CHECK = "update_last_check"
```

- [ ] **Step 4: Implementar `UpdateChecker.kt`**

```kotlin
package com.tatoh.dokushorenshu.update

import android.util.Log
import com.tatoh.dokushorenshu.datos.progreso.PrefsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/** Pregunta a GitHub si hay una release de la app más nueva que la instalada.
 *  Nunca lanza: sin red, rate limit, JSON raro o release sin digest → `null`. */
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
        return runCatching {
            val info = ReleaseInfo.desdeReleases(fetch(URL))
            when {
                info == null -> { Log.w(TAG, "ninguna release con .apk y digest"); null }
                info.version > local -> info
                else -> { Log.i(TAG, "sin novedades (${info.version} vs $local)"); null }
            }
        }.onFailure { Log.w(TAG, "no se pudo chequear la release", it) }.getOrNull()
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
```

- [ ] **Step 5: Correr y ver que pasa**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests 'com.tatoh.dokushorenshu.update.UpdateCheckerTest'`
Expected: `BUILD SUCCESSFUL`, 6 tests pasan.

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/progreso/PrefsRepo.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/update/UpdateChecker.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/update/UpdateCheckerTest.kt
git commit -m "feat(update): UpdateChecker con gate de 24 h en prefs"
```

---

### Task 3: `Instalador` (port de `UpdaterPlugin.kt`) y permiso en el manifest

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/update/Instalador.kt`
- Modify: `app/app/src/main/AndroidManifest.xml:3` (después de `INTERNET`)

**Interfaces:**
- Produces: `enum class EstadoDescarga { PENDIENTE, CORRIENDO, PAUSADA, EXITOSA, FALLIDA }`; `data class Descarga(estado, bytesSoFar: Long, bytesTotal: Long)` con `val progreso: Float?`; `class Instalador(context: Context)` con `puedeInstalar(): Boolean`, `abrirAjustesInstalacion()`, `encolarDescarga(url: String): Long`, `consultarDescarga(id: Long): Descarga`, `suspend fun verificarEInstalar(id: Long, sha256: String): Boolean`.

Sin test unitario: es un wrapper fino sobre `DownloadManager` y `PackageManager` (Robolectric los fakea a medias); lo cubre el smoke de la Task 7. Referencia: `contador-de-truco/android/app/src/main/kotlin/io/github/t4toh/contadordetruco/UpdaterPlugin.kt`.

- [ ] **Step 1: Escribir `Instalador.kt`**

```kotlin
package com.tatoh.dokushorenshu.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

enum class EstadoDescarga { PENDIENTE, CORRIENDO, PAUSADA, EXITOSA, FALLIDA }

/** Foto del estado de una descarga en `DownloadManager`. */
data class Descarga(val estado: EstadoDescarga, val bytesSoFar: Long, val bytesTotal: Long) {
    /** 0..1, o `null` mientras no se conoce el total. */
    val progreso: Float?
        get() = if (bytesTotal > 0) (bytesSoFar.toFloat() / bytesTotal).coerceIn(0f, 1f) else null
}

/** Lado Android del updater: DownloadManager, permiso de instalación e intent del
 *  instalador. Port de `UpdaterPlugin.kt` de Pulpero sin MethodChannel. Sin
 *  FileProvider: DownloadManager ya expone la descarga como `content://`. */
class Instalador(private val context: Context) {

    private val downloadManager
        get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** minSdk 26: el permiso de "instalar apps desconocidas" es por app, no global. */
    fun puedeInstalar(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun abrirAjustesInstalacion() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Encola la descarga y devuelve su id. Lanza si DownloadManager no está disponible. */
    fun encolarDescarga(url: String): Long {
        // DownloadManager falla con ERROR_FILE_ALREADY_EXISTS si el destino existe
        // (una descarga anterior que no se instaló).
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), ARCHIVO).delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Dokusho Renshū")
            .setDescription("Downloading the update")
            .setMimeType(MIME_APK)
            // NOTIFY_COMPLETED, no VISIBLE: si el sistema mata la app durante la
            // descarga, la notificación al completar sigue ahí para instalar igual.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, ARCHIVO)
        return downloadManager.enqueue(request)
    }

    fun consultarDescarga(id: Long): Descarga =
        downloadManager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) {
                // La descarga desapareció (la canceló el usuario desde la notificación,
                // o el sistema la limpió).
                return Descarga(EstadoDescarga.FALLIDA, 0, 0)
            }
            val estado = when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_PENDING -> EstadoDescarga.PENDIENTE
                DownloadManager.STATUS_RUNNING -> EstadoDescarga.CORRIENDO
                DownloadManager.STATUS_PAUSED -> EstadoDescarga.PAUSADA
                DownloadManager.STATUS_SUCCESSFUL -> EstadoDescarga.EXITOSA
                else -> EstadoDescarga.FALLIDA
            }
            Descarga(
                estado,
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }

    /** Verifica el sha256 de la descarga y, si coincide, abre el instalador de Android.
     *  Si no coincide (o cualquier cosa falla) borra la descarga y devuelve false: no se
     *  instala nada corrupto. El hash de ~85 MB tarda segundos: corre en IO. */
    suspend fun verificarEInstalar(id: Long, sha256: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = downloadManager.getUriForDownloadedFile(id)
            val ok = uri != null && sha256(uri) == sha256.lowercase()
            if (ok) {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, MIME_APK)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                downloadManager.remove(id)
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "verificarEInstalar falló", e)
            runCatching { downloadManager.remove(id) }
            false
        }
    }

    private fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val leidos = input.read(buffer)
                if (leidos < 0) break
                digest.update(buffer, 0, leidos)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "Updater"
        const val ARCHIVO = "update.apk"
        const val MIME_APK = "application/vnd.android.package-archive"
    }
}
```

- [ ] **Step 2: Permiso en el manifest**

En `app/app/src/main/AndroidManifest.xml`, después de la línea `<uses-permission android:name="android.permission.INTERNET" />`:

```xml
    <!-- Updater: lanzar el instalador de Android con el APK descargado. -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

- [ ] **Step 3: Compilar**

Run: `cd app && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/update/Instalador.kt app/app/src/main/AndroidManifest.xml
git commit -m "feat(update): Instalador con DownloadManager y verificación sha256"
```

---

### Task 4: `UpdateViewModel`, `UpdateBanner` y enganche en `MainActivity`

**Files:**
- Modify: `app/gradle/libs.versions.toml` (library `lifecycle-runtime-compose`)
- Modify: `app/app/build.gradle.kts:60` (dependencia)
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/update/UpdateViewModel.kt`
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/update/UpdateBanner.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/App.kt` (Contenedor)
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt:74-76,135-223`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/acerca/AcercaScreen.kt:30`

**Interfaces:**
- Consumes: `UpdateChecker.chequear()`, `Instalador` (Tasks 2-3), `PrefsRepo` de `Contenedor.prefs`.
- Produces: `UpdateViewModel(checker, instalador)` con estado observable `info`, `fase`, `progreso`, `error` y acciones `actualizar()`, `abrirAjustes()`, `instalar()`, `cerrar()`, `alVolverDeAjustes()`; `@Composable fun UpdateBanner(vm: UpdateViewModel)`; `Contenedor.instalador`, `Contenedor.updateChecker`, `Contenedor.versionName`.

El estado vive en un ViewModel a nivel Activity (no en el composable) para que rotar durante la descarga no resetee el banner ni re-encole la descarga (`encolarDescarga` borra el `update.apk` en curso). Sin test unitario del ViewModel: haría falta una interfaz para `Instalador` con una sola implementación; lo cubre el smoke de la Task 7.

- [ ] **Step 1: Dependencia `lifecycle-runtime-compose`**

En `app/gradle/libs.versions.toml`, debajo de `lifecycle-viewmodel-compose`:

```toml
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
```

En `app/app/build.gradle.kts`, debajo de `implementation(libs.lifecycle.viewmodel.compose)`:

```kotlin
    implementation(libs.lifecycle.runtime.compose) // LifecycleEventEffect del banner del updater
```

- [ ] **Step 2: `UpdateViewModel.kt`**

```kotlin
package com.tatoh.dokushorenshu.update

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Máquina de estados del updater: aviso → permiso → descarga → verificación →
 *  instalador. Vive a nivel Activity para sobrevivir rotaciones: el banner solo pinta. */
class UpdateViewModel(
    private val checker: UpdateChecker,
    private val instalador: Instalador,
) : ViewModel() {

    enum class Fase { OCULTO, AVISO, SIN_PERMISO, DESCARGANDO, VERIFICANDO, LISTO, ERROR }

    var info by mutableStateOf<ReleaseInfo?>(null); private set
    var fase by mutableStateOf(Fase.OCULTO); private set
    /** 0..1, o null si no se conoce el total. */
    var progreso by mutableStateOf<Float?>(null); private set
    var error by mutableStateOf(""); private set

    private var downloadId: Long? = null
    private var seguimiento: Job? = null

    init {
        viewModelScope.launch {
            checker.chequear()?.let { info = it; fase = Fase.AVISO }
        }
    }

    /** "Not now": oculta hasta el próximo chequeo (24 h). Una descarga en curso sigue en
     *  DownloadManager con su notificación del sistema. */
    fun cerrar() {
        seguimiento?.cancel()
        fase = Fase.OCULTO
    }

    /** Vuelve de Ajustes: si ya habilitó la instalación, arranca solo. */
    fun alVolverDeAjustes() {
        if (fase == Fase.SIN_PERMISO) actualizar()
    }

    fun actualizar() {
        val url = info?.apkUrl ?: return
        try {
            if (!instalador.puedeInstalar()) {
                fase = Fase.SIN_PERMISO
                return
            }
            seguimiento?.cancel()
            progreso = null
            val id = instalador.encolarDescarga(url)
            downloadId = id
            fase = Fase.DESCARGANDO
            seguimiento = viewModelScope.launch { seguir(id) }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo encolar la descarga", e)
            fallar("Download failed.")
        }
    }

    fun abrirAjustes() {
        try {
            instalador.abrirAjustesInstalacion()
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo abrir Ajustes", e)
            fallar("Could not open Settings.")
        }
    }

    /** Botón "Install" de la fase LISTO: el usuario canceló el instalador (o Android lo
     *  rechazó) y hay que poder reintentar sin volver a descargar. */
    fun instalar() {
        val id = downloadId ?: return
        if (fase == Fase.VERIFICANDO) return // doble tap: una sola verificación a la vez
        viewModelScope.launch { verificarEInstalar(id) }
    }

    private suspend fun seguir(id: Long) {
        while (true) {
            val d = try {
                instalador.consultarDescarga(id)
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo consultar la descarga", e)
                fallar("Download failed.")
                return
            }
            when (d.estado) {
                EstadoDescarga.EXITOSA -> { verificarEInstalar(id); return }
                EstadoDescarga.FALLIDA -> { fallar("Download failed."); return }
                EstadoDescarga.PENDIENTE, EstadoDescarga.CORRIENDO, EstadoDescarga.PAUSADA -> progreso = d.progreso
            }
            delay(1_000)
        }
    }

    private suspend fun verificarEInstalar(id: Long) {
        val sha256 = info?.sha256 ?: return
        fase = Fase.VERIFICANDO
        if (instalador.verificarEInstalar(id, sha256)) fase = Fase.LISTO
        else fallar("The download was corrupted, try again.")
    }

    private fun fallar(mensaje: String) {
        error = mensaje
        fase = Fase.ERROR
    }

    private companion object {
        const val TAG = "Updater"
    }
}
```

- [ ] **Step 3: `UpdateBanner.kt`**

```kotlin
package com.tatoh.dokushorenshu.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tatoh.dokushorenshu.update.UpdateViewModel.Fase

/** Aviso de versión nueva arriba de la navegación. No es modal: no interrumpe la
 *  lectura. Toda la lógica está en [UpdateViewModel]; acá solo se pinta la fase. */
@Composable
fun UpdateBanner(vm: UpdateViewModel) {
    val info = vm.info ?: return
    if (vm.fase == Fase.OCULTO) return

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.alVolverDeAjustes() }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
        ) {
            when (vm.fase) {
                Fase.AVISO -> Fila("New version available: ${info.version}", "Update", vm::actualizar, vm::cerrar)
                Fase.SIN_PERMISO -> Fila("To update, allow \"Install unknown apps\" for Dokusho.", "Open Settings", vm::abrirAjustes, null)
                Fase.DESCARGANDO, Fase.VERIFICANDO -> {
                    Text(
                        if (vm.fase == Fase.DESCARGANDO) "Downloading ${info.version}…" else "Verifying the download…",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val progreso = vm.progreso
                    val modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp)
                    if (vm.fase == Fase.DESCARGANDO && progreso != null) {
                        LinearProgressIndicator(progress = { progreso }, modifier = modifier)
                    } else {
                        LinearProgressIndicator(modifier = modifier)
                    }
                }
                Fase.LISTO -> Fila("Update ready to install.", "Install", vm::instalar, vm::cerrar)
                Fase.ERROR -> Fila(vm.error, "Retry", vm::actualizar, vm::cerrar)
                Fase.OCULTO -> Unit
            }
        }
    }
}

@Composable
private fun Fila(texto: String, accion: String, onAccion: () -> Unit, onCerrar: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(texto, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAccion) { Text(accion) }
        // TextButton y no un ícono: el repo no usa material-icons y no vale sumarlo por una ✕.
        if (onCerrar != null) TextButton(onClick = onCerrar) { Text("Not now") }
    }
}
```

- [ ] **Step 4: `Contenedor` en `App.kt`**

Imports nuevos:

```kotlin
import com.tatoh.dokushorenshu.update.Instalador
import com.tatoh.dokushorenshu.update.UpdateChecker
```

Dentro de `class Contenedor`, después de `val dirExportMazos`:

```kotlin
    /** `versionName` del APK instalado (gradle `versionName`), lo que compara el updater. */
    val versionName: String by lazy {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
    }
    val instalador by lazy { Instalador(app) }
    val updateChecker by lazy { UpdateChecker(versionName, prefs) }
```

- [ ] **Step 5: Enganche en `MainActivity.kt`**

Imports nuevos:

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.Modifier
import com.tatoh.dokushorenshu.update.UpdateBanner
import com.tatoh.dokushorenshu.update.UpdateViewModel
```

Dentro de `TemaDokusho { ... }`, inmediatamente después de `val nav = rememberNavController()`:

```kotlin
                // A nivel Activity, no dentro del NavHost: el banner es global y su estado
                // (descarga en curso) tiene que sobrevivir a la navegación y a rotar.
                val updateVm: UpdateViewModel = viewModel(factory = viewModelFactory {
                    initializer { UpdateViewModel(contenedor.updateChecker, contenedor.instalador) }
                })
```

Reemplazar la línea `NavHost(navController = nav, startDestination = "biblioteca") {` por:

```kotlin
                val bannerVisible = updateVm.info != null && updateVm.fase != UpdateViewModel.Fase.OCULTO
                Column(Modifier.fillMaxSize()) {
                    UpdateBanner(updateVm)
                    // Con el banner arriba, él ya absorbió el inset de la status bar: las
                    // pantallas de abajo no tienen que volver a padear (evita el doble hueco).
                    Box(
                        Modifier
                            .weight(1f)
                            .consumeWindowInsets(if (bannerVisible) WindowInsets.statusBars else WindowInsets(0, 0, 0, 0))
                    ) {
                NavHost(navController = nav, startDestination = "biblioteca") {
```

Y cerrar los dos bloques nuevos: donde hoy termina el `NavHost` (la línea `                }` justo antes del cierre de `TemaDokusho`, hoy `composable("acerca") { AcercaScreen() }` + `}`), agregar dos `}` más:

```kotlin
                    composable("acerca") { AcercaScreen() }
                }
                    }
                }
```

Reindentar el bloque del `NavHost` (dos niveles más) para que quede legible.

- [ ] **Step 6: Versión real en `AcercaScreen.kt`**

Imports nuevos:

```kotlin
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
```

Reemplazar `Text("Dokusho Renshū 0.1.0", style = MaterialTheme.typography.headlineSmall)` por:

```kotlin
            val contexto = LocalContext.current
            val version = remember {
                contexto.packageManager.getPackageInfo(contexto.packageName, 0).versionName ?: "?"
            }
            Text("Dokusho Renshū $version", style = MaterialTheme.typography.headlineSmall)
```

- [ ] **Step 7: Compilar y correr todos los tests**

Run: `cd app && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, todos los tests pasan (los 16 nuevos y los existentes).

- [ ] **Step 8: Probar en debug que nada se rompió**

Run: `cd app && ./gradlew installDebug && adb shell am start -n com.tatoh.dokushorenshu/.MainActivity`
Expected: la app abre en Biblioteca sin banner (la debug es `0.1.0`, mayor que cualquier beta publicada) y `adb logcat -s Updater` muestra `sin novedades (0.1.0-beta.4 vs 0.1.0)`. About muestra `Dokusho Renshū 0.1.0`.

- [ ] **Step 9: Commit**

```bash
git add app/gradle/libs.versions.toml app/app/build.gradle.kts \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/update/UpdateViewModel.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/update/UpdateBanner.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/App.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/acerca/AcercaScreen.kt
git commit -m "feat(update): banner de actualización con descarga, verificación e instalador"
```

---

### Task 5: Versionado real y firma fijada en gradle

**Files:**
- Modify: `app/app/build.gradle.kts:1-2,17-19,45-51`
- Modify: `app/.gitignore`
- Create (fuera del repo): `~/dokusho-release.jks`, `app/key.properties`

**Interfaces:**
- Produces: `versionName = "0.1.0-beta.5"`, `versionCode = 5`; `signingConfigs.release` leído de `app/key.properties`; `assembleRelease` falla si falta el archivo.

- [ ] **Step 1: Copiar el keystore y escribir `key.properties`**

Verificar primero que el `debug.keystore` local es el que firmó la beta.4 (huella `35:E3:CA:36:…`):

```bash
keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA256
```

Expected: `SHA256: 35:E3:CA:36:4D:62:6A:73:8C:B4:2C:98:64:7A:3E:81:56:FF:01:A3:2A:96:0A:93:08:E3:30:11:DB:2D:A1:CF`. Si no coincide, **parar**: firmar con otra clave rompe la actualización de las betas instaladas.

```bash
cp ~/.android/debug.keystore ~/dokusho-release.jks
cat > /Users/tatoh/Repos/Personal/dokusho-renshuu/app/key.properties <<'EOF'
storeFile=/Users/tatoh/dokusho-release.jks
storePassword=android
keyAlias=androiddebugkey
keyPassword=android
EOF
```

- [ ] **Step 2: `.gitignore`**

Agregar a `app/.gitignore`:

```
# firma de release: keystore y passwords, nunca al repo
key.properties
*.jks
```

Verificar: `git status --short app/` no debe listar `key.properties`.

- [ ] **Step 3: `build.gradle.kts`**

Al principio del archivo, junto a `import java.net.URI`:

```kotlin
import java.util.Properties
```

Antes de `android {`:

```kotlin
// Firma de release. El keystore es una copia del debug.keystore de la Mac principal
// (el mismo que firmó las betas 1-4: cambiar de clave rompería la actualización de las
// instaladas). Vive en app/key.properties, gitignored. Si falta, el build release
// FALLA en vez de caer a debug: un APK con otra firma no sirve para distribuir.
val propiedadesFirma = rootProject.file("key.properties")
val firma = Properties().apply {
    if (propiedadesFirma.exists()) propiedadesFirma.inputStream().use { load(it) }
}
gradle.taskGraph.whenReady {
    if (!propiedadesFirma.exists() && allTasks.any { it.name == "packageRelease" || it.name == "assembleRelease" }) {
        throw GradleException(
            "Falta app/key.properties (storeFile, storePassword, keyAlias, keyPassword). " +
                "Sin eso el APK release saldría con otra firma y las betas instaladas lo rechazarían."
        )
    }
}
```

En `defaultConfig`, reemplazar `versionCode = 1` / `versionName = "0.1.0"` por:

```kotlin
        // Los dos suben juntos en cada release; el tag es "v$versionName". El updater
        // compara versionName (semver con prerelease) y Android compara versionCode.
        versionCode = 5
        versionName = "0.1.0-beta.5"
```

Dentro de `android { }`, antes de `buildTypes`:

```kotlin
    signingConfigs {
        create("release") {
            if (propiedadesFirma.exists()) {
                storeFile = file(firma.getProperty("storeFile"))
                storePassword = firma.getProperty("storePassword")
                keyAlias = firma.getProperty("keyAlias")
                keyPassword = firma.getProperty("keyPassword")
            }
        }
    }
```

En `buildTypes { release { ... } }`, reemplazar el comentario de "se firma con el keystore de debug" y la línea `signingConfig = signingConfigs.getByName("debug")` por:

```kotlin
            signingConfig = signingConfigs.getByName("release")
```

- [ ] **Step 4: Verificar que sin `key.properties` falla, y con él firma bien**

```bash
cd app && mv key.properties key.properties.bak && ./gradlew assembleRelease; mv key.properties.bak key.properties
```

Expected: `FAILURE` con `Falta app/key.properties`.

```bash
cd app && ./gradlew assembleRelease && \
  $ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk | grep SHA-256 && \
  $ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging app/build/outputs/apk/release/app-release.apk | grep -o "versionCode='[^']*' versionName='[^']*'"
```

Expected: `SHA-256 digest: 35e3ca364d626a738cb42c98647a3e8156ff01a32a960a9308e33011db2da1cf` y `versionCode='5' versionName='0.1.0-beta.5'`.

- [ ] **Step 5: Commit**

```bash
git add app/app/build.gradle.kts app/.gitignore
git commit -m "build: versionName 0.1.0-beta.5 / versionCode 5 y firma de release por key.properties"
```

---

### Task 6: `release.sh`, `CHANGELOG.md` y docs

**Files:**
- Create: `app/release.sh`
- Create: `app/CHANGELOG.md`
- Modify: `README.md:37-38` (raíz), `app/README.md` (sección Build), `docs/ESTADO.md` (backlog 2026-09-18 y "Release app vigente")
- Create: `docs/smoke-updater.md`

**Interfaces:**
- Consumes: `versionName`/`versionCode` de `app/app/build.gradle.kts`, `app/key.properties` (Task 5).
- Produces: `app/app/build/outputs/apk/release/dokusho-renshuu-vX.Y.Z-beta.N.apk` y `versionCode.txt`; imprime el `gh release create`.

- [ ] **Step 1: `app/release.sh`**

```bash
#!/bin/bash
# Prepara un release de la app: valida versión, changelog y firma, buildea el APK
# release e imprime el comando de publicación. NO publica: el tag lo crea una persona.
# Port de contador-de-truco/release.sh (Pulpero) a gradle.
set -euo pipefail

cd "$(dirname "$0")"

GRADLE=app/build.gradle.kts
SALIDA=app/build/outputs/apk/release

if ! gh auth status >/dev/null 2>&1; then
    echo "❌ gh no está autenticado (gh auth login). Sin eso no se pueden verificar el tag ni el versionCode publicado."
    exit 1
fi

VERSION=$(grep -E '^\s*versionName = "' "$GRADLE" | sed -E 's/.*"([^"]+)".*/\1/')
BUILD=$(grep -E '^\s*versionCode = ' "$GRADLE" | sed -E 's/[^0-9]*([0-9]+).*/\1/')
TAG="v$VERSION"

if [ -z "$VERSION" ] || ! [[ "$BUILD" =~ ^[0-9]+$ ]]; then
    echo "❌ No pude leer versionName/versionCode de $GRADLE (leí '$VERSION' / '$BUILD')."
    exit 1
fi

if ! grep -q "^## \[$VERSION\]" CHANGELOG.md; then
    echo "❌ CHANGELOG.md no tiene la sección '## [$VERSION] - AAAA-MM-DD'."
    echo "   Renombrá '## [Sin publicar]' con la versión y la fecha."
    exit 1
fi

if [ ! -f key.properties ]; then
    echo "❌ Falta app/key.properties: el APK saldría con otra firma y las betas instaladas"
    echo "   lo rechazarían (INSTALL_FAILED_UPDATE_INCOMPATIBLE)."
    exit 1
fi

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || gh release view "$TAG" >/dev/null 2>&1; then
    echo "❌ El tag $TAG ya existe. Subí versionName en $GRADLE."
    exit 1
fi

# El updater compara versionName, pero Android compara versionCode: si no sube, el
# instalador rechaza el APK aunque el tag sea mayor. Se mira la última release de la
# APP (tags v*), no las del diccionario (db-v*).
ULTIMO_TAG=$(gh release list --limit 30 --json tagName --jq '[.[] | select(.tagName | startswith("v"))][0].tagName // empty')
if [ -n "$ULTIMO_TAG" ]; then
    BUILD_PUBLICADO=$(gh release download "$ULTIMO_TAG" --pattern versionCode.txt -O - 2>/dev/null | tr -d '[:space:]' || true)
    if [ -z "$BUILD_PUBLICADO" ]; then
        echo "⚠️  $ULTIMO_TAG no tiene versionCode.txt: no se puede verificar el bump del versionCode."
    elif ! [[ "$BUILD_PUBLICADO" =~ ^[0-9]+$ ]]; then
        echo "❌ versionCode.txt de $ULTIMO_TAG no es un número: '$BUILD_PUBLICADO'"
        exit 1
    elif [ "$BUILD" -le "$BUILD_PUBLICADO" ]; then
        echo "❌ versionCode $BUILD no es mayor que el publicado en $ULTIMO_TAG ($BUILD_PUBLICADO)."
        exit 1
    fi
fi

echo "🔨 ./gradlew assembleRelease"
./gradlew assembleRelease

APK="$SALIDA/dokusho-renshuu-$TAG.apk"
cp "$SALIDA/app-release.apk" "$APK"
echo "$BUILD" > "$SALIDA/versionCode.txt"
shasum -a 256 "$APK"

echo ""
echo "⚠️  Probá ESTE apk en un dispositivo antes de publicar (R8 vs ML Kit: el OCR puede"
echo "   volver vacío sin crashear, ver docs/ESTADO.md). Instalar: adb install -r \"$APK\""
echo ""
echo "✅ Listo. Para publicar $TAG, corré vos:"
echo ""
echo "  gh release create $TAG \"$APK\" \"$SALIDA/versionCode.txt\" --prerelease --title $TAG --generate-notes"
echo ""
```

```bash
chmod +x app/release.sh
```

- [ ] **Step 2: `app/CHANGELOG.md`**

```markdown
# Changelog

Cambios notables de la app Dokusho Renshū, del más nuevo al más viejo. Formato
[Keep a Changelog](https://keepachangelog.com/es/1.1.0/), versiones
[SemVer](https://semver.org/lang/es/) con prerelease (`0.1.0-beta.N`). Las releases del
diccionario (`db-vN`) no van acá. Antes de la beta.5 las notas vivían solo en la release
de GitHub.

## [0.1.0-beta.5] - 2026-09-22

### Agregado
- La app se actualiza sola: al abrirla chequea (una vez por día) si hay una release nueva
  en GitHub y ofrece bajarla e instalarla desde un aviso arriba de la biblioteca. Verifica
  el sha256 antes de instalar. Pide el permiso de "instalar apps desconocidas" la primera vez.

### Cambiado
- La versión que muestra About es la real del APK (antes decía siempre 0.1.0).
- La release se firma con una clave fija (`key.properties`, fuera del repo); es la misma
  firma de las betas anteriores, así que se instala encima.
```

- [ ] **Step 3: `README.md` (raíz)**

Reemplazar las líneas:

```
La app todavía no se actualiza sola: cuando salga una versión nueva hay que volver a la
release. Está en el backlog.
```

por:

```
Desde la beta.5 la app avisa cuando hay una versión nueva y la instala desde un aviso
arriba de la biblioteca (una vez por día chequea la release de GitHub, verifica el hash
del APK y abre el instalador de Android; la primera vez pide el permiso de "instalar apps
desconocidas"). Si tenés la beta.4 o anterior, esa última vez la bajás a mano.
```

- [ ] **Step 4: `app/README.md`**

En la sección `## Build`, después del bloque de comandos, agregar:

```markdown
### Release

```bash
./release.sh    # valida versionName/versionCode/CHANGELOG/key.properties/tag, buildea, imprime el gh release create
```

Necesita `app/key.properties` (gitignored) apuntando a `~/dokusho-release.jks`: copia del
`debug.keystore` que firmó las betas 1-4 (alias `androiddebugkey`, pass `android`). Sin
él, `assembleRelease` falla a propósito. Cada release sube `versionCode` y `versionName`
juntos en `app/build.gradle.kts`; el tag es `v$versionName` y la release lleva
`--prerelease` mientras sea beta. El updater in-app (`update/`) lista `/releases` (no
`/latest`, que devuelve `db-v2`) y elige la mayor versión con `.apk` + `digest`.
```

Y en `## Arquitectura` agregar un ítem:

```markdown
- `update/` — updater in-app: `Version` (semver con prerelease), `ReleaseInfo` (parser de
  `/releases`), `UpdateChecker` (gate 24 h en `prefs`), `Instalador` (DownloadManager +
  sha256 + intent del instalador, port de Pulpero) y `UpdateViewModel`/`UpdateBanner`.
```

- [ ] **Step 5: `docs/ESTADO.md`**

En el backlog del 2026-09-18, reemplazar el ítem **Autoupdates** completo por:

```markdown
- ~~**Autoupdates: que la app se actualice sola.**~~ ✅ Hecho en la beta.5 (spec
  `docs/superpowers/specs/2026-09-22-updater-app-design.md`): banner arriba de la
  biblioteca, descarga con DownloadManager, sha256, instalador. Requirió versionar de
  verdad (`versionName`/`versionCode` estaban clavados en 0.1.0/1 desde la beta.1) y fijar
  la firma (`app/key.properties` → copia del debug.keystore que firmó las betas). Sigue
  siendo distinto del Update de **contenido** (historias, PR #13).
```

En "Release app vigente" (Datos operativos), agregar al final del párrafo:

```
Desde la beta.5 el APK se genera con `app/release.sh` y la app se actualiza sola; las
releases llevan `versionCode.txt` como asset para que el script verifique el bump.
```

- [ ] **Step 6: `docs/smoke-updater.md`**

```markdown
# Smoke: updater in-app

Cómo probar el flujo completo en dispositivo **sin publicar nada**, y qué mirar.

## Preparación

La beta.4 instalada no tiene updater. Para ver el banner hay que instalar un build que se
crea más viejo que la última release publicada:

1. En `app/app/build.gradle.kts` poner temporalmente `versionCode = 1` y
   `versionName = "0.1.0-beta.3"` (**no commitear**).
2. `cd app && ./gradlew assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk`
   (misma firma y mismo versionCode que la beta.4: instala encima sin desinstalar).
3. Si hace falta forzar un chequeo fresco: `adb shell pm clear com.tatoh.dokushorenshu`
   (**borra progreso, historias importadas y notas**; avisar antes).

## Flujo

- [ ] Abrir la app. En ≤ 3 s aparece el banner "New version available: 0.1.0-beta.4"
      arriba de Biblioteca; el TopAppBar no tiene doble hueco de status bar.
- [ ] `adb logcat -s Updater` no muestra warnings.
- [ ] Rotar: el banner sigue.
- [ ] ✕ → desaparece. Reabrir la app: no vuelve (gate 24 h). `pm clear` y seguir.
- [ ] `Update` sin el permiso → "To update, allow "Install unknown apps" for Dokusho." ·
      `Open Settings` abre la pantalla de la app en Ajustes. Conceder y volver: la descarga
      arranca sola.
- [ ] Barra de progreso avanza (83 MB). Rotar en el medio: sigue descargando, sin re-encolar.
- [ ] Al terminar, "Verifying the download…" un par de segundos (indeterminada) y se abre
      el instalador de Android con Dokusho Renshū. Cancelar.
- [ ] Banner en "Update ready to install." · `Install` reabre el instalador sin bajar de
      nuevo. Instalar.
- [ ] La app se reabre como beta.4 (About: `Dokusho Renshū 0.1.0`, porque la beta.4 aún no
      leía la versión real). Notificación de descarga completada en la bandeja.
- [ ] Sin red (modo avión, `pm clear`, abrir): no hay banner, logcat dice
      `no se pudo chequear la release`, y no crashea.

## Después

Revertir `versionCode`/`versionName` al valor real de la rama e instalar la beta.5:
`./gradlew assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk`.
```

- [ ] **Step 7: Probar `release.sh` hasta el build (sin publicar)**

Run: `cd app && ./release.sh`
Expected: pasa todas las validaciones (avisa `⚠️ v0.1.0-beta.4 no tiene versionCode.txt`), buildea, imprime el sha256 y el comando `gh release create v0.1.0-beta.5 ... --prerelease`. **No correrlo.**

- [ ] **Step 8: Commit**

```bash
git add app/release.sh app/CHANGELOG.md README.md app/README.md docs/ESTADO.md docs/smoke-updater.md
git commit -m "docs(release): release.sh, CHANGELOG y smoke del updater"
```

---

### Task 7: Smoke en dispositivo

**Files:** ninguno commiteado (cambio temporal en `app/app/build.gradle.kts`, revertido al final).

- [ ] **Step 1: Avisar al usuario** que se va a instalar un build de prueba en el POCO (`A6FML7O7INAUR8LJ`) encima de la beta.4 y que el `pm clear`, si hace falta, borra sus datos. Esperar el ok.

- [ ] **Step 2: Seguir `docs/smoke-updater.md`** paso a paso, marcando cada casilla. Anotar cualquier desvío en el mismo archivo bajo `## Resultado 2026-09-22`.

- [ ] **Step 3: Revertir** `versionCode`/`versionName` en `build.gradle.kts` (`git checkout app/app/build.gradle.kts`) y verificar `git status` limpio.

- [ ] **Step 4: Build final e instalar la beta.5 real**

Run: `cd app && ./release.sh && adb install -r app/build/outputs/apk/release/dokusho-renshuu-v0.1.0-beta.5.apk`
Expected: instala encima; About muestra `Dokusho Renshū 0.1.0-beta.5`; captura OCR sigue funcionando (probar un Scan: el texto tiene que tardar ~300-500 ms, no ~13 ms).

- [ ] **Step 5: Commit del resultado del smoke y entregar**

```bash
git add docs/smoke-updater.md
git commit -m "docs(smoke): resultado del updater en el POCO"
```

Ofrecer al usuario: push de `feat/updater`, PR, y el comando `gh release create` que imprimió el script (lo corre él).
