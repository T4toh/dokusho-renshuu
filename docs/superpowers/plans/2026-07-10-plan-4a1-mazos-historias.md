# Plan 4a.1: mazo Anki por historia — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> Spec: `docs/superpowers/specs/2026-07-10-plan-4a1-mazos-historias-design.md`

**Goal:** Botón "Export Stories deck" que genera UN `dokusho-stories.apkg` con un subdeck Anki por historia local (`Dokusho — Stories::桃太郎`), una carta por kanji único de esa historia en orden de primera aparición, con oraciones de ejemplo SOLO de esa historia.

**Architecture:** Reutiliza la infraestructura Anki de 4a entera. `ModeloNotas` gana GUID por historia+kanji y deck IDs determinísticos por historia; `EscritorApkg` se generaliza de "2 mazos fijos" a "lista de mazos" (los de 4a pasan por el mismo camino vía overload); `ArmadorMazos` gana `armarHistorias()`; `ExportViewModel/Screen` ganan el tercer tipo de export. Sin dependencias nuevas, sin cambios de datos.

**Tech Stack:** El de 4a: Kotlin 2.3 + Compose (BOM 2026.06.01), AGP 9.2 built-in Kotlin, Robolectric para SQLite en tests de escritor, JVM plano para el resto. Tests con maxHeapSize 2g.

## Global Constraints

- Branch `feature/plan-4a1-mazos-historias` desde main actualizado; PR al final.
- Código/comentarios en español; strings de UI en INGLÉS.
- TDD por task; suite completa verde antes de cada commit (base actual: **127 tests app**). Reportar counts exactos.
- Comandos gradle SIEMPRE desde `app/` (`cd app && ./gradlew test`) — cuidado con el cwd, `app/app` NO tiene wrapper.
- **U+001F**: el separador de campos Anki se escribe como escape Kotlin de seis caracteres visibles (backslash, u, 0, 0, 1, f), NUNCA como carácter literal. Tras tocar `EscritorApkg.kt` o sus tests, verificar `grep -cP '\x1f' <archivo>` == 0 (el tooling ya lo decodificó silenciosamente dos veces en 4a).
- No romper 4a: mismos model IDs, deck IDs, nombres, GUIDs y comportamiento observable de los mazos Words/Kanji. Los tests existentes de `EscritorApkgTest`/`ArmadorMazosTest`/`ExportViewModelTest`/`ModeloNotasTest` deben quedar verdes SIN tocar sus asserts (solo se permite agregar tests).
- Sin dependencias nuevas (si apareciera la necesidad: regla org de supply-chain, nunca versiones <7 días — no debería aplicar acá).

## File Structure

- `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ModeloNotas.kt` — Task 1: `claveGuidPropia` en NotaKanji, `deckIdDeHistoria`, prefijo de nombre de subdeck.
- `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/EscritorApkg.kt` — Task 2: `MazoNotas` + `escribir(destino, mazos)` + overload compat.
- `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazos.kt` — Task 3: `armarHistorias()`.
- `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportViewModel.kt` + `ExportScreen.kt` — Task 4: `TipoExport.STORIES`, contador de historias, botón, resumen.
- Tests espejo en `app/app/src/test/kotlin/...` (mismos paquetes).

---

### Task 1: ModeloNotas — GUID por historia+kanji y deck IDs de subdecks

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ModeloNotas.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/ModeloNotasTest.kt` (agregar tests, no tocar existentes)

**Interfaces:**
- Consumes: `NotaKanji` y `ModeloNotas.guidDe(clave)` existentes (4a).
- Produces (Tasks 2-4 dependen de esto):
  - `NotaKanji` gana parámetro final `claveGuidPropia: String? = null`; `claveGuid` pasa de `get() = "kanji:$kanji"` a `get() = claveGuidPropia ?: "kanji:$kanji"`. Constructores existentes intactos (param con default al final).
  - `ModeloNotas.deckIdDeHistoria(idHistoria: String): Long` — determinístico, 13 dígitos positivos, disjunto de `DECK_ID_WORDS`/`DECK_ID_KANJI`.
  - `ModeloNotas.nombreDeckHistoria(titulo: String): String` = `"Dokusho — Stories::" + titulo`.

- [ ] **Step 1: Tests que fallan.** Agregar a `ModeloNotasTest.kt`:

```kotlin
@Test
fun `claveGuid de NotaKanji usa claveGuidPropia si esta presente`() {
    val base = NotaKanji("洗", "セン", "あら.う", "wash", "hard")
    assertEquals("kanji:洗", base.claveGuid)
    val deHistoria = base.copy(claveGuidPropia = "story:momotaro:洗")
    assertEquals("story:momotaro:洗", deHistoria.claveGuid)
    // GUIDs resultantes disjuntos: misma nota, mazos distintos, notas Anki distintas
    assertNotEquals(ModeloNotas.guidDe(base.claveGuid), ModeloNotas.guidDe(deHistoria.claveGuid))
}

