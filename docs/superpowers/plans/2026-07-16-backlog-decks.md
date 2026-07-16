# Mejoras de tarjetas Anki (PR C) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cerrar los 5 items de decks del backlog "feedback de uso": kanji/término objetivo resaltado en la oración de la tarjeta, traducción en la tarjeta (historias vía `Oracion.traduccion` del PR B + Tatoeba con estilo), lecturas kun (hiragana) primero en dos líneas etiquetadas, y mejores separadores entre lecturas.

**Architecture:** El parser de historias empieza a leer el campo `traduccion` (emitido por el catálogo desde PR B; null en importadas). `oracionARubyHtml` gana un param `objetivo` y resalta por RANGOS sobre el texto original (funciona aunque el objetivo cruce límites de spans de ruby). Los templates Kanji (que Stories reutiliza) pasan a dos líneas etiquetadas kun/on. Todo en `dominio/anki/` + `datos/ModelosHistoria.kt`.

**Tech Stack:** Kotlin, JUnit4 JVM-puro (los tests de anki/ no tocan Android).

**Spec:** `docs/superpowers/specs/2026-07-16-backlog-feedback-uso-design.md` (sección PR C)

## Global Constraints

- Branch: `feature/decks-mejoras` desde `main`. Solo tocar `app/` (dominio/anki/ + datos/ModelosHistoria.kt + sus tests).
- U+001F jamás literal en fuentes (`grep -rcP '\x1f' app/app/src/main/kotlin | grep -v ':0'` vacío).
- GUIDs y MODEL/DECK IDs NO cambian (re-export debe actualizar sin duplicar).
- UI copy/etiquetas de tarjeta en inglés minúsculas ("kun"/"on"); comentarios en español calcados del archivo.
- Tests: `cd app && ./gradlew :app:testDebugUnitTest --tests '*anki*'` (foco) y `./gradlew test` (full antes del PR).
- El campo Anki `OnYomi`/`KunYomi` sigue existiendo en el mismo orden (`CAMPOS_KANJI` intacto — solo cambia el TEMPLATE, no el schema del modelo).

---

### Task 1: `Oracion.traduccion` — parser y serializador

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/ModelosHistoria.kt` (`Oracion` línea 20, `parsearOracion` líneas 89-104, `SerializadorHistoria` línea 140 y su doc línea 117-120)
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/` — archivo de tests del parser existente (buscar `ParserHistoria` en los tests de `datos/`; agregar ahí)

**Interfaces:**
- Produces: `Oracion(texto, furigana, traduccion: String? = null)` — default null: TODOS los constructores existentes siguen compilando. Parser lee `"traduccion"` (string no vacío → valor; null/ausente → null); serializador emite el valor o `JsonNull` (round-trip exacto).

- [ ] **Step 1: Tests que fallan** (en el test del parser existente, mismo estilo):

```kotlin
@Test
fun `parsea traduccion cuando viene como string`() {
    val json = """{"id":"t","titulo":"T","autor":"A","fuente":"F","licencia":"L",
        "dificultad":"facil","version":2,"parrafos":[{"oraciones":[
        {"texto":"桃太郎は強い。","furigana":[[0,3,"ももたろう"]],"traduccion":"Momotaro is strong."}]}]}"""
    val historia = ParserHistoria.parsear(json)
    assertEquals("Momotaro is strong.", historia.parrafos[0].oraciones[0].traduccion)
}

@Test
fun `traduccion null o ausente parsea como null`() {
    val conNull = """{"id":"t","titulo":"T","autor":"A","fuente":"F","licencia":"L",
        "dificultad":"facil","version":2,"parrafos":[{"oraciones":[
        {"texto":"a","furigana":[],"traduccion":null},{"texto":"b","furigana":[]}]}]}"""
    val oraciones = ParserHistoria.parsear(conNull).parrafos[0].oraciones
    assertNull(oraciones[0].traduccion)
    assertNull(oraciones[1].traduccion)
}

@Test
fun `round-trip serializar-parsear conserva traduccion`() {
    val historia = Historia(
        id = "t", titulo = "T", autor = "A", fuente = "F", licencia = "L",
        dificultad = "facil", version = 2,
        parrafos = listOf(Parrafo(listOf(
            Oracion("桃太郎は強い。", listOf(Furigana(0, 3, "ももたろう")), "Momotaro is strong."),
            Oracion("川へ行った。", emptyList()),  // sin traducción → JsonNull
        ))),
    )
    assertEquals(historia, ParserHistoria.parsear(SerializadorHistoria.serializar(historia)))
}
```

