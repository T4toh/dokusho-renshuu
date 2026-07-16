# Backlog app (PR A) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cerrar los 3 items de app del backlog "feedback de uso": botón Update por historia cuando el catálogo remoto trae una versión más nueva, número de oración junto al piquito del lector, y selección de rango de tokens con Search web/Copy.

**Architecture:** Detección de updates comparando `EntradaCatalogo.tamanio` remoto vs tamaño local (asset: entrada del catálogo asset; descargada: bytes del archivo) — sin cambios de pipeline ni schema. Selección como estado del `LectorViewModel` (rango de chars sobre el texto de UNA oración), long-press ancla / tap extiende, barra contextual reemplaza Previous/Next mientras hay selección.

**Tech Stack:** Kotlin + Compose (Material 3), coroutines test (StandardTestDispatcher), JUnit4. Sin dependencias nuevas.

**Spec:** `docs/superpowers/specs/2026-07-16-backlog-feedback-uso-design.md`

## Global Constraints

- Branch: `feature/backlog-app` desde `main`. Compilar con JDK 17+ / SDK 36 (esta máquina, la PC secundaria, compila OK).
- UI copy en inglés; código/comentarios en español (convención del repo).
- El separador U+001F jamás literal en fuentes: `grep -rcP '\x1f' app/app/src/main/kotlin | grep -v ':0'` debe no devolver nada.
- Tests unit: `cd app && ./gradlew test` (maxHeapSize 2g ya configurado, Kuromoji).
- No tocar `historias/`, `diccionario/`, `catalogo/` en este PR.
- Comentarios de código: densidad e idioma calcados de los archivos tocados (docs largos con referencia a plan/feedback).

---

### Task 1: `HistoriasRepo.tamanioLocal(id)` — tamaño local comparable con el catálogo remoto

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/HistoriasRepo.kt` (agregar método después de `esImportada`, línea ~110)
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/HistoriasRepoTest.kt` (agregar tests al final de la clase existente; calcar el patrón de construcción del repo que ya usa ese archivo — fakes de assets + dirs temp, igual que `BibliotecaViewModelTest.repo()`)

**Interfaces:**
- Produces: `fun tamanioLocal(id: String): Long?` — bytes de la historia local para comparar contra `EntradaCatalogo.tamanio` remoto. Descargada → `File.length()`; bundleada (asset) → `tamanio` de la entrada en `catalogoLocal()`; importada o inexistente → `null`.

- [ ] **Step 1: Escribir los tests que fallan**

En `HistoriasRepoTest.kt` (adaptar el helper de construcción del repo que ya exista en ese archivo; si no hay uno reutilizable, construir inline como acá). Los recursos `momotaro.json` y `catalogo.json` ya existen en `test/resources` (momotaro figura en el catálogo con `"tamaño": 67083`):

```kotlin
// --- tamanioLocal: fix "la app nunca actualiza historias bundleadas" (backlog
// feedback de uso 2026-07-13). Fuente de verdad del tamaño local para comparar
// contra EntradaCatalogo.tamanio remoto: descargada = bytes del archivo (idénticos
// al raw remoto al momento de bajarlo, tamaño = os.path.getsize en emisor.py);
// bundleada = tamaño registrado en el catálogo asset (misma generación que el
// asset); importada o desconocida = null (no existen en el catálogo remoto). ---

@Test
fun `tamanioLocal de historia bundleada devuelve el tamanio del catalogo asset`() {
    val repo = repoConAssets()  // momotaro como asset + catalogo.json asset
    assertEquals(67083L, repo.tamanioLocal("momotaro"))
}

@Test
fun `tamanioLocal de historia descargada devuelve los bytes del archivo`() = runTest {
    val repo = repoConAssets(http = { url ->
        if (url == HistoriasRepo.urlHistoria("momotaro")) momotaroJson
        else throw IOException("sin red")
    })
    repo.descargarHistoria("momotaro").getOrThrow()
    // la descargada pisa el asset: el tamaño pasa a ser el del archivo bajado
    assertEquals(momotaroJson.toByteArray(Charsets.UTF_8).size.toLong(), repo.tamanioLocal("momotaro"))
}

@Test
fun `tamanioLocal de importada o inexistente es null`() {
    val repo = repoConAssets()
    repo.guardarImportada(ParserHistoria.parsear(momotaroJson).copy(id = "propia", titulo = "自作"))
    assertNull(repo.tamanioLocal("propia"))    // importada: jamás es actualizable
    assertNull(repo.tamanioLocal("no-existe"))
}
```

Si `HistoriasRepoTest.kt` no tiene un helper `repoConAssets`, definirlo en el test con el patrón existente:

```kotlin
private fun repoConAssets(http: ClienteHttp = ClienteHttp { throw IOException("sin red") }) = HistoriasRepo(
    leerAsset = { n ->
        when (n) {
            "historias/momotaro.json" -> momotaroJson
            "catalogo.json" -> catalogoJson
            else -> null
        }
    },
    listarAssetsHistorias = { listOf("momotaro.json") },
    dirDescargas = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
    dirImportadas = File.createTempFile("imp", "").let { it.delete(); it.mkdirs(); it },
    http = http,
)
```