@Test
fun `deckIdDeHistoria es estable, de 13 digitos y disjunto de los IDs fijos`() {
    val id = ModeloNotas.deckIdDeHistoria("momotaro")
    assertEquals(id, ModeloNotas.deckIdDeHistoria("momotaro"))  // determinístico
    assertTrue("13 dígitos: $id", id in 1_000_000_000_000L..9_999_999_999_999L)
    assertNotEquals(ModeloNotas.DECK_ID_WORDS, id)
    assertNotEquals(ModeloNotas.DECK_ID_KANJI, id)
    // historias distintas → decks distintos
    assertNotEquals(id, ModeloNotas.deckIdDeHistoria("urashima_taro"))
}

@Test
fun `nombreDeckHistoria arma el subdeck con la sintaxis de dos puntos de Anki`() {
    assertEquals("Dokusho — Stories::桃太郎", ModeloNotas.nombreDeckHistoria("桃太郎"))
}
```

- [ ] **Step 2: Verificar RED.** `cd app && ./gradlew test --tests '*ModeloNotasTest*'` → FAIL compilación (`claveGuidPropia`, `deckIdDeHistoria` no existen).

- [ ] **Step 3: Implementación mínima.**

En `NotaKanji`: agregar al final de la lista de parámetros:

```kotlin
    val oraciones: List<String> = emptyList(),
    // GUID alternativo para mazos por historia (Plan 4a.1): "story:<id>:<kanji>".
    // Si compartiera el guid "kanji:<kanji>" con el mazo Dokusho — Kanji, el import
    // de Anki (match global por guid) pisaría esa nota en vez de crear la carta en
    // el subdeck de la historia.
    val claveGuidPropia: String? = null,
```

y cambiar `claveGuid`:

```kotlin
    val claveGuid: String get() = claveGuidPropia ?: "kanji:$kanji"
```

En `object ModeloNotas` (junto a las constantes de deck):

```kotlin
    const val NOMBRE_DECK_STORIES: String = "Dokusho — Stories"

    fun nombreDeckHistoria(titulo: String): String = "$NOMBRE_DECK_STORIES::$titulo"

    /** Deck ID determinístico por historia: primeros 8 bytes de SHA-256 de
     *  "deck:<idHistoria>" mapeados al rango de 13 dígitos que usa Anki. Constante
     *  entre ejecuciones (mismo requisito que los IDs fijos: si cambiara, cada
     *  export crearía un deck nuevo en vez de actualizar). Los IDs fijos de 4a
     *  terminan en 1xx (1720000000101/102); la colisión con este hash es
     *  teóricamente posible pero despreciable (2 IDs sobre un rango de 9e12). */
    fun deckIdDeHistoria(idHistoria: String): Long {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("deck:$idHistoria".toByteArray(Charsets.UTF_8))
        var n = 0L
        for (i in 0 until 8) n = (n shl 8) or (hash[i].toLong() and 0xff)
        return (n and Long.MAX_VALUE) % 9_000_000_000_000L + 1_000_000_000_000L
    }
```

- [ ] **Step 4: Verificar GREEN.** `cd app && ./gradlew test --tests '*ModeloNotasTest*'` → PASS. Después suite completa: `cd app && ./gradlew test` → 130 tests (127 + 3), 0 failures.

- [ ] **Step 5: Commit.**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ModeloNotas.kt app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/ModeloNotasTest.kt
git commit -m "feat(app): GUID por historia+kanji y deck IDs de subdecks en ModeloNotas"
```

---