- [ ] **Step 2: RED** — `cd app && ./gradlew :app:testDebugUnitTest --tests '*ParserHistoria*'` (ajustar al nombre real de la clase de test)

- [ ] **Step 3: Implementar.**

`Oracion` (ModelosHistoria.kt:20):
```kotlin
data class Oracion(val texto: String, val furigana: List<Furigana>, val traduccion: String? = null)
```

En `parsearOracion` (línea ~103), antes del `return`:
```kotlin
// traduccion (PR B, backlog feedback de uso): string no vacío o null/ausente.
// Catálogos viejos no traen el campo; importadas lo emiten null.
val traduccion = obj["traduccion"]
    ?.takeIf { it !is JsonNull }
    ?.jsonPrimitive?.content
    ?.takeIf { it.isNotEmpty() }
return Oracion(textoOracion, furigana, traduccion)
```

En `SerializadorHistoria` (línea 140), reemplazar `put("traduccion", JsonNull)`:
```kotlin
oracion.traduccion?.let { put("traduccion", it) } ?: put("traduccion", JsonNull)
```
y actualizar la doc del objeto (líneas 117-120): "`traduccion` siempre null" → "`traduccion` = valor de la oración o null (importadas: null — Kuromoji no traduce)". Fix del comment stale señalado en el review final del PR B.

- [ ] **Step 4: GREEN** (clase del parser + full `--tests '*datos*'`), **Step 5: Commit** — `feat(app): ParserHistoria lee y serializa traduccion por oración`

---

### Task 2: `oracionARubyHtml` con objetivo resaltado por rangos

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazos.kt` (función `oracionARubyHtml` líneas 210-226 + helper nuevo)
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/OracionARubyHtmlTest.kt`

**Interfaces:**
- Produces: `oracionARubyHtml(oracion: Oracion, objetivo: String? = null)` — ocurrencias del objetivo envueltas en `<b class="objetivo">`, por rangos sobre el texto original: si el objetivo cruza un límite de span de ruby, cada fragmento se resalta por separado. `rt` (la lectura) nunca se resalta. Default null: callers existentes intactos.

- [ ] **Step 1: Tests que fallan** (agregar a `OracionARubyHtmlTest.kt`):

```kotlin
@Test
fun `objetivo en texto plano queda envuelto en b objetivo`() {
    val oracion = Oracion("川へ行った。", emptyList())
    assertEquals(
        """<b class="objetivo">川</b>へ行った。""",
        oracionARubyHtml(oracion, objetivo = "川"),
    )
}

@Test
fun `objetivo dentro de un span de ruby se resalta en la base sin tocar rt`() {
    val oracion = Oracion("桃太郎", listOf(Furigana(0, 3, "ももたろう")))
    assertEquals(
        """<ruby><b class="objetivo">桃</b>太郎<rt>ももたろう</rt></ruby>""",
        oracionARubyHtml(oracion, objetivo = "桃"),
    )
}

@Test
fun `objetivo multichar que cruza el limite de un span se resalta por fragmentos`() {
    // ruby solo sobre 大 [0,1): el objetivo 大人 cruza el límite del span
    val oracion = Oracion("大人だ。", listOf(Furigana(0, 1, "おと")))
    assertEquals(
        """<ruby><b class="objetivo">大</b><rt>おと</rt></ruby><b class="objetivo">人</b>だ。""",
        oracionARubyHtml(oracion, objetivo = "大人"),
    )
}

@Test
fun `ocurrencias multiples se resaltan todas`() {
    val oracion = Oracion("山と山。", emptyList())
    assertEquals(
        """<b class="objetivo">山</b>と<b class="objetivo">山</b>。""",
        oracionARubyHtml(oracion, objetivo = "山"),
    )
}

@Test
fun `objetivo null u ausente no cambia la salida existente`() {
    val oracion = Oracion("桃太郎", listOf(Furigana(0, 3, "ももたろう")))
    assertEquals(oracionARubyHtml(oracion), oracionARubyHtml(oracion, objetivo = null))
}

@Test
fun `objetivo con caracteres html se escapa dentro del resalte`() {
    val oracion = Oracion("a<b", emptyList())
    assertEquals(
        """a<b class="objetivo">&lt;</b>b""",
        oracionARubyHtml(oracion, objetivo = "<"),
    )
}
```

- [ ] **Step 2: RED**, **Step 3: Implementar** (reemplaza `oracionARubyHtml`):