(Para el test suspend de descarga usar `runTest` de `kotlinx.coroutines.test` — con el `ioDispatcher` default real alcanza porque `descargarHistoria` se awaitea directo.)

- [ ] **Step 2: Correr los tests y verificar que fallan**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests '*HistoriasRepoTest*'`
Expected: FAIL — `unresolved reference: tamanioLocal`

- [ ] **Step 3: Implementar `tamanioLocal`**

En `HistoriasRepo.kt`, después de `esImportada` (línea ~110):

```kotlin
/** Tamaño en bytes de la historia local, comparable con [EntradaCatalogo.tamanio]
 *  remoto (fix "la app nunca actualiza historias bundleadas", backlog feedback de
 *  uso 2026-07-13): el catálogo publica `tamaño` = os.path.getsize del JSON
 *  (emisor.py), así que para una descargada los bytes del archivo son EXACTAMENTE
 *  ese valor al momento de bajarla, y para una bundleada vale el `tamanio` de la
 *  entrada del catálogo asset (misma generación que el asset). Importadas y ids
 *  desconocidos devuelven null: no existen en el catálogo remoto, nunca son
 *  actualizables. Limitación aceptada (spec): un cambio remoto de igual tamaño
 *  en bytes no se detecta. */
fun tamanioLocal(id: String): Long? {
    val descargada = File(dirDescargas, "$id.json")
    if (descargada.exists()) return descargada.length()
    if (leerAsset("historias/$id.json") != null) {
        return catalogoLocal()?.historias?.firstOrNull { it.id == id }?.tamanio
    }
    return null
}
```

- [ ] **Step 4: Correr los tests y verificar que pasan**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests '*HistoriasRepoTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/HistoriasRepo.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/HistoriasRepoTest.kt
git commit -m "feat(app): tamanioLocal en HistoriasRepo para detectar updates de historias"
```

---

### Task 2: `BibliotecaViewModel` — flags de update por historia

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaViewModel.kt` (`refrescarCatalogo`, líneas 93-104; nuevo StateFlow)
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaViewModelTest.kt`

**Interfaces:**
- Consumes: `HistoriasRepo.tamanioLocal(id: String): Long?` (Task 1)
- Produces: `val actualizables: StateFlow<Set<String>>` — ids de historias locales con versión remota más nueva. `descargar(id)` ya existente re-descarga y (vía `cargar()` → `refrescarCatalogo()`) limpia el flag.

- [ ] **Step 1: Escribir los tests que fallan**

En `BibliotecaViewModelTest.kt`. El helper `repo(conRed)` existente siempre sirve el mismo catálogo por asset y por red; parametrizarlo (manteniendo la firma vieja como default para no tocar los tests existentes):

```kotlin
private fun repo(
    conRed: Boolean,
    catalogoAsset: String = catalogoJson,
    catalogoRemoto: String = catalogoJson,
): HistoriasRepo = HistoriasRepo(
    leerAsset = { n ->
        when (n) {
            "historias/momotaro.json" -> momotaroJson
            "catalogo.json" -> catalogoAsset
            else -> null
        }
    },
    listarAssetsHistorias = { listOf("momotaro.json") },
    dirDescargas = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
    dirImportadas = File.createTempFile("imp", "").let { it.delete(); it.mkdirs(); it },
    http = { url ->
        when {
            !conRed -> throw IOException("sin red")
            url == HistoriasRepo.URL_CATALOGO -> catalogoRemoto
            url == HistoriasRepo.urlHistoria("momotaro") -> momotaroJson
            else -> throw IOException("sin red")
        }
    },
    ioDispatcher = dispatcher,
)
```

Y el fabricador de catálogos con otro tamaño (el fixture trae `"tamaño": 67083` para momotaro):

```kotlin
// catálogo con el tamaño de momotaro reemplazado — para simular que el remoto (o el
// asset local viejo) difiere. El fixture real trae "tamaño": 67083 para momotaro.
private fun catalogoConTamanioMomotaro(tamanio: Long): String =
    catalogoJson.replace(Regex("\"tamaño\"\\s*:\\s*67083"), "\"tamaño\": $tamanio")
```

Tests nuevos:

```kotlin
// --- actualizables: fix "la app nunca actualiza historias bundleadas" (backlog
// feedback de uso 2026-07-13). refrescarCatalogo compara tamanio remoto vs local
// (HistoriasRepo.tamanioLocal) para cada historia local y publica los ids con
// update disponible; la UI muestra el botón Update que llama a descargar(id). ---

@Test
fun `catalogo remoto identico al local no marca updates`() = runTest {
    val vm = vm(conRed = true)
    vm.cargar(); advanceUntilIdle()
    assertTrue(vm.actualizables.value.isEmpty())
}

@Test
fun `tamanio remoto distinto marca update para la historia local`() = runTest {
    val repo = repo(conRed = true, catalogoRemoto = catalogoConTamanioMomotaro(1))
    val vm = BibliotecaViewModel(repo, ProgresoDaoFake(), DiccionarioFake(), ioDispatcher = dispatcher)
    vm.cargar(); advanceUntilIdle()
    assertEquals(setOf("momotaro"), vm.actualizables.value)
    // la historia local con update NO aparece además como descargable
    val catalogo = vm.catalogo.value as EstadoCatalogo.Ok
    assertTrue(catalogo.disponibles.none { it.id == "momotaro" })
}

@Test
fun `descargar re-descarga la historia y limpia el flag de update`() = runTest {
    // asset local viejo (tamaño 1) vs remoto real: el remoto declara EXACTAMENTE los
    // bytes del JSON que sirve el http fake, como en producción (tamaño = getsize).
    val tamanioReal = momotaroJson.toByteArray(Charsets.UTF_8).size.toLong()
    val repo = repo(
        conRed = true,
        catalogoAsset = catalogoConTamanioMomotaro(1),
        catalogoRemoto = catalogoConTamanioMomotaro(tamanioReal),
    )
    val vm = BibliotecaViewModel(repo, ProgresoDaoFake(), DiccionarioFake(), ioDispatcher = dispatcher)
    vm.cargar(); advanceUntilIdle()
    assertEquals(setOf("momotaro"), vm.actualizables.value)

    vm.descargar("momotaro"); advanceUntilIdle()
    assertTrue(vm.actualizables.value.isEmpty())  // descargada.length() == tamanio remoto
    assertEquals(1, vm.locales.value.count { it.historia.id == "momotaro" })  // sin duplicar
}

@Test
fun `sin red no hay flags de update y el error de catalogo se mantiene`() = runTest {
    val vm = vm(conRed = false)
    vm.cargar(); advanceUntilIdle()
    assertTrue(vm.actualizables.value.isEmpty())
    assertTrue(vm.catalogo.value is EstadoCatalogo.Error)
}
```

- [ ] **Step 2: Correr los tests y verificar que fallan**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests '*BibliotecaViewModelTest*'`
Expected: FAIL — `unresolved reference: actualizables`

- [ ] **Step 3: Implementar en el ViewModel**

En `BibliotecaViewModel.kt`, junto a los otros StateFlow:

```kotlin
// ids de historias locales cuya versión remota difiere (fix "la app nunca actualiza
// historias bundleadas", backlog feedback de uso 2026-07-13): comparación de
// tamanio remoto vs tamanioLocal en refrescarCatalogo. La UI muestra Update en la
// tarjeta y descargar(id) — que ya recarga todo vía cargar() — limpia el flag.
private val _actualizables = MutableStateFlow<Set<String>>(emptySet())
val actualizables: StateFlow<Set<String>> = _actualizables
```

Y `refrescarCatalogo` (reemplaza líneas 93-104):

```kotlin
fun refrescarCatalogo() {
    viewModelScope.launch {
        _catalogo.value = EstadoCatalogo.Cargando
        val idsLocales = _locales.value.map { it.historia.id }.toSet()
        _catalogo.value = historiasRepo.catalogoRemoto().fold(
            onSuccess = { catalogo ->
                // update disponible: historia YA local cuyo tamaño remoto difiere del
                // local (tamanioLocal: descargada = bytes del archivo, bundleada =
                // entrada del catálogo asset, importada/desconocida = null → nunca
                // actualizable). File I/O → ioDispatcher, como el resto de cargar().
                _actualizables.value = withContext(ioDispatcher) {
                    catalogo.historias
                        .filter { it.id in idsLocales }
                        .filter { remota ->
                            historiasRepo.tamanioLocal(remota.id)
                                ?.let { local -> local != remota.tamanio } == true
                        }
                        .map { it.id }
                        .toSet()
                }
                EstadoCatalogo.Ok(catalogo.historias.filter { it.id !in idsLocales })
            },
            onFailure = {
                _actualizables.value = emptySet()
                EstadoCatalogo.Error("Couldn't load the catalog")
            },
        )
    }
}
```

- [ ] **Step 4: Correr los tests y verificar que pasan (los viejos también)**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests '*BibliotecaViewModelTest*'`
Expected: PASS (los 7 existentes + 4 nuevos)

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaViewModel.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaViewModelTest.kt
git commit -m "feat(app): BibliotecaViewModel detecta updates de historias (tamanio remoto vs local)"
```

---

### Task 3: Botón Update en la tarjeta de biblioteca

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaScreen.kt` (card de historia local, líneas 124-197)

**Interfaces:**
- Consumes: `vm.actualizables: StateFlow<Set<String>>`, `vm.descargar(id)` (Task 2)

- [ ] **Step 1: Colectar el estado y agregar el botón**

En `BibliotecaScreen`, junto a los otros `collectAsState()` (línea ~86):

```kotlin
val actualizables by vm.actualizables.collectAsState()
```

En la card de historia local, después del bloque `if (item.progresoPct > 0) { ... }` (línea ~179), dentro del mismo `Column`:

```kotlin
// Update disponible (fix "la app nunca actualiza historias bundleadas"): el
// catálogo remoto trae esta historia con otro tamaño — re-descargarla pisa el
// asset/descarga vieja (prioridad descargada > asset en historiasLocales) y
// descargar() recarga todo, lo que limpia este flag.
if (item.historia.id in actualizables) {
    Button(
        onClick = { vm.descargar(item.historia.id) },
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text("Update")
    }
}
```

- [ ] **Step 2: Compilar**

Run: `cd app && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaScreen.kt
git commit -m "feat(app): boton Update en la tarjeta cuando el catalogo remoto trae version nueva"
```

---

### Task 4: Número de oración junto al piquito

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/lector/LectorScreen.kt` (indicador ▸ izquierdo, líneas 267-272)

**Interfaces:**
- Consumes: `estado.indiceActual` (ya disponible en `ListaOracionesLibre`)

- [ ] **Step 1: Reemplazar el `Text("▸")` izquierdo por número + piquito**

Reemplazar el primer indicador (líneas 267-272) por:

```kotlin
// Indicador de centro ("piquito", feedback de dispositivo): marca fija en el
// margen izquierdo que señala la línea vertical central del viewport, para que
// el usuario sepa dónde soltar/ubicar la oración que quiere leer durante el
// scroll libre. Vive FUERA de la LazyColumn (hijo directo de este
// BoxWithConstraints) así que NO scrollea con el contenido; no tiene ningún
// modifier de gestos (sin `clickable` ni `pointerInput`) así que no intercepta
// toques — el scroll/tap de la lista de abajo pasa de largo.
// Al lado va el número (1-based) de la oración enfocada (backlog feedback de
// uso 2026-07-13: poder reportar casos puntuales por número de oración).
Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
) {
    if (estado.indiceActual >= 0) {
        Text(
            text = "${estado.indiceActual + 1}",
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
    Text(
        text = "▸",
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        style = MaterialTheme.typography.titleLarge,
    )
}
```

El indicador derecho `◂` (líneas 279-284) queda tal cual.

- [ ] **Step 2: Compilar**

Run: `cd app && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/lector/LectorScreen.kt
git commit -m "feat(app): numero de oracion junto al piquito del lector"
```

---

### Task 5: Estado de selección en `LectorViewModel`

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/lector/LectorViewModel.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/ui/lector/LectorViewModelTest.kt`

**Interfaces:**
- Produces (para Tasks 6-7):
  - `data class SeleccionTexto(val indiceOracion: Int, val inicio: Int, val fin: Int)` (offsets de chars sobre `oracion.texto`, fin exclusivo)
  - `EstadoLector.seleccion: SeleccionTexto?` y `EstadoLector.textoSeleccionado: String?`
  - `fun iniciarSeleccion(indice: Int, token: PalabraToken)` — long-press: ancla y enfoca, sin abrir sheet
  - `fun tapPalabra(indice: Int, token: PalabraToken)` — tap unificado: extiende la selección si está activa en la misma oración; si no, comportamiento original (enfocar + consulta)
  - `fun limpiarSeleccion()`
  - `mover()` y `enfocar(otro índice)` limpian la selección; `enfocar(mismo índice)` la conserva

- [ ] **Step 1: Escribir los tests que fallan**

En `LectorViewModelTest.kt`:

```kotlin
// --- selección de rango de tokens (backlog feedback de uso 2026-07-13: buscar en
// el browser expresiones largas/frases adverbiales que el diccionario no tiene).
// Long-press ancla (iniciarSeleccion), tap con selección activa en la MISMA oración
// extiende el rango (tapPalabra), tap en otra oración o navegar limpia. El texto
// seleccionado es el substring del texto de la oración: incluye partículas y
// tokens no-contenido intermedios, sin furigana. ---

@Test
fun `long-press ancla la seleccion en el token y enfoca la oracion`() = runTest {
    val vm = vmMomotaro(ProgresoDaoFake())
    vm.cargar(); advanceUntilIdle()
    vm.avanzar(); advanceUntilIdle()
    val indice = vm.estado.value.indiceActual + 1  // oración NO enfocada todavía
    val token = vm.estado.value.oraciones[indice].tokens.first { it.esContenido }
    vm.iniciarSeleccion(indice, token); advanceUntilIdle()
    val estado = vm.estado.value
    assertEquals(SeleccionTexto(indice, token.inicio, token.fin), estado.seleccion)
    assertEquals(indice, estado.indiceActual)   // long-press también enfoca
    assertNull(estado.consulta)                  // y NO abre el sheet de diccionario
}

@Test
fun `tap con seleccion activa extiende el rango hacia ambos lados`() = runTest {
    val vm = vmMomotaro(ProgresoDaoFake())
    vm.cargar(); advanceUntilIdle()
    vm.avanzar(); advanceUntilIdle()
    val indice = vm.estado.value.indiceActual
    val tokens = vm.estado.value.oraciones[indice].tokens.filter { it.esContenido }
    // ancla en el del medio, extiende al último y después al primero
    vm.iniciarSeleccion(indice, tokens[1]); advanceUntilIdle()
    vm.tapPalabra(indice, tokens.last()); advanceUntilIdle()
    assertEquals(tokens.last().fin, vm.estado.value.seleccion?.fin)
    vm.tapPalabra(indice, tokens.first()); advanceUntilIdle()
    val seleccion = vm.estado.value.seleccion!!
    assertEquals(tokens.first().inicio, seleccion.inicio)
    assertEquals(tokens.last().fin, seleccion.fin)
    assertNull(vm.estado.value.consulta)  // extender jamás abre el sheet
}

@Test
fun `textoSeleccionado es el substring de la oracion, con particulas intermedias y sin furigana`() = runTest {
    val vm = vmMomotaro(ProgresoDaoFake())
    vm.cargar(); advanceUntilIdle()
    vm.avanzar(); advanceUntilIdle()
    val indice = vm.estado.value.indiceActual
    val plana = vm.estado.value.oraciones[indice]
    val contenido = plana.tokens.filter { it.esContenido }
    vm.iniciarSeleccion(indice, contenido[0]); advanceUntilIdle()
    vm.tapPalabra(indice, contenido[1]); advanceUntilIdle()
    // substring crudo del texto original: entra TODO lo que hay entre ambos tokens
    assertEquals(
        plana.oracion.texto.substring(contenido[0].inicio, contenido[1].fin),
        vm.estado.value.textoSeleccionado,
    )
}

@Test
fun `tap sin seleccion mantiene el comportamiento original (enfoca y abre consulta)`() = runTest {
    val dao = ProgresoDaoFake()
    val vm = vmMomotaro(dao)
    vm.cargar(); advanceUntilIdle()
    vm.avanzar(); advanceUntilIdle()
    val indice = vm.estado.value.indiceActual
    val token = vm.estado.value.oraciones[indice].tokens.first { it.esContenido }
    vm.tapPalabra(indice, token); advanceUntilIdle()
    assertNotNull(vm.estado.value.consulta)
    assertNull(vm.estado.value.seleccion)
    assertEquals(1, dao.palabrasDe("momotaro").size)
}

@Test
fun `tap en OTRA oracion con seleccion activa la limpia y vuelve al comportamiento normal`() = runTest {
    val vm = vmMomotaro(ProgresoDaoFake())
    vm.cargar(); advanceUntilIdle()
    vm.avanzar(); advanceUntilIdle()
    val indice = vm.estado.value.indiceActual
    vm.iniciarSeleccion(indice, vm.estado.value.oraciones[indice].tokens.first { it.esContenido })
    advanceUntilIdle()
    val otro = indice + 1
    val tokenOtro = vm.estado.value.oraciones[otro].tokens.first { it.esContenido }
    vm.tapPalabra(otro, tokenOtro); advanceUntilIdle()
    assertNull(vm.estado.value.seleccion)
    assertNotNull(vm.estado.value.consulta)
    assertEquals(otro, vm.estado.value.indiceActual)
}

@Test
fun `navegar con Previous-Next limpia la seleccion`() = runTest {
    val vm = vmMomotaro(ProgresoDaoFake())
    vm.cargar(); advanceUntilIdle()
    vm.avanzar(); advanceUntilIdle()
    val indice = vm.estado.value.indiceActual
    vm.iniciarSeleccion(indice, vm.estado.value.oraciones[indice].tokens.first { it.esContenido })
    advanceUntilIdle()
    vm.avanzar(); advanceUntilIdle()
    assertNull(vm.estado.value.seleccion)
}

@Test
fun `enfocar otra oracion limpia la seleccion pero enfocar la misma la conserva`() = runTest {
    val vm = vmMomotaro(ProgresoDaoFake())
    vm.cargar(); advanceUntilIdle()
    vm.avanzar(); advanceUntilIdle()
    val indice = vm.estado.value.indiceActual
    vm.iniciarSeleccion(indice, vm.estado.value.oraciones[indice].tokens.first { it.esContenido })
    advanceUntilIdle()
    vm.enfocar(indice); advanceUntilIdle()   // settle del scroll sobre la MISMA oración
    assertNotNull(vm.estado.value.seleccion) // no borra la selección en curso
    vm.enfocar(indice + 1); advanceUntilIdle()
    assertNull(vm.estado.value.seleccion)
}

@Test
fun `limpiarSeleccion borra la seleccion`() = runTest {
    val vm = vmMomotaro(ProgresoDaoFake())
    vm.cargar(); advanceUntilIdle()
    vm.avanzar(); advanceUntilIdle()
    val indice = vm.estado.value.indiceActual
    vm.iniciarSeleccion(indice, vm.estado.value.oraciones[indice].tokens.first { it.esContenido })
    advanceUntilIdle()
    vm.limpiarSeleccion(); advanceUntilIdle()
    assertNull(vm.estado.value.seleccion)
}
```

- [ ] **Step 2: Correr los tests y verificar que fallan**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests '*LectorViewModelTest*'`
Expected: FAIL — `unresolved reference: SeleccionTexto` / `iniciarSeleccion` / `tapPalabra`

- [ ] **Step 3: Implementar**

En `LectorViewModel.kt`. Tipo nuevo (arriba de `EstadoLector`):

```kotlin
/** Selección de un rango de tokens dentro de UNA oración (backlog feedback de uso
 *  2026-07-13: buscar en el browser expresiones/frases que el diccionario no tiene).
 *  [inicio]/[fin] son offsets de chars sobre `oracion.texto` (fin EXCLUSIVO, mismo
 *  contrato que [PalabraToken]): el rango cubre tokens completos, y el texto
 *  seleccionado es el substring crudo — partículas intermedias incluidas, sin
 *  furigana. */
data class SeleccionTexto(val indiceOracion: Int, val inicio: Int, val fin: Int)
```

En `EstadoLector`, campo y derivado:

```kotlin
// selección activa (long-press + taps que extienden, ver SeleccionTexto); null = sin
// selección. La limpia cualquier navegación (mover/enfocar a otra oración).
val seleccion: SeleccionTexto? = null,
```

```kotlin
val textoSeleccionado: String? get() = seleccion?.let { s ->
    oraciones.getOrNull(s.indiceOracion)?.oracion?.texto?.substring(s.inicio, s.fin)
}
```

Métodos del ViewModel (después de `tocarPalabra`):

```kotlin
/** Long-press sobre un token: ancla (o re-ancla) la selección en ese token y enfoca
 *  su oración. No abre el sheet de diccionario — eso queda para el tap simple. */
fun iniciarSeleccion(indice: Int, token: PalabraToken) {
    val estado = _estado.value
    if (indice !in estado.oraciones.indices) return
    enfocar(indice)  // limpia una selección previa en otra oración, enfoca y persiste
    _estado.value = _estado.value.copy(seleccion = SeleccionTexto(indice, token.inicio, token.fin))
}

/** Tap sobre un token (reemplaza el par enfocar+tocarPalabra que armaba la Screen):
 *  con selección activa en la MISMA oración, extiende el rango a la unión
 *  [min(inicio), max(fin)] en vez de abrir el diccionario; en cualquier otro caso
 *  (sin selección, u otra oración) comportamiento original — enfocar limpia la
 *  selección vieja y tocarPalabra abre la consulta. */
fun tapPalabra(indice: Int, token: PalabraToken) {
    val seleccion = _estado.value.seleccion
    if (seleccion != null && seleccion.indiceOracion == indice) {
        _estado.value = _estado.value.copy(
            seleccion = seleccion.copy(
                inicio = minOf(seleccion.inicio, token.inicio),
                fin = maxOf(seleccion.fin, token.fin),
            ),
        )
        return
    }
    enfocar(indice)
    tocarPalabra(token)
}

fun limpiarSeleccion() {
    _estado.value = _estado.value.copy(seleccion = null)
}
```

Limpieza al navegar — en `mover()` (línea ~151), agregar `seleccion = null` al `copy`:

```kotlin
_estado.value = estado.copy(
    indiceActual = nuevo,
    progresoGuardado = if (nuevo >= 0) nuevo else estado.progresoGuardado,
    centradoPedido = estado.centradoPedido + 1,
    seleccion = null,
)
```

Y en `enfocar()` (línea ~175) — el early-return `if (indice == estado.indiceActual) return` ya conserva la selección al re-asentar sobre la misma oración; el `copy` del cambio real suma la limpieza:

```kotlin
_estado.value = estado.copy(indiceActual = indice, progresoGuardado = indice, seleccion = null)
```

- [ ] **Step 4: Correr los tests y verificar que pasan (viejos incluidos)**

Run: `cd app && ./gradlew :app:testDebugUnitTest --tests '*LectorViewModelTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/lector/LectorViewModel.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/ui/lector/LectorViewModelTest.kt
git commit -m "feat(app): estado de seleccion de rango de tokens en LectorViewModel"
```

---

### Task 6: Long-press y resaltado en `TextoConFurigana` + wiring en el lector

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/lector/TextoConFurigana.kt` (firma + `FilaGrupo`)
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/lector/LectorScreen.kt` (`ItemOracion` y su callsite, líneas 241-320)

**Interfaces:**
- Consumes: `vm.tapPalabra(indice, token)`, `vm.iniciarSeleccion(indice, token)`, `estado.seleccion` (Task 5)
- Produces: `TextoConFurigana(..., onLongPressPalabra: ((PalabraToken) -> Unit)? = null, rangoSeleccion: IntRange? = null)` — params nuevos con default para no romper otros callsites; `rangoSeleccion` en offsets de chars, construido como `inicio until fin`.

- [ ] **Step 1: Ampliar `TextoConFurigana`**

Firma (params nuevos al final, con default):

```kotlin
@Composable
fun TextoConFurigana(
    tokens: List<PalabraToken>,
    gruposFurigana: List<GrupoFurigana>,
    furiganaActiva: Boolean,
    katakanaActiva: Boolean,
    onTapPalabra: ((PalabraToken) -> Unit)?,
    // Selección de rango (backlog feedback de uso 2026-07-13): long-press ancla la
    // selección (lo maneja el VM); acá solo se reporta el gesto y se pinta el fondo
    // de los tokens cuyo span cae dentro de rangoSeleccion (chars de la oración,
    // construido `inicio until fin` — IntRange con last INCLUSIVO). Defaults null:
    // los callsites sin selección (p.ej. previews de import) no cambian.
    onLongPressPalabra: ((PalabraToken) -> Unit)? = null,
    rangoSeleccion: IntRange? = null,
)
```

Propagar ambos params a las 2 llamadas internas a `FilaGrupo`. En `FilaGrupo`:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilaGrupo(
    segmentos: List<Segmento>,
    estiloBase: TextStyle,
    katakanaActiva: Boolean,
    onTapPalabra: ((PalabraToken) -> Unit)?,
    onLongPressPalabra: ((PalabraToken) -> Unit)?,
    rangoSeleccion: IntRange?,
) {
    Row {
        for (segmento in segmentos) {
            // token seleccionado si su span [inicio, fin) pisa el rango de selección
            // (last es inclusivo: rango construido como `inicio until fin`).
            val seleccionado = rangoSeleccion != null &&
                segmento.token.inicio <= rangoSeleccion.last && segmento.token.fin > rangoSeleccion.first
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .then(
                        if (seleccionado) {
                            Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                        } else Modifier,
                    )
                    .then(
                        if (segmento.token.esContenido && onTapPalabra != null) {
                            Modifier.combinedClickable(
                                onClick = { onTapPalabra(segmento.token) },
                                onLongClick = onLongPressPalabra?.let { alMantener ->
                                    { alMantener(segmento.token) }
                                },
                            )
                        } else Modifier,
                    ),
            ) {
                // ... cuerpo idéntico al actual (ruby + superficie) ...
            }
        }
    }
}
```

Imports nuevos: `androidx.compose.foundation.ExperimentalFoundationApi`, `androidx.compose.foundation.background`, `androidx.compose.foundation.combinedClickable` (reemplaza el import de `clickable` si queda sin uso).

- [ ] **Step 2: Wiring en `LectorScreen`**

`ItemOracion` (líneas 294-320) suma params y los pasa:

```kotlin
@Composable
private fun ItemOracion(
    esActual: Boolean,
    plana: OracionPlana,
    furiganaActiva: Boolean,
    katakanaActiva: Boolean,
    onTapPalabra: (PalabraToken) -> Unit,
    onLongPressPalabra: (PalabraToken) -> Unit,
    // rango de selección SOLO si pertenece a esta oración (ya filtrado en el
    // callsite de itemsIndexed, mismo criterio que esActual: nunca entra
    // EstadoLector completo — un cambio de selección solo recompone los items
    // cuyo param cambió).
    rangoSeleccion: IntRange?,
) {
    // ... alpha animada idéntica ...
    Box(Modifier.alpha(alphaAnimada).fillMaxWidth()) {
        TextoConFurigana(
            tokens = plana.tokens,
            gruposFurigana = plana.gruposFurigana,
            furiganaActiva = furiganaActiva,
            katakanaActiva = katakanaActiva,
            onTapPalabra = onTapPalabra,
            onLongPressPalabra = onLongPressPalabra,
            rangoSeleccion = rangoSeleccion,
        )
    }
}
```

Callsite en `itemsIndexed` (líneas 247-256):

```kotlin
ItemOracion(
    esActual = indice == estado.indiceActual,
    plana = plana,
    furiganaActiva = estado.furiganaActiva,
    katakanaActiva = estado.katakanaActiva,
    onTapPalabra = { token -> vm.tapPalabra(indice, token) },
    onLongPressPalabra = { token -> vm.iniciarSeleccion(indice, token) },
    rangoSeleccion = estado.seleccion
        ?.takeIf { it.indiceOracion == indice }
        ?.let { it.inicio until it.fin },
)
```

(El par `vm.enfocar(indice); vm.tocarPalabra(token)` desaparece: `tapPalabra` lo absorbe.)

- [ ] **Step 3: Compilar y correr toda la suite**

Run: `cd app && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/lector/TextoConFurigana.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/lector/LectorScreen.kt
git commit -m "feat(app): long-press ancla seleccion y resaltado de tokens seleccionados"
```

---

### Task 7: Barra contextual de selección (Search web / Copy / cancelar)

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/lector/LectorScreen.kt` (bottomBar, líneas 54-66; composable nuevo `BarraSeleccion` + helper `buscarEnWeb`)

**Interfaces:**
- Consumes: `estado.textoSeleccionado`, `vm.limpiarSeleccion()` (Task 5)

- [ ] **Step 1: Implementar barra + intents**

En `LectorScreen`, obtener contexto y clipboard junto a los otros `remember`/state (línea ~34):

```kotlin
val contexto = LocalContext.current
val portapapeles = LocalClipboardManager.current
```

`bottomBar` pasa a:

```kotlin
bottomBar = {
    val seleccionado = estado.textoSeleccionado
    if (seleccionado != null) {
        // Con selección activa la barra de navegación cede su lugar a las acciones
        // de selección (backlog feedback de uso 2026-07-13): Search web abre el
        // browser con la frase (el diccionario no tiene expresiones largas), Copy
        // al portapapeles, ✕ cancela. Previous/Next vuelven al limpiar.
        BarraSeleccion(
            texto = seleccionado,
            onBuscarWeb = { buscarEnWeb(contexto, seleccionado) },
            onCopiar = {
                portapapeles.setText(AnnotatedString(seleccionado))
                vm.limpiarSeleccion()
            },
            onCancelar = vm::limpiarSeleccion,
        )
    } else {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = vm::retroceder, enabled = estado.indiceActual > -1) {
                Text("Previous")
            }
            Button(onClick = vm::avanzar) {
                Text(if (estado.enPortada) (if (estado.progresoGuardado >= 0) "Continue reading" else "Start reading") else "Next")
            }
        }
    }
},
```

Composable y helper nuevos al final del archivo:

```kotlin
/** Barra contextual de selección: texto elegido + Search web / Copy / cancelar.
 *  Reemplaza a Previous/Next en el bottomBar mientras hay selección activa. */