### Task 2: EscritorApkg — N mazos en un archivo

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/EscritorApkg.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/EscritorApkgTest.kt` (agregar tests; los 5+ existentes quedan intactos y verdes)

**Interfaces:**
- Consumes: `NotaKanji.claveGuidPropia` y `ModeloNotas.deckIdDeHistoria`/`nombreDeckHistoria` (Task 1).
- Produces (Task 4 depende de esto):
  - `data class MazoNotas(val deckId: Long, val nombre: String, val notasWords: List<NotaWords> = emptyList(), val notasKanji: List<NotaKanji> = emptyList())` en `EscritorApkg.kt` (top-level del archivo, mismo paquete).
  - `EscritorApkg.escribir(destino: File, mazos: List<MazoNotas>)` — nueva entrada general. `require(mazos.isNotEmpty())`.
  - `EscritorApkg.escribir(destino: File, notasWords: List<NotaWords>, notasKanji: List<NotaKanji>)` — QUEDA, como overload que delega: `escribir(destino, listOf(MazoNotas(DECK_ID_WORDS, NOMBRE_DECK_WORDS, notasWords = notasWords), MazoNotas(DECK_ID_KANJI, NOMBRE_DECK_KANJI, notasKanji = notasKanji)))`. Así los mazos de 4a pasan por el camino nuevo y los tests existentes verifican la regresión gratis.
  - Semántica: `col.decks` = Default ("1") + UNA entrada por mazo de la lista (aunque esté vacío — el overload de 4a siempre lista Words y Kanji, comportamiento idéntico al actual). Un archivo solo-historias NO contiene los decks Words/Kanji. Cards con `did` = deckId de su mazo. `due` incremental global en el orden de la lista. `conf.activeDecks` = deckIds de la lista; `conf.curDeck` = deckId del primer mazo.

**ATENCIÓN U+001F** (Global Constraints): tras editar, `grep -cP '\x1f' app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/EscritorApkg.kt` debe dar 0.

- [ ] **Step 1: Tests que fallan.** Agregar a `EscritorApkgTest.kt` (mismo patrón que los existentes: escribir a un temp, deszipear, abrir con `SQLiteDatabase`/sqlite y assertear; copiar los helpers de deszipeo/lectura que el archivo ya tenga en vez de duplicar lógica nueva):

```kotlin
@Test
fun `escribir con mazos de historias crea un subdeck por historia y asigna las cards`() {
    val notaMomotaro = NotaKanji("山", "サン", "やま", "mountain", "", claveGuidPropia = "story:momotaro:山")
    val notaUrashima = NotaKanji("浦", "ホ", "うら", "bay", "", claveGuidPropia = "story:urashima_taro:浦")
    val mazos = listOf(
        MazoNotas(
            deckId = ModeloNotas.deckIdDeHistoria("momotaro"),
            nombre = ModeloNotas.nombreDeckHistoria("桃太郎"),
            notasKanji = listOf(notaMomotaro),
        ),
        MazoNotas(
            deckId = ModeloNotas.deckIdDeHistoria("urashima_taro"),
            nombre = ModeloNotas.nombreDeckHistoria("浦島太郎"),
            notasKanji = listOf(notaUrashima),
        ),
    )
    val destino = File(dirTemp(), "stories.apkg")
    EscritorApkg.escribir(destino, mazos)

    abrirColeccion(destino).use { db ->
        // decks: Default + los 2 subdecks, SIN Words/Kanji
        val decks = leerDecksJson(db)  // helper existente o análogo al de los tests de 4a
        assertEquals(setOf("1",
            ModeloNotas.deckIdDeHistoria("momotaro").toString(),
            ModeloNotas.deckIdDeHistoria("urashima_taro").toString()), decks.keys)
        assertEquals("Dokusho — Stories::桃太郎",
            decks[ModeloNotas.deckIdDeHistoria("momotaro").toString()]!!.jsonObject["name"]!!.jsonPrimitive.content)
        // cada card en el did de su mazo, due incremental global (1, 2)
        db.rawQuery("SELECT did, due FROM cards ORDER BY due", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(ModeloNotas.deckIdDeHistoria("momotaro"), c.getLong(0)); assertEquals(1L, c.getLong(1))
            assertTrue(c.moveToNext())
            assertEquals(ModeloNotas.deckIdDeHistoria("urashima_taro"), c.getLong(0)); assertEquals(2L, c.getLong(1))
        }
        // guid de la nota = guidDe de la clave propia
        db.rawQuery("SELECT guid FROM notes ORDER BY id LIMIT 1", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(ModeloNotas.guidDe("story:momotaro:山"), c.getString(0))
        }
    }
}

@Test
fun `escribir con lista vacia de mazos falla rapido`() {
    val destino = File(dirTemp(), "vacio.apkg")
    assertThrows(IllegalArgumentException::class.java) {
        EscritorApkg.escribir(destino, emptyList())
    }
}
```

(Ajustar nombres de helpers a los REALES del test existente — leerlo primero. Si no hay helper de decks JSON, extraer el `col.decks` con `db.rawQuery("SELECT decks FROM col", ...)` + `Json.parseToJsonElement(...).jsonObject` como hagan los tests de 4a.)

- [ ] **Step 2: Verificar RED.** `cd app && ./gradlew test --tests '*EscritorApkgTest*'` → FAIL compilación (`MazoNotas` no existe).

- [ ] **Step 3: Implementación.** En `EscritorApkg.kt`:

Top-level, antes del object:

```kotlin
/** Un mazo dentro del .apkg: deck propio + sus notas. Words y Kanji usan modelos
 *  distintos, por eso dos listas — un mazo normalmente llena una sola. */