```kotlin
/** Convierte una oración con spans de furigana fin-exclusivo a HTML con
 *  `<ruby>` (formato que Anki/AnkiDroid renderiza en cualquier cliente, a
 *  diferencia del filtro `{{furigana:}}` que depende del parsing de
 *  corchetes). Pura, sin dependencias de Android — testeable en JVM plano.
 *
 *  [objetivo] (backlog feedback de uso 2026-07-13): ocurrencias del
 *  término/kanji objetivo envueltas en `<b class="objetivo">`. El resalte se
 *  calcula por RANGOS sobre el texto original, así un objetivo que cruza el
 *  límite de un span de ruby se resalta por fragmentos (la lectura `rt`
 *  nunca se resalta). */
internal fun oracionARubyHtml(oracion: Oracion, objetivo: String? = null): String {
    val texto = oracion.texto
    val resaltes = rangosDeObjetivo(texto, objetivo)
    fun tramo(desde: Int, hasta: Int) = emitirConResalte(texto, desde, hasta, resaltes)
    val sb = StringBuilder()
    var cursor = 0
    for (f in oracion.furigana.sortedBy { it.inicio }) {
        // defensivo: spans solapados son un bug de datos conocido (ledger Plan
        // 3.6 — momotaro.json llegó a traer furigana solapada); se ignora el
        // segundo span en vez de lanzar con un rango de substring inválido.
        if (f.inicio < cursor) continue
        if (f.inicio > cursor) sb.append(tramo(cursor, f.inicio))
        sb.append("<ruby>").append(tramo(f.inicio, f.fin))
            .append("<rt>").append(escapeHtml(f.lectura)).append("</rt></ruby>")
        cursor = f.fin
    }
    if (cursor < texto.length) sb.append(tramo(cursor, texto.length))
    return sb.toString()
}

/** Rangos [inicio, fin) de cada ocurrencia (no solapada) del objetivo. */
private fun rangosDeObjetivo(texto: String, objetivo: String?): List<IntRange> {
    if (objetivo.isNullOrEmpty()) return emptyList()
    val rangos = mutableListOf<IntRange>()
    var i = texto.indexOf(objetivo)
    while (i >= 0) {
        rangos.add(i until i + objetivo.length)
        i = texto.indexOf(objetivo, i + objetivo.length)
    }
    return rangos
}

/** Emite texto[desde, hasta) escapado, envolviendo en `<b class="objetivo">`
 *  las intersecciones con [resaltes]. */
private fun emitirConResalte(texto: String, desde: Int, hasta: Int, resaltes: List<IntRange>): String {
    if (resaltes.isEmpty()) return escapeHtml(texto.substring(desde, hasta))
    val sb = StringBuilder()
    var cursor = desde
    for (r in resaltes) {
        val ini = maxOf(r.first, cursor)
        val fin = minOf(r.last + 1, hasta)
        if (fin <= ini) continue
        if (ini > cursor) sb.append(escapeHtml(texto.substring(cursor, ini)))
        sb.append("""<b class="objetivo">""").append(escapeHtml(texto.substring(ini, fin))).append("</b>")
        cursor = fin
    }
    if (cursor < hasta) sb.append(escapeHtml(texto.substring(cursor, hasta)))
    return sb.toString()
}
```

- [ ] **Step 4: GREEN** (`--tests '*OracionARubyHtml*'` — los tests viejos siguen pasando por el default null), **Step 5: Commit** — `feat(app): resaltado del kanji-término objetivo en oracionARubyHtml`

---

### Task 3: `ArmadorMazos` — traducción en tarjeta, objetivo, separador ・

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazos.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazosTest.kt`

**Interfaces:**
- Consumes: `Oracion.traduccion` (Task 1), `oracionARubyHtml(oracion, objetivo)` (Task 2)
- Produces: oración de historia en tarjeta = `rubyHtml` + (si hay traducción) `<span class="traduccion">…</span>`; Tatoeba = `japonés-con-objetivo` + `<span class="traduccion">inglés</span>` (el `<br>` viejo desaparece: `.traduccion` ya es `display:block` en el CSS). Lecturas unidas con `" ・ "`.

- [ ] **Step 1: Tests que fallan** (en `ArmadorMazosTest.kt`, calcar fakes existentes del archivo):

```kotlin
@Test
fun `oracion de historia lleva objetivo resaltado y traduccion en span`() = runTest {
    // adaptar al builder de historias del test: una historia con una oración
    // con traduccion no-null que contenga el kanji taggeado
    // (p.ej. Oracion("川へ行った。", emptyList(), "To the river (he) went."))
    val resultado = armador.armarKanji(historias)  // kanji taggeado: 川
    val oracion = resultado.first.single { it.kanji == "川" }.oraciones.first()
    assertTrue(oracion.contains("""<b class="objetivo">川</b>"""))
    assertTrue(oracion.contains("""<span class="traduccion">To the river (he) went.</span>"""))
}