@Composable
private fun BarraSeleccion(
    texto: String,
    onBuscarWeb: () -> Unit,
    onCopiar: () -> Unit,
    onCancelar: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            texto,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCopiar) { Text("Copy") }
        Button(onClick = onBuscarWeb) { Text("Search web") }
        TextButton(onClick = onCancelar) { Text("✕") }
    }
}

/** Abre la búsqueda web del texto seleccionado. ACTION_WEB_SEARCH primero (respeta
 *  el buscador default del dispositivo); si no hay handler (package visibility de
 *  Android 11+ o dispositivo sin app de búsqueda), fallback a una URL de Google por
 *  ACTION_VIEW, que cualquier browser resuelve. Si tampoco hay browser (emulador
 *  pelado), no crashear: la selección queda para Copy. */
private fun buscarEnWeb(contexto: Context, texto: String) {
    val busqueda = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, texto)
    runCatching { contexto.startActivity(busqueda) }.recoverCatching {
        val url = "https://www.google.com/search?q=${Uri.encode(texto)}"
        contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
```

Imports nuevos en `LectorScreen.kt`: `android.app.SearchManager`, `android.content.Context`, `android.content.Intent`, `android.net.Uri`, `androidx.compose.ui.platform.LocalClipboardManager`, `androidx.compose.ui.platform.LocalContext`, `androidx.compose.ui.text.AnnotatedString`, `androidx.compose.ui.text.style.TextOverflow`.

- [ ] **Step 2: Compilar y correr toda la suite + invariante U+001F**

Run: `cd app && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest && grep -rcP '\x1f' app/app/src/main/kotlin | grep -v ':0' || echo OK`
Expected: BUILD SUCCESSFUL, tests PASS, `OK`

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/lector/LectorScreen.kt
git commit -m "feat(app): barra de seleccion con Search web y Copy en el lector"
```

---

### Task 8: Verificación end-to-end en tablet

**Files:** ninguno (build + smoke manual).

- [ ] **Step 1: Suite completa**

Run: `cd app && ./gradlew test`
Expected: BUILD SUCCESSFUL, 0 failures

- [ ] **Step 2: APK debug e instalación**

Run: `cd app && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success` (tablet Xiaomi: si MIUI bloquea, confirmar el diálogo de instalación en pantalla).

- [ ] **Step 3: Smoke manual (checklist para el usuario)**

- Biblioteca: sin cambios visibles (catálogo actual == assets del APK recién buildeado → no debería haber badges Update salvo que main tenga un catálogo más nuevo que los assets).
- Lector: número de oración aparece junto a ▸ y cambia al scrollear/navegar; oculto en portada.
- Long-press en una palabra: fondo resaltado + barra con el texto; taps en otras palabras de la misma oración extienden el resaltado y el texto de la barra (partículas intermedias incluidas).
- Search web abre el browser/buscador con la frase; Copy deja el texto en el portapapeles; ✕ o navegar con Previous/Next limpia.
- Tap simple sin selección sigue abriendo el sheet de diccionario.

- [ ] **Step 4: Review final de branch + PR**

Review de la branch completa (proceso del repo: reviewer subagent / code-review), luego PR contra `main` con el resumen de los 3 items y referencia al spec. Actualizar `docs/ESTADO.md` (fila nueva en la tabla + tachar items 1-3 de la sección app del backlog feedback de uso) en el mismo PR.

---

## Verificación global

1. `cd app && ./gradlew test` — verde.
2. `grep -rcP '\x1f' app/app/src/main/kotlin | grep -v ':0'` — vacío.
3. Smoke en tablet (Task 8 Step 3).
4. Para probar el badge Update de punta a punta hace falta un catálogo remoto distinto de los assets — llega naturalmente con el PR B (regen con traducciones): tras mergear B, la app instalada con este PR debe mostrar Update en las 10 historias.