data class MazoNotas(
    val deckId: Long,
    val nombre: String,
    val notasWords: List<NotaWords> = emptyList(),
    val notasKanji: List<NotaKanji> = emptyList(),
)
```

Reescribir `escribir` como overload + general:

```kotlin
    /** Entrada de 4a (mazos fijos Words/Kanji): delega en la general. Los decks
     *  Words y Kanji van SIEMPRE en col.decks (aunque una lista venga vacía) —
     *  comportamiento observable idéntico a 4a, verificado por los tests previos. */
    fun escribir(destino: File, notasWords: List<NotaWords>, notasKanji: List<NotaKanji>) =
        escribir(
            destino,
            listOf(
                MazoNotas(ModeloNotas.DECK_ID_WORDS, ModeloNotas.NOMBRE_DECK_WORDS, notasWords = notasWords),
                MazoNotas(ModeloNotas.DECK_ID_KANJI, ModeloNotas.NOMBRE_DECK_KANJI, notasKanji = notasKanji),
            ),
        )

    fun escribir(destino: File, mazos: List<MazoNotas>) {
        require(mazos.isNotEmpty()) { "escribir: lista de mazos vacía" }
        val sqliteTemp = File.createTempFile("apkg_", ".sqlite", destino.parentFile)
        try {
            SQLiteDatabase.openOrCreateDatabase(sqliteTemp, null).use { db ->
                for (sentencia in DDL) db.execSQL(sentencia)

                val ahoraSegundos = System.currentTimeMillis() / 1000
                insertarCol(db, ahoraSegundos, mazos)

                var idGen = ahoraSegundos * 1000 // estilo genanki: contador base epoch-ms
                var due = 1L
                for (mazo in mazos) {
                    for (nota in mazo.notasWords) {
                        escribirNota(
                            db, idGen, idGen + 1, ModeloNotas.guidDe(nota.claveGuid),
                            ModeloNotas.MODEL_ID_WORDS, ahoraSegundos, mazo.deckId,
                            nota.campos(), due,
                        )
                        idGen += 2
                        due += 1
                    }
                    for (nota in mazo.notasKanji) {
                        escribirNota(
                            db, idGen, idGen + 1, ModeloNotas.guidDe(nota.claveGuid),
                            ModeloNotas.MODEL_ID_KANJI, ahoraSegundos, mazo.deckId,
                            nota.campos(), due,
                        )
                        idGen += 2
                        due += 1
                    }
                }
            }
            zipear(sqliteTemp, destino)
        } finally {
            sqliteTemp.delete()
        }
    }
```

`insertarCol`, `confJson` y `decksJson` pasan a recibir los mazos:

```kotlin
    private fun insertarCol(db: SQLiteDatabase, ahoraSegundos: Long, mazos: List<MazoNotas>) {
        db.execSQL(
            "INSERT INTO col(id, crt, mod, scm, ver, dty, usn, ls, conf, models, decks, dconf, tags)" +
                " VALUES (NULL, ?, ?, ?, 11, 0, 0, 0, ?, ?, ?, ?, '{}')",
            arrayOf<Any>(
                ahoraSegundos, ahoraSegundos, ahoraSegundos * 1000,
                confJson(mazos).toString(),
                modelsJson(ahoraSegundos).toString(),
                decksJson(ahoraSegundos, mazos).toString(),
                dconfJson().toString(),
            ),
        )
    }

    private fun confJson(mazos: List<MazoNotas>) = buildJsonObject {
        put("activeDecks", buildJsonArray { mazos.forEach { add(it.deckId) } })
        put("addToCur", true)
        put("collapseTime", 1200)
        put("curDeck", mazos.first().deckId)
        put("curModel", ModeloNotas.MODEL_ID_WORDS.toString())
        put("dueCounts", true)
        put("estTimes", true)
        put("newBury", true)
        put("newSpread", 0)
        put("nextPos", 1)
        put("sortBackwards", false)
        put("sortType", "noteFld")
        put("timeLim", 0)
    }

    private fun decksJson(modSegundos: Long, mazos: List<MazoNotas>) = buildJsonObject {
        // Deck Default obligatorio: invariante de Anki, siempre presente en genanki
        put("1", deckJson(1L, "Default", modSegundos))
        for (mazo in mazos) put(mazo.deckId.toString(), deckJson(mazo.deckId, mazo.nombre, modSegundos))
    }
```

(El `confJson(modSegundos)` viejo tenía el parámetro sin usar — la firma nueva lo elimina; ojo que la llamada vieja `confJson(ahoraSegundos)` desaparece con `insertarCol` nuevo. `modelsJson` y `dconfJson` no cambian: los dos modelos van siempre, son note types y no ensucian la lista de mazos del usuario.)

- [ ] **Step 4: Verificar GREEN + regresión + U+001F.**

```bash
cd app && ./gradlew test --tests '*EscritorApkgTest*'   # PASS, incluidos los tests de 4a sin tocar
cd app && ./gradlew test                                  # 132 tests (130 + 2), 0 failures
grep -cP '\x1f' app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/EscritorApkg.kt   # 0 (correr desde app/)
```

- [ ] **Step 5: Commit.**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/EscritorApkg.kt app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/EscritorApkgTest.kt
git commit -m "feat(app): EscritorApkg acepta N mazos — subdecks de historias en un archivo"
```

---