@Test
fun `oracion de historia sin traduccion no emite span vacio`() = runTest {
    // oración con traduccion null → el HTML no contiene "traduccion"
    val oracion = /* nota armada sobre historia sin traducciones */
    assertFalse(oracion.contains("traduccion"))
}

@Test
fun `relleno tatoeba lleva objetivo resaltado y traduccion en span sin br`() = runTest {
    // diccionario fake devuelve OracionEjemplo("川で泳ぐ。", "Swim in the river.")
    val oracion = /* nota Words de término 川 sin oraciones de historia */
    assertEquals(
        """<b class="objetivo">川</b>で泳ぐ。<span class="traduccion">Swim in the river.</span>""",
        oracion,
    )
}

@Test
fun `lecturas se unen con punto medio espaciado`() = runTest {
    // KanjiInfo con onYomi=[スイ], kunYomi=[みず, みず-]
    val nota = /* armarKanji sobre kanji taggeado */
    assertEquals("みず ・ みず-", nota.kunYomi)
    assertEquals("スイ", nota.onYomi)
}
```

(El implementer adapta los `/* … */` a los fakes/builders reales del archivo — los tests existentes de `armarKanji`/`armarWords`/`armarHistorias` muestran el patrón; los 4 tests deben quedar ejecutables y con las aserciones exactas de arriba.)

- [ ] **Step 2: RED**, **Step 3: Implementar:**

1. Helper nuevo junto a `oracionARubyHtml`:
```kotlin
/** Oración de historia lista para el campo de la tarjeta: ruby + objetivo
 *  resaltado + traducción (si la historia la trae — PR B) en un span con la
 *  clase `.traduccion` ya definida en el CSS del template. */
