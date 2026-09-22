# Updater in-app — diseño

Fecha: 2026-09-22. Estado: aprobado, pendiente de plan.

## Objetivo

Que Dokusho avise cuando hay una release nueva en GitHub y la descargue, verifique e
instale desde la app, como hace Pulpero (`contador-de-truco/lib/update/` +
`UpdaterPlugin.kt`). Cierra el ítem "Autoupdates" del backlog del 2026-09-18.

No confundir con el Update de contenido (historias del catálogo, PR #13): ese sigue igual.

## Por qué no es un port literal

Relevado contra las releases reales de `T4toh/dokusho-renshuu`:

1. **`/releases/latest` devuelve `db-v2`** (el diccionario). Todas las releases de la app
   son prerelease y GitHub las excluye de `latest`. Hay que listar `/releases` y elegir el
   mayor tag que parsee como versión de la app.
2. **Semver con prerelease** (`v0.1.0-beta.4`). El `Version` de Pulpero solo acepta
   `X.Y.Z`. Se necesita orden de prerelease.
3. **`versionCode = 1` y `versionName = "0.1.0"` hardcodeados** desde la beta.1. Si la
   beta.5 saliera así, se creería `0.1.0`, que en semver es *mayor* que `0.1.0-beta.6`, y
   nunca avisaría. Hay que versionar de verdad a partir de ahora.
4. **Firma**: la beta.4 está firmada con el `debug.keystore` de la Mac principal
   (SHA-256 `35e3ca36…`). Si ese archivo se regenera, ninguna beta instalada acepta el
   update (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Hay que fijar esa clave.

## Alcance

Se hace (opción A, elegida sobre "solo aviso" y sobre Obtainium):

- Chequeo automático al abrir la app, gate de 24 h por dispositivo.
- Banner no modal arriba de la navegación: aviso, permiso, descarga con progreso,
  verificación sha256, instalador de Android, reintento.
- Versionado real en gradle, firma fijada, `release.sh` que valida antes de buildear.

Descartado a propósito: botón "Check for updates" manual, changelog in-app, filtro de
ABIs, dependencias nuevas.

## Piezas

Paquete `com.tatoh.dokushorenshu.update`, cuatro archivos.

### `Version.kt` (puro Kotlin, testeable con JUnit)

- `Version(major, minor, patch, prerelease: List<String>)`. `parse(texto)` acepta `v` opcional,
  `X.Y.Z` y `X.Y.Z-ident(.ident)*`. Cualquier otra cosa (`db-v2`, vacío) → `null`.
- Orden semver 2.0: por `major.minor.patch`; a igualdad, una versión **sin** prerelease es
  mayor que una con prerelease; entre prereleases se comparan identificadores de a uno,
  numéricos como enteros, numérico < alfanumérico, y el prefijo más corto es menor.
  Casos que fijan el contrato: `0.1.0-beta.5 > 0.1.0-beta.4`, `0.1.0 > 0.1.0-beta.9`,
  `1.2.0-rc.1 > 1.2.0-beta.3`, `0.1.0-beta.10 > 0.1.0-beta.9`.
- `ReleaseInfo(version, apkUrl: String, sha256: String)`.
  `ReleaseInfo.desdeReleases(json: String): ReleaseInfo?` parsea la respuesta de
  `GET /repos/T4toh/dokusho-renshuu/releases?per_page=20` con kotlinx.serialization
  (`Json { ignoreUnknownKeys = true }`), descarta releases con tag que no parsea, sin asset
  `.apk`, sin `digest` `sha256:`, o con URL que no sea `https`, y devuelve la de mayor
  versión. Drafts (`draft: true`) se ignoran.

### `UpdateChecker.kt`

- Constructor: `versionInstalada: String`, `prefs: PrefsRepo`, `fetch: suspend (String) -> String`
  (inyectable), `ahora: () -> Long`.
- `suspend fun chequear(): ReleaseInfo?`. Nunca lanza. Secuencia:
  1. Parsea `versionInstalada`; si no parsea, log y `null`.
  2. Lee `update_last_check` de `PrefsRepo`; si hace menos de 24 h, `null`.
  3. Graba `update_last_check = ahora` **antes** del fetch: una falla también cuenta como
     intento y no se martilla la API sin red.
  4. Fetch, `desdeReleases`, compara. Devuelve la release solo si es estrictamente mayor.
- Fetch por defecto: `HttpURLConnection`, `User-Agent: dokusho-renshuu`,
  `Accept: application/vnd.github+json`, connect 10 s, read 15 s, solo HTTP 200.
- `PrefsRepo` gana dos métodos: `ultimoChequeoUpdate(): Long?` y
  `setUltimoChequeoUpdate(Long)`, sobre la tabla `prefs` existente. Sin migración.

### `Instalador.kt`

Port de `UpdaterPlugin.kt` sin MethodChannel. Clase con `Context`, mismas cinco operaciones:

- `puedeInstalar(): Boolean` → `packageManager.canRequestPackageInstalls()` (minSdk 26,
  sin rama pre-O).
- `abrirAjustesInstalacion()` → `ACTION_MANAGE_UNKNOWN_APP_SOURCES` con `package:`.
- `encolarDescarga(url): Long` → borra `update.apk` previo en
  `getExternalFilesDir(DIRECTORY_DOWNLOADS)`, `DownloadManager.Request` con mime APK,
  `VISIBILITY_VISIBLE_NOTIFY_COMPLETED`, título "Dokusho Renshū".
- `consultarDescarga(id): Descarga` → `Descarga(estado, bytesSoFar, bytesTotal)`; cursor
  vacío = `FALLIDA`.
- `suspend fun verificarEInstalar(id, sha256): Boolean` en `Dispatchers.IO`: hash del
  `content://` de `getUriForDownloadedFile`, si coincide lanza `ACTION_VIEW` con
  `FLAG_GRANT_READ_URI_PERMISSION | FLAG_ACTIVITY_NEW_TASK`; si no, `downloadManager.remove(id)`.
  Cualquier excepción → `false` + `remove`.

Sin FileProvider: DownloadManager ya expone `content://`.

### `UpdateViewModel.kt` + `UpdateBanner.kt`

La máquina de estados vive en `UpdateViewModel` (a nivel Activity, `viewModelScope` para
el chequeo y el polling): rotar durante la descarga no resetea el banner ni re-encola
(`encolarDescarga` borra el `update.apk` en curso). El composable `UpdateBanner(vm)` solo
pinta. Textos en inglés como el resto de la UI. Mismas fases que Pulpero más `Oculto`:
`Aviso`, `SinPermiso`, `Descargando`, `Verificando`, `Listo`, `Error`. Cerrar ("Not now",
botón de texto: el repo no usa material-icons) oculta hasta el próximo chequeo.

- Aviso: "New version available: 0.1.0-beta.5" · `Update` · `Not now`.
- SinPermiso: "To update, allow \"Install unknown apps\" for Dokusho." · `Open Settings`.
  `LifecycleResumeEffect`: al volver de Ajustes en esta fase, reintenta solo.
- Descargando/Verificando: `LinearProgressIndicator` (determinado si hay total).
  Polling con `LaunchedEffect(downloadId) { while (isActive) { consultar; delay(1s) } }`:
  el loop es secuencial, así que no hace falta la guarda `_consultando` de Pulpero. Sí se
  mantiene la guarda contra doble tap en `Update`/`Retry`.
- Listo: "Update ready to install." · `Install` (reintenta el instalador sin re-descargar) · `Not now`.
- Error: mensaje · `Retry` · `Not now`. Mensajes: "Download failed.", "The download was corrupted,
  try again.", "Could not open Settings."
- Colores: `MaterialTheme.colorScheme.primaryContainer` / `onPrimaryContainer`.

## Enganche

- `Contenedor`: `val instalador by lazy { Instalador(app) }`,
  `val updateChecker by lazy { UpdateChecker(versionName del package, prefs) }`.
- `MainActivity`, dentro de `TemaDokusho`: `UpdateViewModel` vía `viewModel(factory)` a nivel
  Activity; `Column { UpdateBanner(vm); Box(consumeWindowInsets(statusBars si hay banner)) { NavHost } }`
  para que las pantallas no dupliquen el padding de la status bar.
- Manifest: `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>`.
  `INTERNET` ya está.
- `AcercaScreen` muestra `packageManager.versionName` en vez del literal "0.1.0".

## Versionado y firma (gradle)

- `versionName = "0.1.0-beta.5"`, `versionCode = 5`. Regla desde acá: cada release sube los
  dos; `versionName` sin la `v`, el tag es `v$versionName`.
- `signingConfigs.release` lee `app/key.properties` (gitignored, mismas claves que Pulpero:
  `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). El keystore es una copia de
  `~/.android/debug.keystore` de la Mac principal en `~/dokusho-release.jks` (alias
  `androiddebugkey`, pass `android`): misma firma que la beta.4, así que instala encima sin
  desinstalar. Si falta `key.properties`, el build release **falla** con mensaje claro; no
  cae a la firma de debug.
- Se deja de decir en README/ESTADO/notas de release que "está firmado con clave de debug"
  y que "no se actualiza sola".

## `app/release.sh`

Port del de Pulpero. No publica: imprime el comando. Valida en orden:

1. `gh auth status`.
2. `versionName` y `versionCode` leídos de `app/build.gradle.kts`; `TAG=v$versionName`.
3. `CHANGELOG.md` tiene `## [$versionName]` (se crea el archivo con la beta.5).
4. `app/key.properties` existe.
5. El tag no existe ni local ni en GitHub.
6. `versionCode.txt` de la última release de la app (no `db-*`): el nuevo debe ser mayor.
   Se busca con `gh release list` filtrando tags `v*`; si la última no trae el asset, avisa
   y sigue.
7. `./gradlew assembleRelease`, copia a `build/outputs/apk/release/dokusho-renshuu-$TAG.apk`,
   escribe `versionCode.txt`, `shasum -a 256`.
8. Imprime `gh release create $TAG <apk> <versionCode.txt> --prerelease --title $TAG --generate-notes`.

Recordatorio en pantalla: probar el APK release en dispositivo antes de publicar (R8 vs ML
Kit, ver ESTADO).

## Errores

| Situación | Comportamiento |
|---|---|
| Sin red, rate limit, HTTP ≠ 200, JSON raro | `null`, `Log.w`, reintento en 24 h |
| Ninguna release con `.apk` + `digest` | `null` |
| Tag de la última release no parsea (`db-v3`) | se ignora esa release, se sigue con las demás |
| Hash distinto | se borra la descarga, banner en Error con Retry |
| Usuario cancela el instalador | banner queda en Listo; `Install` relanza sin bajar de nuevo |
| App muerta durante la descarga | notificación del sistema al completar; al reabrir, chequeo nuevo cuando venza el gate |

## Pruebas

- JUnit puro: `VersionTest` (parse con/sin `v`, prerelease, rechazo de `db-v2` y basura,
  todos los casos de orden listados arriba) y `ReleaseInfoTest` con un JSON tomado de la
  API real (mezcla de `db-v2`, betas, un draft, una release sin digest).
- `UpdateCheckerTest` con fetch fake y `PrefsRepo` sobre Room in-memory (Robolectric, como
  `HistoriasRepoTest`): gate de 24 h, graba el intento antes del fetch, falla → `null`,
  igual versión → `null`, mayor → info.
- Smoke en dispositivo, sin publicar nada: build release local con `versionName =
  "0.1.0-beta.3"` y `versionCode = 1` (misma firma, mismo versionCode: instala encima de la
  beta.4). Al abrir, el updater encuentra la beta.4 publicada (83 MB, con digest) y recorre
  permiso → descarga → hash → instalador. Queda la beta.4 instalada; después `adb install`
  de la beta.5 real. Se documenta en `docs/smoke-updater.md`.