### Task 3: ArmadorMazos.armarHistorias()

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazos.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazosTest.kt` (agregar; existentes intactos)

**Interfaces:**
- Consumes: `NotaKanji(..., claveGuidPropia = "story:<id>:<kanji>")` (Task 1); `Historia` (`id`, `titulo`, `parrafos`), `Diccionario.buscarKanji`, `ProgresoDao.kanjisTaggeados()`, `oracionARubyHtml` — todos existentes.
- Produces (Task 4 depende de esto):

```kotlin
data class MazoHistoria(val idHistoria: String, val titulo: String, val notas: List<NotaKanji>)
data class ResultadoHistorias(val mazos: List<MazoHistoria>, val kanjisOmitidos: Int)

suspend fun armarHistorias(
    historias: List<Historia> = historiasRepo.historiasLocales(),
): ResultadoHistorias
```

Reglas:
- Por historia: kanjis únicos del texto de sus oraciones, **en orden de primera aparición** (LinkedHashSet recorriendo `parrafos → oraciones → texto` en orden). Detección: `c in '一'..'鿿'` (mismo rango que `BuscadorPalabras.esKanji` — duplicar el helper privado acá con un comentario que lo diga; es 1 línea, no vale acoplar los módulos).
- `dificultad` = tag del usuario si ese kanji está en `kanjisTaggeados()` (mapa kanji→dificultad leído UNA vez para todas las historias), sino `""` (el template ya omite el badge con campo vacío).
- Kanji sin entrada en `diccionario.buscarKanji` → se omite y suma a `kanjisOmitidos` (global, no por historia).
- Oraciones: SOLO de ESA historia (`texto.contains(kanji)`), cap 5, `oracionARubyHtml`, SIN relleno Tatoeba.
- Historia sin ningún kanji con entrada → mazo con lista de notas vacía IGUAL entra en la lista (el VM decide qué reportar; el escritor tolera mazos vacíos).

- [ ] **Step 1: Tests que fallan.** Agregar a `ArmadorMazosTest.kt` (usa el fixture real `momotaro.json` ya cargado por los tests existentes — 217 kanjis únicos reales, el primero en aparecer es 山 y el orden arranca 山→刈→川→洗→濯; 刈 aparece en exactamente 1 oración):

```kotlin
// --- armarHistorias (Plan 4a.1) ---

@Test
fun `armarHistorias arma un mazo por historia con kanjis en orden de primera aparicion`() = runTest {
    val dao = ProgresoDaoFake()
    val diccionario = DiccionarioFake().apply { todosLosKanjisConocidos = true }
    val resultado = armador(dao, diccionario).armarHistorias()
    val mazo = resultado.mazos.single()
    assertEquals("momotaro", mazo.idHistoria)
    assertEquals("桃太郎", mazo.titulo)
    assertEquals(217, mazo.notas.size)  // kanjis únicos reales del fixture (= kanjis_unicos del catálogo)
    assertEquals(listOf("山", "刈", "川", "洗", "濯"), mazo.notas.take(5).map { it.kanji })
    assertEquals(0, resultado.kanjisOmitidos)
}

@Test
fun `armarHistorias usa guid por historia y oraciones solo de esa historia sin Tatoeba`() = runTest {
    val dao = ProgresoDaoFake()
    val diccionario = DiccionarioFake().apply {
        todosLosKanjisConocidos = true
        // Tatoeba NUNCA debe consultarse para mazos de historias:
        ejemplosKanji["刈"] = listOf(OracionEjemplo("この芝を刈る。", "Mow this lawn."))
    }
    val mazo = armador(dao, diccionario).armarHistorias().mazos.single()
    val nota = mazo.notas.single { it.kanji == "刈" }
    assertEquals("story:momotaro:刈", nota.claveGuid)
    assertEquals(1, nota.oraciones.size)              // 刈 está en 1 sola oración del fixture
    assertTrue(nota.oraciones.single().contains("<ruby>"))
    assertFalse(nota.oraciones.single().contains("<br>"))  // sin relleno Tatoeba (formato jp<br>en)
}

@Test
fun `armarHistorias hereda el tag del usuario y deja vacia la dificultad sin tag`() = runTest {
    val dao = ProgresoDaoFake()
    dao.insertarKanjiSiNoExiste(KanjiTocado("洗", "hard", 1L))
    val diccionario = DiccionarioFake().apply { todosLosKanjisConocidos = true }
    val notas = armador(dao, diccionario).armarHistorias().mazos.single().notas
    assertEquals("hard", notas.single { it.kanji == "洗" }.dificultad)
    assertEquals("", notas.single { it.kanji == "山" }.dificultad)
}