internal fun oracionDeTarjeta(oracion: Oracion, objetivo: String): String {
    val ruby = oracionARubyHtml(oracion, objetivo)
    val traduccion = oracion.traduccion
        ?.let { """<span class="traduccion">${escapeHtml(it)}</span>""" } ?: ""
    return ruby + traduccion
}
```
2. `oracionesDeLaHistoria` (líneas 140-146): `.map { oracionARubyHtml(it) }` → `.map { oracionDeTarjeta(it, kanji) }`.
3. `armarOraciones` (líneas 187-203): `.map { oracionARubyHtml(it) }` → `.map { oracionDeTarjeta(it, termino) }`; relleno Tatoeba →
```kotlin
val relleno = tatoeba(CAP_ORACIONES - deHistorias.size).map {
    val japones = oracionARubyHtml(Oracion(it.japones, emptyList()), objetivo = termino)
    """$japones<span class="traduccion">${escapeHtml(it.ingles)}</span>"""
}
```
(reutiliza el resaltado por rangos; una `Oracion` sin furigana emite texto plano escapado). Actualizar el doc de `armarOraciones` (líneas 184-186): las historias AHORA pueden llevar traducción (PR B).
4. Separadores: los 4 `joinToString("、")` de lecturas (líneas 113-114 y 173-174) → `joinToString(" ・ ")`.
5. `escapeHtml` pasa de `private` a `internal` si `oracionDeTarjeta` lo necesita desde otro scope (mismo archivo: queda `private`).

- [ ] **Step 4: GREEN** (`--tests '*ArmadorMazos*'` + `'*anki*'` completo — los tests existentes que asserten `"、"` o `<br>` de Tatoeba se ACTUALIZAN al formato nuevo, documentando en el commit), **Step 5: Commit** — `feat(app): tarjetas con objetivo resaltado, traduccion y separador de lecturas ・`

---

### Task 4: Template Kanji — dos líneas etiquetadas, kun primero + CSS

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ModeloNotas.kt` (`AFMT_KANJI` línea 256-264, CSS líneas 139-211)
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/ModeloNotasTest.kt`

**Interfaces:**
- Produces: `AFMT_KANJI` con kun (hiragana) arriba y on (katakana) abajo, cada línea con etiqueta chica ("kun"/"on") y condicional Anki (`{{#KunYomi}}`) para no mostrar línea vacía. `CAMPOS_KANJI` y los MODEL/DECK IDs intactos.

- [ ] **Step 1: Tests que fallan** (en `ModeloNotasTest.kt`):

```kotlin
@Test
fun `afmt kanji muestra kun antes que on, en lineas etiquetadas y condicionales`() {
    val afmt = ModeloNotas.AFMT_KANJI
    assertTrue(afmt.indexOf("{{KunYomi}}") < afmt.indexOf("{{OnYomi}}"))
    assertTrue(afmt.contains("{{#KunYomi}}"))
    assertTrue(afmt.contains("{{#OnYomi}}"))
    assertTrue(afmt.contains("""<span class="etiqueta-lectura">kun</span>"""))
    assertTrue(afmt.contains("""<span class="etiqueta-lectura">on</span>"""))
}

@Test
fun `css define objetivo y etiqueta-lectura con overrides de modo claro`() {
    assertTrue(ModeloNotas.CSS.contains(".objetivo"))
    assertTrue(ModeloNotas.CSS.contains(".etiqueta-lectura"))
    assertTrue(ModeloNotas.CSS.contains(".card:not(.night_mode) #oracion .objetivo"))
}
```

- [ ] **Step 2: RED**, **Step 3: Implementar.**

`AFMT_KANJI` — reemplazar la línea `<div class="lecturas">…</div>` (línea 259) por:
```html
<div class="lecturas">
    {{#KunYomi}}<div class="linea-lectura"><span class="etiqueta-lectura">kun</span><span class="kun">{{KunYomi}}</span></div>{{/KunYomi}}
    {{#OnYomi}}<div class="linea-lectura"><span class="etiqueta-lectura">on</span><span class="on">{{OnYomi}}</span></div>{{/OnYomi}}
</div>
```
(con comentario en español encima: kun/hiragana primero — feedback de uso 2026-07-13: es la lectura de uso más común, también en doblajes).

CSS — agregar dentro del bloque oscuro (después de `#oracion .traduccion`, línea ~190):
```css
#oracion .objetivo {
    color: #ffb74d;
    font-weight: bold;
}
.linea-lectura {
    margin: 2px 0;
}
.etiqueta-lectura {
    display: inline-block;
    font-size: 12px;
    color: #888888;
    min-width: 34px;
    text-align: right;
    margin-right: 8px;
}
```
y el override claro (junto a los otros `:not(.night_mode)`, línea ~208):
```css
.card:not(.night_mode) #oracion .objetivo {
    color: #e65100;
}
.card:not(.night_mode) .etiqueta-lectura {
    color: #999999;
}
```

- [ ] **Step 4: GREEN** (`--tests '*ModeloNotas*'` — los asserts viejos de `{{OnYomi}}`/`{{KunYomi}}` siguen pasando), **Step 5: Commit** — `feat(app): template kanji con kun primero en lineas etiquetadas y estilo del objetivo`

---

### Task 5: Verificación end-to-end

**Files:** ninguno.

- [ ] **Step 1:** `cd app && ./gradlew test` — full suite verde; `grep -rcP '\x1f' app/app/src/main/kotlin | grep -v ':0' || echo OK` → OK.
- [ ] **Step 2:** `cd app && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk` (nota: el build regen assets con `copiarHistorias` — si PR B ya está mergeado en main y la branch se rebasa, los assets traen traducciones y el smoke las muestra; si no, las tarjetas de historias salen sin span de traducción y solo Tatoeba trae inglés — ambos estados son válidos).
- [ ] **Step 3 (smoke manual, usuario):** exportar los 3 mazos → importar en AnkiDroid → verificar: kun arriba/on abajo con etiquetas; lecturas separadas por `・`; kanji objetivo resaltado en color en la oración; traducción gris debajo de la oración (historias si los assets ya la traen, Tatoeba siempre); re-import actualiza sin duplicar.

---

### Task 6: Review final + PR + ESTADO

- [ ] Review final de branch, PR contra `main` (referenciar spec, PR #13 y #14). ESTADO.md: fila C, tachar los 5 items de decks del backlog feedback de uso, nota en Datos operativos (Anki 4a) del formato nuevo de tarjeta.

## Verificación global

1. Suite completa verde; U+001F OK; asserts de template/CSS nuevos.
2. Smoke AnkiDroid (Task 5 Step 3).
3. Post-merge de A+B+C: apps ven Update (B), actualizan historias, re-export muestra traducciones de historias en tarjetas.