@Test
fun `armarHistorias omite y cuenta kanjis fuera del diccionario`() = runTest {
    val dao = ProgresoDaoFake()
    val diccionario = DiccionarioFake()  // sin todosLosKanjisConocidos: solo conoce lo cargado a mano
    diccionario.kanjis["山"] = KanjiInfo("山", listOf("mountain"), listOf("サン"), listOf("やま"), null, null)
    val resultado = armador(dao, diccionario).armarHistorias()
    val mazo = resultado.mazos.single()
    assertEquals(listOf("山"), mazo.notas.map { it.kanji })
    assertEquals(216, resultado.kanjisOmitidos)  // 217 únicos - 1 conocido
}
```

**Sobre `DiccionarioFake`:** leer `app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/Fakes.kt` ANTES de escribir los tests. Si el fake no tiene `todosLosKanjisConocidos` (flag que hace que `buscarKanji` devuelva un `KanjiInfo` sintético — p.ej. `KanjiInfo(kanji, listOf("meaning-$kanji"), listOf("オン"), listOf("くん"), null, null)` — para cualquier kanji) ni un mapa `kanjis` mutable, agregárselos al fake respetando su estilo actual; son adiciones compatibles (default `false`/vacío) que no afectan tests existentes. Ajustar los nombres de estos tests a los campos REALES del fake si ya existen equivalentes.

- [ ] **Step 2: Verificar RED.** `cd app && ./gradlew test --tests '*ArmadorMazosTest*'` → FAIL compilación (`armarHistorias` no existe).

- [ ] **Step 3: Implementación.** En `ArmadorMazos.kt`:

Data classes junto a `ResultadoArmado`:

```kotlin
/** Un mazo de pre-lectura por historia (Plan 4a.1): todos los kanjis únicos de la
 *  historia en orden de primera aparición, oraciones SOLO de esa historia. */
data class MazoHistoria(val idHistoria: String, val titulo: String, val notas: List<NotaKanji>)

data class ResultadoHistorias(val mazos: List<MazoHistoria>, val kanjisOmitidos: Int)
```

Método en la clase:

```kotlin
    /** Mazos de pre-lectura, uno por historia local. El tag del usuario (si existe)
     *  viaja en Dificultad; kanji sin entrada en el diccionario se omite y se
     *  cuenta (mismo criterio "exported N, skipped M" que armarKanji). */
    suspend fun armarHistorias(
        historias: List<Historia> = historiasRepo.historiasLocales(),
    ): ResultadoHistorias {
        val tagPorKanji = progresoDao.kanjisTaggeados()
            .associate { it.kanji to requireNotNull(it.dificultad) }
        var omitidos = 0
        val mazos = historias.map { historia ->
            val notas = kanjisEnOrdenDeAparicion(historia).mapNotNull { kanji ->
                val info = diccionario.buscarKanji(kanji)
                if (info == null) {
                    omitidos++
                    null
                } else {
                    NotaKanji(
                        kanji = kanji,
                        onYomi = info.onYomi.joinToString("、"),
                        kunYomi = info.kunYomi.joinToString("、"),
                        significados = info.significados.joinToString("; "),
                        dificultad = tagPorKanji[kanji] ?: "",
                        oraciones = oracionesDeLaHistoria(historia, kanji),
                        claveGuidPropia = "story:${historia.id}:$kanji",
                    )
                }
            }
            MazoHistoria(historia.id, historia.titulo, notas)
        }
        return ResultadoHistorias(mazos, omitidos)
    }

    /** Kanjis únicos en orden de primera aparición — el mazo se estudia en el
     *  orden en que se van a encontrar leyendo. */
    private fun kanjisEnOrdenDeAparicion(historia: Historia): List<String> {
        val vistos = LinkedHashSet<Char>()
        for (parrafo in historia.parrafos)
            for (oracion in parrafo.oraciones)
                for (c in oracion.texto)
                    if (esKanji(c)) vistos.add(c)
        return vistos.map { it.toString() }
    }

    /** Oraciones de ESA historia solamente (spec 4a.1: sin relleno Tatoeba — el
     *  punto del mazo es prepararse para ese texto). */
    private fun oracionesDeLaHistoria(historia: Historia, kanji: String): List<String> =
        historia.parrafos.asSequence()
            .flatMap { it.oraciones.asSequence() }
            .filter { it.texto.contains(kanji) }
            .map { oracionARubyHtml(it) }
            .take(CAP_ORACIONES)
            .toList()

    // Mismo rango que BuscadorPalabras.esKanji (helper privado de 1 línea,
    // duplicado a propósito para no acoplar dominio/anki con dominio/).
    private fun esKanji(c: Char): Boolean = c in '一'..'鿿'
```

- [ ] **Step 4: Verificar GREEN.** `cd app && ./gradlew test --tests '*ArmadorMazosTest*'` → PASS (existentes + 4 nuevos). Suite completa: 136 tests (132 + 4), 0 failures.

- [ ] **Step 5: Commit.**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazos.kt app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazosTest.kt app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/Fakes.kt
git commit -m "feat(app): armarHistorias — kanjis por historia en orden de aparicion"
```

(Si `Fakes.kt` no se tocó, sacarlo del add.)

---

### Task 4: ExportViewModel + ExportScreen — botón Stories

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportViewModel.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportScreen.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/ui/export/ExportViewModelTest.kt` (agregar; existentes intactos)

**Interfaces:**
- Consumes: `ArmadorMazos.armarHistorias(): ResultadoHistorias` (Task 3), `EscritorApkg.escribir(destino, mazos: List<MazoNotas>)` (Task 2), `ModeloNotas.deckIdDeHistoria`/`nombreDeckHistoria` (Task 1).
- Produces:
  - `TipoExport` gana `STORIES`; archivo `dokusho-stories.apkg`.
  - `ContadoresExport(words: Int, kanjisTaggeados: Int, historias: Int)` — campo nuevo al final con default NO (los tests existentes construyen con 2 args posicionales? NO: revisar — si construyen posicional, agregar `historias: Int = 0` con default para no romperlos).
  - VM gana constructor param `escribirMazos: (File, List<MazoNotas>) -> Unit = EscritorApkg::escribir` (mismo patrón inyectable que `escribir`).
  - Resumen: `"Exported N stories (M kanji)"`; con omitidos: `"Exported N stories (M kanji, K skipped)"`.
  - Botón "Export Stories deck" con hint `"No local stories"` (deshabilitado si `historias == 0`).

- [ ] **Step 1: Tests que fallan.** Leer PRIMERO los tests existentes del VM (helper `vm(...)`, fakes, cómo cuentan estados). Agregar:

```kotlin
@Test
fun `contadores incluyen historias locales`() = runTest {
    val viewModel = vm()  // el helper ya monta HistoriasRepo con momotaro
    viewModel.cargar()
    advanceUntilIdle()
    assertEquals(1, viewModel.contadores.value.historias)
}

@Test
fun `exportar STORIES escribe un mazo por historia y resume counts`() = runTest {
    var mazosEscritos: List<MazoNotas>? = null
    val diccionario = DiccionarioFake().apply { todosLosKanjisConocidos = true }
    val viewModel = vm(
        diccionario = diccionario,
        escribirMazos = { _, mazos -> mazosEscritos = mazos },
    )
    viewModel.exportar(TipoExport.STORIES)
    advanceUntilIdle()
    val listo = viewModel.estado.value as EstadoExport.Listo
    assertEquals("dokusho-stories.apkg", listo.archivo.name)
    assertEquals("Exported 1 stories (217 kanji)", listo.resumen)
    val mazo = mazosEscritos!!.single()
    assertEquals(ModeloNotas.deckIdDeHistoria("momotaro"), mazo.deckId)
    assertEquals("Dokusho — Stories::桃太郎", mazo.nombre)
    assertEquals(217, mazo.notasKanji.size)
    assertTrue(mazo.notasWords.isEmpty())
}

@Test
fun `exportar STORIES reporta kanjis omitidos en el resumen`() = runTest {
    val diccionario = DiccionarioFake()  // solo conoce lo cargado a mano
    diccionario.kanjis["山"] = KanjiInfo("山", listOf("mountain"), listOf("サン"), listOf("やま"), null, null)
    val viewModel = vm(diccionario = diccionario, escribirMazos = { _, _ -> })
    viewModel.exportar(TipoExport.STORIES)
    advanceUntilIdle()
    val listo = viewModel.estado.value as EstadoExport.Listo
    assertEquals("Exported 1 stories (1 kanji, 216 skipped)", listo.resumen)
}
```

(Extender el helper `vm(...)` con el param `escribirMazos: (File, List<MazoNotas>) -> Unit = { _, _ -> }` igual que hace con `escribir`. Ajustar la construcción de `ContadoresExport` en asserts existentes SOLO si es inevitable — preferir default `historias: Int = 0`.)

- [ ] **Step 2: Verificar RED.** `cd app && ./gradlew test --tests '*ExportViewModelTest*'` → FAIL compilación.

- [ ] **Step 3: Implementación.**

`ExportViewModel.kt`:

```kotlin
enum class TipoExport { WORDS, KANJI, STORIES }

data class ContadoresExport(val words: Int, val kanjisTaggeados: Int, val historias: Int = 0)
```

Constructor: agregar tras `escribir`:

```kotlin
    private val escribirMazos: (File, List<MazoNotas>) -> Unit = EscritorApkg::escribir,
```

`cargar()`: dentro del `withContext`, contar historias vía el armador — para no darle al VM una dependencia nueva, exponer en `ArmadorMazos`:

```kotlin
    /** Para los counts de la pantalla de Export — evita que el VM dependa de
     *  HistoriasRepo solo para contar. */
    fun contarHistoriasLocales(): Int = historiasRepo.historiasLocales().size
```

y en el VM:

```kotlin
                val words = progresoDao.todasPalabras().map { it.termino }.distinct().size
                val kanjis = progresoDao.kanjisTaggeados().size
                val historias = armadorMazos.contarHistoriasLocales()
                ContadoresExport(words, kanjis, historias)
```

`exportar()`: el `when (tipo)` gana la rama (mismo bloque `withContext`, mismo manejo de errores — no tocar el try/catch):

```kotlin
                        TipoExport.STORIES -> {
                            val resultado = armadorMazos.armarHistorias()
                            val mazos = resultado.mazos.map { mazo ->
                                MazoNotas(
                                    deckId = ModeloNotas.deckIdDeHistoria(mazo.idHistoria),
                                    nombre = ModeloNotas.nombreDeckHistoria(mazo.titulo),
                                    notasKanji = mazo.notas,
                                )
                            }
                            escribirMazos(destino, mazos)
                            val totalKanji = resultado.mazos.sumOf { it.notas.size }
                            val base = "${resultado.mazos.size} stories ($totalKanji kanji"
                            if (resultado.kanjisOmitidos > 0) "$base, ${resultado.kanjisOmitidos} skipped)" else "$base)"
                        }
```

`nombreArchivo`: agregar `TipoExport.STORIES -> "dokusho-stories.apkg"`.

Imports nuevos del VM: `MazoNotas`, `ModeloNotas`.

`ExportScreen.kt`: tras el botón Kanji:

```kotlin
            Spacer(Modifier.height(12.dp))
            BotonExport(
                titulo = "Export Stories deck",
                habilitado = contadores.historias > 0,
                hint = "No local stories",
                generando = estado is EstadoExport.Generando,
                onClick = { vm.exportar(TipoExport.STORIES) },
            )
```

y el texto de counts pasa a:

```kotlin
            Text(
                "${contadores.words} words · ${contadores.kanjisTaggeados} tagged kanji · ${contadores.historias} stories",
                style = MaterialTheme.typography.bodyMedium,
            )
```

- [ ] **Step 4: Verificar GREEN.** `cd app && ./gradlew test --tests '*ExportViewModelTest*'` → PASS. Suite completa: 139 tests (136 + 3), 0 failures. `cd app && ./gradlew assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportViewModel.kt app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportScreen.kt app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazos.kt app/app/src/test/kotlin/com/tatoh/dokushorenshu/ui/export/ExportViewModelTest.kt
git commit -m "feat(app): boton Export Stories deck — un subdeck por historia"
```

---

### Task 5: Gate en dispositivo + ESTADO + PR (coordina el controller)

**Files:**
- Modify: `docs/ESTADO.md`

**Interfaces:**
- Consumes: todo lo anterior; AnkiDroid instalado en tablet/POCO.
- Produces: plan 4a.1 validado en dispositivo real, ESTADO al día, PR abierto.

- [ ] **Step 1 (device, coordina el controller):** `cd app && ./gradlew installDebug` en el dispositivo conectado. Checklist:
  1. Export screen muestra "N words · M tagged kanji · 4 stories".
  2. Export Stories deck → "Exported 4 stories (~800 kanji)" (número real; puede traer "K skipped" si el db no cubre todo).
  3. Share → AnkiDroid → import OK; la lista de mazos muestra "Dokusho — Stories" colapsable con 4 subdecks con título japonés; SIN decks Words/Kanji vacíos nuevos.
  4. Estudiar el subdeck de momotaro: la primera carta es 山 (orden de aparición); dorso con lecturas/significados y oración de LA MISMA historia con ruby; kanji taggeado (洗 si sigue el tag de la sesión anterior) muestra el badge.
  5. Re-export + re-import → "updated existing", sin duplicados.
  6. Re-export del mazo Words de 4a → sigue actualizando el suyo (GUIDs disjuntos, regresión).
- [ ] **Step 2:** Si algo falla: fix con TDD y re-validar. Si pasa: actualizar `docs/ESTADO.md` — fila nueva `4a.1 | mazos por historia (subdecks) | ✅ Completo (PR pendiente — actualizar con #N al abrir)` y una línea en "Datos operativos" dentro del bullet **Anki (4a)** mencionando subdecks por historia + GUID `story:<id>:<kanji>`. Commit `docs(ESTADO): plan 4a.1 completo`.
- [ ] **Step 3:** Review final de branch (proceso de siempre) → push + `gh pr create --title "Plan 4a.1: mazos Anki por historia (subdecks de pre-lectura)"`.

---

## Self-Review (hecho)

- **Spec coverage**: empaquetado subdecks (T2+T4), oraciones solo-historia (T3), GUID por historia (T1+T3), orden de aparición (T3), dificultad heredada (T3), skipped (T3+T4), deck IDs determinísticos (T1), UX tercer botón + resumen (T4), errores mismos patrones (T4 no toca try/catch), gate device (T5). Sin gaps.
- **Placeholders**: ninguno — todo step con código completo o comando exacto.
- **Consistencia de tipos**: `MazoNotas(deckId, nombre, notasWords, notasKanji)` idéntico en T2/T4; `ResultadoHistorias(mazos, kanjisOmitidos)` idéntico en T3/T4; `claveGuidPropia` idéntico en T1/T2/T3; counts de suite encadenados 127→130→132→136→139.
