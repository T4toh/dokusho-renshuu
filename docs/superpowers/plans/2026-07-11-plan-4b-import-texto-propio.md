# Plan 4b — Import de texto propio: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** El usuario pega texto japonés o abre un `.txt` y la app lo guarda como historia local (JSON schema v2 con furigana Kuromoji persistida), borrable, que entra al lector, la biblioteca y el export Anki; además, checkboxes para elegir qué historias van al mazo Stories.

**Architecture:** Pipeline de import puro en `dominio/` (segmentador portado de Python + generador de furigana sobre el `Tokenizador` existente), serializador inverso de `ParserHistoria` en `datos/`, tercer origen `filesDir/importadas/` en `HistoriasRepo`, y UI nueva `ui/importar/` + retoques en biblioteca y export. Spec: `docs/superpowers/specs/2026-07-11-import-texto-propio-design.md`.

**Tech Stack:** Kotlin + Compose, Kuromoji IPADIC (ya en el árbol), kotlinx.serialization JsonElement (ya en el árbol), JUnit4 + kotlinx-coroutines-test.

## Global Constraints

- Trabajar en branch `feature/plan-4b-import-texto` desde `main`.
- UI copy en **inglés**; código/comentarios en español (convención del repo).
- Dificultad en el schema JSON: `facil|media|dificil`. En UI: `easy|medium|hard`. Mapeo explícito, nunca mezclar.
- Contrato furigana: `[inicio, fin, lectura]`, **fin exclusivo**, índices sobre el texto de la oración, spans disjuntos.
- U+001F solo como escape Kotlin: `grep -rcP '\x1f' app/app/src/main/kotlin` debe dar 0 por archivo (regla 4a).
- Todos los comandos gradle se corren desde `app/` con JDK 17+ (esta máquina: JDK 21, SDK 36).
- Escrituras a disco siempre atómicas tmp→rename (patrón `descargarHistoria`).
- Commits frecuentes, mensajes en español estilo repo (`feat(app): …`, `test(app): …`).

---

### Task 0: Branch

**Files:** ninguno.

- [ ] **Step 1: Crear branch**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu
git checkout -b feature/plan-4b-import-texto
```

---

### Task 1: SegmentadorTexto (port del segmentador Python)

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/SegmentadorTexto.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/SegmentadorTextoTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `SegmentadorTexto.segmentar(texto: String): List<Pair<Int, Int>>` — spans `(inicio, fin)` con fin **exclusivo**, en orden. Lo consume Task 5 (`ImportadorHistoria`).

Referencia canónica: `historias/src/segmentador.py` líneas 25-58 (`_es_residuo` + `segmentar` + regla de fusión). NO portar `es_encabezado_seccion` ni `segmentar_parrafo` (específicos de Aozora).

- [ ] **Step 1: Test que falla**

```kotlin
package com.tatoh.dokushorenshu.dominio

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentadorTextoTest {
    private fun oraciones(texto: String): List<String> =
        SegmentadorTexto.segmentar(texto).map { (ini, fin) -> texto.substring(ini, fin) }

    @Test
    fun `corta en punto japones`() {
        assertEquals(listOf("犬が走る。", "猫が寝る。"), oraciones("犬が走る。猫が寝る。"))
    }

    @Test
    fun `corta en exclamacion e interrogacion`() {
        assertEquals(listOf("走れ！", "なぜ？"), oraciones("走れ！なぜ？"))
    }

    @Test
    fun `dialogo con puntos internos queda como una oracion`() {
        // contrato del Plan 2: 「…。…。」 = 1 oración
        assertEquals(listOf("「おはよう。元気？」と言った。"), oraciones("「おはよう。元気？」と言った。"))
    }

    @Test
    fun `parentesis y comillas dobles tambien anidan`() {
        assertEquals(listOf("彼（先生。偉い人。）が来た。"), oraciones("彼（先生。偉い人。）が来た。"))
        assertEquals(listOf("『本。』を読む。"), oraciones("『本。』を読む。"))
    }

    @Test
    fun `cierre residual se fusiona con la oracion anterior`() {
        // 」 residual tras el corte: span de solo puntuación → fusionar
        // (profundidad ya en 0 cuando aparece el 。 dentro… caso sintético del test Python)
        assertEquals(listOf("行く。」"), oraciones("行く。」"))
    }

    @Test
    fun `interrogacion suelta tras exclamacion se fusiona`() {
        assertEquals(listOf("待て！？"), oraciones("待て！？"))
    }

    @Test
    fun `resto sin puntuacion final es una oracion`() {
        assertEquals(listOf("終わりのない文"), oraciones("終わりのない文"))
    }

    @Test
    fun `texto vacio o solo espacios no produce spans`() {
        assertEquals(emptyList<String>(), oraciones(""))
        assertEquals(emptyList<String>(), oraciones("   　"))
    }

    @Test
    fun `cierre sin apertura no rompe la profundidad`() {
        // max(0, profundidad-1): un 」 huérfano no deja profundidad negativa
        assertEquals(listOf("」変だ。", "次。"), oraciones("」変だ。次。"))
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/app
./gradlew :app:testDebugUnitTest --tests '*SegmentadorTextoTest*'
```
Esperado: FAIL de compilación — `SegmentadorTexto` no existe.

- [ ] **Step 3: Implementación**

```kotlin
package com.tatoh.dokushorenshu.dominio

/** Port de `historias/src/segmentador.py` (deuda del Plan 3, pagada en 4b).
 *  Spans [inicio, fin) sobre `texto`. Corta en 。！？ solo fuera de
 *  comillas/paréntesis: el diálogo 「…。…。」 queda como una sola oración.
 *  Un span "residuo" (solo puntuación/espacios) se fusiona con su vecino
 *  — cubre el 」 residual de diálogo multi-párrafo y el ？ suelto tras ！. */
object SegmentadorTexto {
    private const val FIN_ORACION = "。！？"
    private const val APERTURA = "「『（"
    private const val CIERRE = "」』）"
    private const val PUNTUACION = FIN_ORACION + APERTURA + CIERRE

    fun segmentar(texto: String): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        var inicio = 0
        var profundidad = 0
        for ((i, c) in texto.withIndex()) {
            when {
                c in APERTURA -> profundidad++
                c in CIERRE -> profundidad = maxOf(0, profundidad - 1)
                c in FIN_ORACION && profundidad == 0 -> {
                    spans.add(inicio to i + 1)
                    inicio = i + 1
                }
            }
        }
        if (texto.substring(inicio).isNotBlank()) spans.add(inicio to texto.length)
        val fusionados = mutableListOf<Pair<Int, Int>>()
        for (span in spans) {
            val ultimo = fusionados.lastOrNull()
            if (ultimo != null &&
                (esResiduo(texto.substring(span.first, span.second)) ||
                    esResiduo(texto.substring(ultimo.first, ultimo.second)))
            ) {
                fusionados[fusionados.lastIndex] = ultimo.first to span.second
            } else {
                fusionados.add(span)
            }
        }
        return fusionados
    }

    private fun esResiduo(fragmento: String): Boolean =
        fragmento.all { it in PUNTUACION || it.isWhitespace() }
}
```

- [ ] **Step 4: Correr y ver que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests '*SegmentadorTextoTest*'
```
Esperado: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/SegmentadorTexto.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/SegmentadorTextoTest.kt
git commit -m "feat(app): port del segmentador de oraciones a Kotlin (Plan 4b)"
```

---

### Task 2: GeneradorFurigana

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/GeneradorFurigana.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/GeneradorFuriganaTest.kt`

**Interfaces:**
- Consumes: `Tokenizador.tokenizar(texto): List<PalabraToken>` y `katakanaAHiragana(String)` de `dominio/Tokenizador.kt`; `Furigana(inicio, fin, lectura)` de `datos/ModelosHistoria.kt`.
- Produces: `class GeneradorFurigana(tokenizador: Tokenizador)` con `fun generar(oracion: String): List<Furigana>`. Lo consume Task 5.

Reglas (spec §Componentes 2): terna solo para tokens con ≥1 kanji y lectura conocida; trim de okurigana por ambos extremos comparando superficie (convertida a hiragana) contra la lectura; si el trim degenera (núcleo sin kanji o lectura vacía), terna sobre el token completo; tokens de kana/latín/puntuación no emiten terna. Los tokens de Kuromoji no se solapan ⇒ spans disjuntos gratis.

- [ ] **Step 1: Test que falla**

El test usa el `Tokenizador` real (Kuromoji ya carga en `TokenizadorTest` con `maxHeapSize 2g`). Una sola instancia compartida por clase (carga ~1s).

```kotlin
package com.tatoh.dokushorenshu.dominio

import com.tatoh.dokushorenshu.datos.Furigana
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneradorFuriganaTest {
    companion object {
        private val generador = GeneradorFurigana(Tokenizador())
    }

    @Test
    fun `kanji puro lleva la lectura completa`() {
        // 桃 es un token de un solo kanji → terna [0,1) con lectura もも
        assertEquals(listOf(Furigana(0, 1, "もも")), generador.generar("桃"))
    }

    @Test
    fun `okurigana se recorta del ruby`() {
        // 走った: lectura はしった → ruby はし solo sobre 走 ([0,1))
        val ternas = generador.generar("走った")
        assertEquals(listOf(Furigana(0, 1, "はし")), ternas)
    }

    @Test
    fun `kana puro no lleva terna`() {
        assertEquals(emptyList<Furigana>(), generador.generar("ここにいる"))
    }

    @Test
    fun `katakana no lleva terna`() {
        // el ruby de katakana lo maneja el toggle カナ del lector (Plan 3.7)
        assertEquals(emptyList<Furigana>(), generador.generar("テレビ"))
    }

    @Test
    fun `latin y numeros no llevan terna`() {
        assertEquals(emptyList<Furigana>(), generador.generar("ABC123"))
    }

    @Test
    fun `oracion mixta emite ternas disjuntas dentro de rango`() {
        val texto = "犬が走った。"
        val ternas = generador.generar(texto)
        assertTrue(ternas.isNotEmpty())
        var cursor = 0
        for (f in ternas.sortedBy { it.inicio }) {
            assertTrue("solapado: $f", f.inicio >= cursor)
            assertTrue("fuera de rango: $f", f.fin <= texto.length)
            assertTrue("lectura vacía: $f", f.lectura.isNotEmpty())
            cursor = f.fin
        }
    }

    @Test
    fun `texto vacio no emite nada`() {
        assertEquals(emptyList<Furigana>(), generador.generar(""))
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

```bash
./gradlew :app:testDebugUnitTest --tests '*GeneradorFuriganaTest*'
```
Esperado: FAIL de compilación — `GeneradorFurigana` no existe.

- [ ] **Step 3: Implementación**

```kotlin
package com.tatoh.dokushorenshu.dominio

import com.tatoh.dokushorenshu.datos.Furigana

/** Furigana automática para texto importado (Plan 4b): Kuromoji puro, sin
 *  alineador Aozora. Una terna por token con kanji; el ruby se recorta al
 *  núcleo kanji comparando superficie y lectura por ambos extremos (trim de
 *  okurigana: 走った/はしった → はし sobre 走). Si el recorte degenera, la
 *  terna cubre el token completo (degradación segura). */
class GeneradorFurigana(private val tokenizador: Tokenizador) {

    fun generar(oracion: String): List<Furigana> =
        tokenizador.tokenizar(oracion).mapNotNull(::ternaDelToken)

    private fun ternaDelToken(token: PalabraToken): Furigana? {
        val lectura = token.lecturaHiragana ?: return null
        if (token.superficie.none(::esKanji)) return null
        val superficieHira = katakanaAHiragana(token.superficie)
        var pre = 0
        while (pre < superficieHira.length && pre < lectura.length &&
            !esKanji(token.superficie[pre]) && superficieHira[pre] == lectura[pre]
        ) pre++
        var post = 0
        while (post < superficieHira.length - pre && post < lectura.length - pre &&
            !esKanji(token.superficie[token.superficie.length - 1 - post]) &&
            superficieHira[superficieHira.length - 1 - post] == lectura[lectura.length - 1 - post]
        ) post++
        val nucleo = token.superficie.substring(pre, token.superficie.length - post)
        val lecturaNucleo = lectura.substring(pre, lectura.length - post)
        return if (nucleo.any(::esKanji) && lecturaNucleo.isNotEmpty()) {
            Furigana(token.inicio + pre, token.fin - post, lecturaNucleo)
        } else {
            Furigana(token.inicio, token.fin, lectura)
        }
    }

    // Mismo rango que BuscadorPalabras/ArmadorMazos (helper de 1 línea,
    // duplicado a propósito para no acoplar módulos — convención del repo).
    private fun esKanji(c: Char): Boolean = c in '一'..'鿿'
}
```

- [ ] **Step 4: Correr y ver que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests '*GeneradorFuriganaTest*'
```
Esperado: PASS (7 tests). Si `okurigana se recorta del ruby` falla porque Kuromoji tokeniza 走った distinto (走っ+た), ajustar el assert al span real manteniendo la invariante "el ruby no cubre kana final" — verificar el token real con un `println` temporal antes de fijar el valor.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/GeneradorFurigana.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/GeneradorFuriganaTest.kt
git commit -m "feat(app): generador de furigana Kuromoji con trim de okurigana (Plan 4b)"
```

---

### Task 3: DetectorJapones (heurística CJK)

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/DetectorJapones.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/DetectorJaponesTest.kt`

**Interfaces:**
- Produces: `DetectorJapones.pareceJapones(texto: String): Boolean` — ≥50% de los caracteres no-espacio en rangos japoneses. Lo consume Task 7 (`ImportViewModel`).

- [ ] **Step 1: Test que falla**

```kotlin
package com.tatoh.dokushorenshu.dominio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorJaponesTest {
    @Test
    fun `japones puro pasa`() {
        assertTrue(DetectorJapones.pareceJapones("昔々、あるところにおじいさんとおばあさんが住んでいました。"))
    }

    @Test
    fun `ingles puro no pasa`() {
        assertFalse(DetectorJapones.pareceJapones("Once upon a time there was an old man."))
    }

    @Test
    fun `mixto mayormente japones pasa`() {
        assertTrue(DetectorJapones.pareceJapones("私はAndroidが好きです。"))
    }

    @Test
    fun `vacio o solo espacios no pasa`() {
        assertFalse(DetectorJapones.pareceJapones(""))
        assertFalse(DetectorJapones.pareceJapones(" \n　"))
    }

    @Test
    fun `puntuacion japonesa cuenta como japones`() {
        assertTrue(DetectorJapones.pareceJapones("「はい。」"))
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

```bash
./gradlew :app:testDebugUnitTest --tests '*DetectorJaponesTest*'
```
Esperado: FAIL de compilación.

- [ ] **Step 3: Implementación**

```kotlin
package com.tatoh.dokushorenshu.dominio

/** Heurística del spec 4b (§Manejo de errores): si menos del 50% de los
 *  caracteres visibles son japoneses, el import avisa antes de guardar. */
object DetectorJapones {
    private const val UMBRAL = 0.5

    fun pareceJapones(texto: String): Boolean {
        val visibles = texto.filterNot { it.isWhitespace() }
        if (visibles.isEmpty()) return false
        val japoneses = visibles.count(::esJapones)
        return japoneses.toDouble() / visibles.length >= UMBRAL
    }

    private fun esJapones(c: Char): Boolean =
        c in '぀'..'ヿ' ||  // hiragana + katakana
            c in '一'..'鿿' ||       // CJK unificado (4E00..9FFF)
            c in '　'..'〿' ||  // puntuación CJK: 。「」『』（）…
            c in '！'..'｠'     // formas fullwidth: ！？０-９Ａ-Ｚ
}
```

- [ ] **Step 4: Correr y ver que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests '*DetectorJaponesTest*'
```
Esperado: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/DetectorJapones.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/DetectorJaponesTest.kt
git commit -m "feat(app): heurística de detección de japonés para el import (Plan 4b)"
```

---

### Task 4: SerializadorHistoria (inverso de ParserHistoria)

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/ModelosHistoria.kt` (agregar `object SerializadorHistoria` al final)
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/SerializadorHistoriaTest.kt`

**Interfaces:**
- Consumes: data classes `Historia/Parrafo/Oracion/Furigana` y `ParserHistoria.parsear` (mismo archivo).
- Produces: `SerializadorHistoria.serializar(historia: Historia): String` — JSON schema v2 exacto. Lo consume Task 5 (`HistoriasRepo.guardarImportada`).

- [ ] **Step 1: Test que falla**

`momotaro.json` ya existe en test resources (lo usa `HistoriasRepoTest`).

```kotlin
package com.tatoh.dokushorenshu.datos

import org.junit.Assert.assertEquals
import org.junit.Test

class SerializadorHistoriaTest {
    @Test
    fun `round trip con historia real de assets`() {
        val crudo = javaClass.classLoader!!
            .getResourceAsStream("momotaro.json")!!.readBytes().decodeToString()
        val historia = ParserHistoria.parsear(crudo)
        assertEquals(historia, ParserHistoria.parsear(SerializadorHistoria.serializar(historia)))
    }

    @Test
    fun `round trip con furigana vacia y campos minimos`() {
        val historia = Historia(
            id = "prueba", titulo = "テスト", autor = "", fuente = "import",
            licencia = "texto del usuario", dificultad = "media", version = 2,
            parrafos = listOf(Parrafo(listOf(Oracion("ここにいる。", emptyList())))),
        )
        assertEquals(historia, ParserHistoria.parsear(SerializadorHistoria.serializar(historia)))
    }

    @Test
    fun `emite traduccion null y no escapa el japones`() {
        val historia = Historia(
            id = "x", titulo = "犬", autor = "", fuente = "import",
            licencia = "texto del usuario", dificultad = "facil", version = 2,
            parrafos = listOf(Parrafo(listOf(Oracion("犬。", listOf(Furigana(0, 1, "いぬ")))))),
        )
        val json = SerializadorHistoria.serializar(historia)
        assertEquals(true, json.contains(""""traduccion":null"""))
        assertEquals(true, json.contains("犬"))  // ensure_ascii=False equivalente
        assertEquals(true, json.contains("""[0,1,"いぬ"]"""))
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

```bash
./gradlew :app:testDebugUnitTest --tests '*SerializadorHistoriaTest*'
```
Esperado: FAIL de compilación — `SerializadorHistoria` no existe.

- [ ] **Step 3: Implementación** (agregar al final de `ModelosHistoria.kt`; sumar los imports `buildJsonObject, putJsonArray, addJsonObject, addJsonArray, add, put, JsonNull` de `kotlinx.serialization.json`)

```kotlin
/** Inverso de [ParserHistoria.parsear]: emite el schema v2 exacto (mismo
 *  contrato que el emisor Python del Plan 2 — claves en español, furigana como
 *  ternas, `traduccion` siempre null, japonés sin escapar). Round-trip
 *  garantizado por test: parsear(serializar(h)) == h. */
object SerializadorHistoria {
    fun serializar(historia: Historia): String = buildJsonObject {
        put("id", historia.id)
        put("titulo", historia.titulo)
        put("autor", historia.autor)
        put("fuente", historia.fuente)
        put("licencia", historia.licencia)
        put("dificultad", historia.dificultad)
        put("version", historia.version)
        putJsonArray("parrafos") {
            for (parrafo in historia.parrafos) addJsonObject {
                putJsonArray("oraciones") {
                    for (oracion in parrafo.oraciones) addJsonObject {
                        put("texto", oracion.texto)
                        putJsonArray("furigana") {
                            for (f in oracion.furigana) addJsonArray {
                                add(f.inicio); add(f.fin); add(f.lectura)
                            }
                        }
                        put("traduccion", JsonNull)
                    }
                }
            }
        }
    }.toString()
}
```

- [ ] **Step 4: Correr y ver que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests '*SerializadorHistoriaTest*'
```
Esperado: PASS (3 tests). Nota: kotlinx emite JSON compacto sin espacios — si el assert de `"traduccion":null` falla por formato, ajustar el assert al output real, no la implementación.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/ModelosHistoria.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/SerializadorHistoriaTest.kt
git commit -m "feat(app): serializador Historia→JSON v2 con round-trip garantizado (Plan 4b)"
```

---

### Task 5: HistoriasRepo — origen importadas + ImportadorHistoria

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/HistoriasRepo.kt`
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/ImportadorHistoria.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/App.kt` (nuevo lazy `importador`)
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/HistoriasRepoTest.kt` (ampliar)
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/ImportadorHistoriaTest.kt`

**Interfaces:**
- Consumes: `SerializadorHistoria.serializar` (Task 4), `SegmentadorTexto.segmentar` (Task 1), `GeneradorFurigana.generar` (Task 2).
- Produces (Task 7 y 9 dependen de esto):
  - `HistoriasRepo` gana parámetro de constructor `dirImportadas: File` (después de `dirDescargas`, sin default; `desde()` usa `File(contexto.filesDir, "importadas")`).
  - `fun guardarImportada(historia: Historia)` — valida con round-trip parse ANTES de escribir, tmp→rename.
  - `fun borrarImportada(id: String): Boolean`
  - `fun esImportada(id: String): Boolean`
  - `fun idsLocales(): Set<String>`
  - `historiasLocales()` incluye importadas; una importada NUNCA pisa un id existente de assets/descargas.
  - `cargarHistoria(id)` resuelve también desde `dirImportadas` (prioridad: descargada > importada > asset).
  - `class ImportadorHistoria(generadorFurigana: GeneradorFurigana, historiasRepo: HistoriasRepo)` con `fun importar(titulo: String, autor: String, dificultad: String, texto: String): Historia` — dificultad en schema (`facil|media|dificil`), lanza `IllegalArgumentException` si título en blanco, dificultad inválida o texto sin contenido.

- [ ] **Step 1: Tests que fallan**

Ampliar `HistoriasRepoTest.kt` — el helper `repo(...)` gana `dirImportadas` (temp dir, mismo patrón que `dir`):

```kotlin
    // en el helper repo(...): agregar parámetro
    //   dirImportadas: File = File.createTempFile("imp", "").let { it.delete(); it.mkdirs(); it },
    // y pasarlo al constructor después de dirDescargas.

    @Test
    fun `historia importada aparece en locales y se carga por id`() {
        val repo = repo()
        val historia = ParserHistoria.parsear(momotaroJson).copy(id = "mi_texto")
        repo.guardarImportada(historia)
        assertEquals(2, repo.historiasLocales().size)
        assertEquals(historia.titulo, repo.cargarHistoria("mi_texto")!!.titulo)
        assertTrue(repo.esImportada("mi_texto"))
        assertFalse(repo.esImportada("momotaro"))
    }

    @Test
    fun `importada no pisa una historia existente con el mismo id`() {
        val repo = repo()
        val impostora = ParserHistoria.parsear(momotaroJson).copy(titulo = "偽物")
        repo.guardarImportada(impostora)  // id "momotaro" ya existe en assets
        assertEquals("桃太郎", repo.cargarHistoria("momotaro")!!.titulo)  // asset gana
        assertEquals(1, repo.historiasLocales().size)
    }

    @Test
    fun `borrar importada la saca de locales`() {
        val repo = repo()
        repo.guardarImportada(ParserHistoria.parsear(momotaroJson).copy(id = "borrable"))
        assertTrue(repo.borrarImportada("borrable"))
        assertNull(repo.cargarHistoria("borrable"))
        assertEquals(1, repo.historiasLocales().size)
        assertFalse(repo.borrarImportada("borrable"))  // segunda vez: ya no existe
    }

    @Test
    fun `idsLocales une los tres origenes`() {
        val repo = repo()
        repo.guardarImportada(ParserHistoria.parsear(momotaroJson).copy(id = "extra"))
        assertEquals(setOf("momotaro", "extra"), repo.idsLocales())
    }
```

Nuevo `ImportadorHistoriaTest.kt`:

```kotlin
package com.tatoh.dokushorenshu.dominio

import com.tatoh.dokushorenshu.datos.HistoriasRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImportadorHistoriaTest {
    companion object {
        private val generador = GeneradorFurigana(Tokenizador())
    }

    private fun tempDir() = File.createTempFile("imp", "").let { it.delete(); it.mkdirs(); it }

    private fun repo() = HistoriasRepo(
        leerAsset = { null },
        listarAssetsHistorias = { emptyList() },
        dirDescargas = tempDir(),
        dirImportadas = tempDir(),
    )

    private fun importador(repo: HistoriasRepo = repo()) = ImportadorHistoria(generador, repo)

    @Test
    fun `importa texto en parrafos y oraciones con furigana`() {
        val repo = repo()
        val historia = importador(repo).importar(
            titulo = "犬の話", autor = "", dificultad = "media",
            texto = "犬が走った。猫が寝た。\n\n「おはよう。」と言った。",
        )
        assertEquals(2, historia.parrafos.size)                       // línea en blanco separa párrafos
        assertEquals(2, historia.parrafos[0].oraciones.size)          // dos oraciones
        assertEquals(1, historia.parrafos[1].oraciones.size)          // diálogo = 1 oración
        assertTrue(historia.parrafos[0].oraciones[0].furigana.isNotEmpty())  // 犬 lleva ruby
        assertEquals("import", historia.fuente)
        assertEquals(2, historia.version)
        assertEquals(historia.id, repo.cargarHistoria(historia.id)!!.id)  // quedó persistida
    }

    @Test
    fun `id se deriva del titulo y desambigua colisiones`() {
        val repo = repo()
        val imp = importador(repo)
        val h1 = imp.importar("私の話", "", "facil", "犬。")
        val h2 = imp.importar("私の話", "", "facil", "猫。")
        assertEquals("私の話", h1.id)
        assertEquals("私の話-2", h2.id)
    }

    @Test
    fun `titulo con caracteres ilegales de filesystem se sanitiza`() {
        val h = importador().importar("a/b:c*d?e", "", "media", "犬。")
        assertEquals("a_b_c_d_e", h.id)
    }

    @Test
    fun `entradas invalidas lanzan IllegalArgumentException`() {
        val imp = importador()
        assertThrows(IllegalArgumentException::class.java) { imp.importar("", "", "media", "犬。") }
        assertThrows(IllegalArgumentException::class.java) { imp.importar("t", "", "easy", "犬。") }  // dificultad UI, no schema
        assertThrows(IllegalArgumentException::class.java) { imp.importar("t", "", "media", "   \n  ") }
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

```bash
./gradlew :app:testDebugUnitTest --tests '*HistoriasRepoTest*' --tests '*ImportadorHistoriaTest*'
```
Esperado: FAIL de compilación (constructor sin `dirImportadas`, clases nuevas inexistentes).

- [ ] **Step 3: Implementación**

`HistoriasRepo.kt` — constructor y `desde()`:

```kotlin
class HistoriasRepo(
    private val leerAsset: (String) -> String?,
    private val listarAssetsHistorias: () -> List<String>,
    private val dirDescargas: File,
    private val dirImportadas: File,
    private val http: ClienteHttp = ClienteHttpReal(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // en companion desde(): agregar
    //   dirImportadas = File(contexto.filesDir, "importadas").apply { mkdirs() },
```

Métodos nuevos y modificados:

```kotlin
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
     *  descargarHistoria: nunca guardar JSON a medias); escritura atómica. */
    fun guardarImportada(historia: Historia) {
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
```

`ImportadorHistoria.kt`:

```kotlin
package com.tatoh.dokushorenshu.dominio

import com.tatoh.dokushorenshu.datos.Historia
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.Oracion
import com.tatoh.dokushorenshu.datos.Parrafo

/** Pipeline de import (Plan 4b): texto plano → párrafos (una línea no vacía =
 *  un párrafo, mismo criterio que Aozora) → oraciones (SegmentadorTexto) →
 *  furigana Kuromoji persistida → Historia schema v2 guardada en el repo.
 *  Corre en ioDispatcher del caller (Kuromoji tarda en textos largos). */
class ImportadorHistoria(
    private val generadorFurigana: GeneradorFurigana,
    private val historiasRepo: HistoriasRepo,
) {
    fun importar(titulo: String, autor: String, dificultad: String, texto: String): Historia {
        require(titulo.isNotBlank()) { "título vacío" }
        require(dificultad in setOf("facil", "media", "dificil")) {
            "dificultad inválida: '$dificultad'"
        }
        val parrafos = texto.lines()
            .map { it.trim().trim('　') }
            .filter { it.isNotEmpty() }
            .map { linea ->
                Parrafo(SegmentadorTexto.segmentar(linea).map { (inicio, fin) ->
                    val oracion = linea.substring(inicio, fin)
                    Oracion(oracion, generadorFurigana.generar(oracion))
                })
            }
            .filter { it.oraciones.isNotEmpty() }
        require(parrafos.isNotEmpty()) { "texto sin contenido" }
        val historia = Historia(
            id = generarId(titulo),
            titulo = titulo.trim(),
            autor = autor.trim(),
            fuente = "import",
            licencia = "texto del usuario",
            dificultad = dificultad,
            version = 2,
            parrafos = parrafos,
        )
        historiasRepo.guardarImportada(historia)
        return historia
    }

    /** Id = título sanitizado para filesystem/URL de GUID; el japonés se
     *  conserva (los filesystems Android son UTF-8). Colisión → sufijo -2, -3… */
    private fun generarId(titulo: String): String {
        val base = titulo.trim()
            .replace(Regex("""[\\/:*?"<>|.\s　]+"""), "_")
            .trim('_')
            .ifEmpty { "importada" }
        val existentes = historiasRepo.idsLocales()
        if (base !in existentes) return base
        var n = 2
        while ("$base-$n" in existentes) n++
        return "$base-$n"
    }
}
```

`App.kt` — en `Contenedor`:

```kotlin
    val importador by lazy { ImportadorHistoria(GeneradorFurigana(tokenizador), historias) }
```
(con imports `com.tatoh.dokushorenshu.dominio.GeneradorFurigana` y `...dominio.ImportadorHistoria`).

- [ ] **Step 4: Correr y ver que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests '*HistoriasRepoTest*' --tests '*ImportadorHistoriaTest*' --tests '*BibliotecaViewModelTest*'
```
Esperado: PASS. Ojo: cualquier test existente que construya `HistoriasRepo` (buscar con `grep -rn "HistoriasRepo(" app/app/src/test`) necesita el parámetro nuevo `dirImportadas` — agregar temp dir en cada uno.

- [ ] **Step 5: Commit**

```bash
git add -A app/app/src
git commit -m "feat(app): origen de historias importadas + pipeline de import (Plan 4b)"
```

---

### Task 6: ImportViewModel

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/importar/ImportViewModel.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/ui/importar/ImportViewModelTest.kt`

**Interfaces:**
- Consumes: `ImportadorHistoria.importar(titulo, autor, dificultad, texto)` (Task 5), `DetectorJapones.pareceJapones` (Task 3).
- Produces (Task 7 la consume desde la Screen):
  - Estado: `data class FormImport(val texto: String = "", val titulo: String = "", val autor: String = "", val dificultad: String = "medium")` expuesto como `StateFlow<FormImport>`; setters `setTexto/setTitulo/setAutor/setDificultad`.
  - `sealed interface EstadoImport { Idle; ConfirmarNoJapones; Importando; Listo(id: String); Error(mensaje: String) }` como `StateFlow`.
  - `val puedeImportar: Boolean` derivado (texto y título no blancos, estado != Importando).
  - `fun importar(forzar: Boolean = false)` — sin forzar y texto no-japonés → `ConfirmarNoJapones`; la Screen re-llama con `forzar = true`.
  - `fun cargarArchivo(bytes: ByteArray)` — decodifica UTF-8 **estricto** (malformed → `Error("File is not valid UTF-8 text")`), pisa `texto`.
  - `fun descartarAviso()` — vuelve a Idle desde ConfirmarNoJapones/Error.
  - Mapeo dificultad UI→schema: `easy→facil, medium→media, hard→dificil` (privado del VM).

Patrón de referencia: `ExportViewModel` (StateFlow + `viewModelScope.launch` + `withContext(ioDispatcher)` + `CancellationException` re-lanzada + `log` inyectable). Tests con el patrón de `ui/export` (TestDispatcher compartido, `advanceUntilIdle()`).

- [ ] **Step 1: Tests que fallan** — casos mínimos:

```kotlin
package com.tatoh.dokushorenshu.ui.importar

import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.dominio.GeneradorFurigana
import com.tatoh.dokushorenshu.dominio.ImportadorHistoria
import com.tatoh.dokushorenshu.dominio.Tokenizador
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImportViewModelTest {
    companion object {
        private val generador = GeneradorFurigana(Tokenizador())
    }

    private val dispatcher = StandardTestDispatcher()

    @After fun despues() = Dispatchers.resetMain()

    private fun tempDir() = File.createTempFile("imp", "").let { it.delete(); it.mkdirs(); it }

    private fun vm(): ImportViewModel {
        Dispatchers.setMain(dispatcher)
        val repo = HistoriasRepo(
            leerAsset = { null }, listarAssetsHistorias = { emptyList() },
            dirDescargas = tempDir(), dirImportadas = tempDir(),
        )
        return ImportViewModel(
            importador = ImportadorHistoria(generador, repo),
            ioDispatcher = dispatcher,
            log = { _, _ -> },
        )
    }

    @Test
    fun `import feliz llega a Listo con el id`() = runTest(dispatcher) {
        val vm = vm()
        vm.setTitulo("犬の話"); vm.setTexto("犬が走った。")
        vm.importar()
        advanceUntilIdle()
        assertEquals("犬の話", (vm.estado.value as EstadoImport.Listo).id)
    }

    @Test
    fun `texto no japones pide confirmacion y forzar lo importa igual`() = runTest(dispatcher) {
        val vm = vm()
        vm.setTitulo("english"); vm.setTexto("This is English text, not Japanese at all.")
        vm.importar()
        assertEquals(EstadoImport.ConfirmarNoJapones, vm.estado.value)
        vm.importar(forzar = true)
        advanceUntilIdle()
        assertTrue(vm.estado.value is EstadoImport.Listo)
    }

    @Test
    fun `archivo no UTF-8 da error sin tocar el texto`() = runTest(dispatcher) {
        val vm = vm()
        vm.setTexto("previo")
        vm.cargarArchivo(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x40))  // no es UTF-8
        assertTrue(vm.estado.value is EstadoImport.Error)
        assertEquals("previo", vm.form.value.texto)
    }

    @Test
    fun `archivo UTF-8 valido reemplaza el texto`() = runTest(dispatcher) {
        val vm = vm()
        vm.cargarArchivo("犬が走った。".toByteArray())
        assertEquals("犬が走った。", vm.form.value.texto)
    }

    @Test
    fun `dificultad UI se mapea al schema`() = runTest(dispatcher) {
        val vm = vm()
        vm.setTitulo("た"); vm.setTexto("犬。"); vm.setDificultad("hard")
        vm.importar(forzar = true)
        advanceUntilIdle()
        assertTrue(vm.estado.value is EstadoImport.Listo)
        // dificultad persistida como "dificil" — verificado vía ImportadorHistoriaTest;
        // acá alcanza con que no lance IllegalArgumentException ("hard" crudo lanzaría).
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

```bash
./gradlew :app:testDebugUnitTest --tests '*ImportViewModelTest*'
```
Esperado: FAIL de compilación.

- [ ] **Step 3: Implementación**

```kotlin
package com.tatoh.dokushorenshu.ui.importar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tatoh.dokushorenshu.dominio.DetectorJapones
import com.tatoh.dokushorenshu.dominio.ImportadorHistoria
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class FormImport(
    val texto: String = "",
    val titulo: String = "",
    val autor: String = "",
    val dificultad: String = "medium",  // UI: easy|medium|hard
)

sealed interface EstadoImport {
    data object Idle : EstadoImport
    /** El texto no parece japonés (spec: avisar ANTES de guardar). La UI
     *  muestra diálogo continuar/cancelar; continuar = importar(forzar=true). */
    data object ConfirmarNoJapones : EstadoImport
    data object Importando : EstadoImport
    data class Listo(val id: String) : EstadoImport
    data class Error(val mensaje: String) : EstadoImport
}

/** UI easy/medium/hard → schema facil/media/dificil (contrato catálogo v2). */
private val DIFICULTAD_SCHEMA = mapOf("easy" to "facil", "medium" to "media", "hard" to "dificil")

class ImportViewModel(
    private val importador: ImportadorHistoria,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String, Throwable) -> Unit = { msg, t -> android.util.Log.e("ImportViewModel", msg, t) },
) : ViewModel() {
    private val _form = MutableStateFlow(FormImport())
    val form: StateFlow<FormImport> = _form

    private val _estado = MutableStateFlow<EstadoImport>(EstadoImport.Idle)
    val estado: StateFlow<EstadoImport> = _estado

    fun setTexto(v: String) { _form.value = _form.value.copy(texto = v) }
    fun setTitulo(v: String) { _form.value = _form.value.copy(titulo = v) }
    fun setAutor(v: String) { _form.value = _form.value.copy(autor = v) }
    fun setDificultad(v: String) { _form.value = _form.value.copy(dificultad = v) }

    fun descartarAviso() { _estado.value = EstadoImport.Idle }

    fun importar(forzar: Boolean = false) {
        val f = _form.value
        if (f.texto.isBlank() || f.titulo.isBlank()) return
        if (_estado.value is EstadoImport.Importando) return  // guard doble-tap
        if (!forzar && !DetectorJapones.pareceJapones(f.texto)) {
            _estado.value = EstadoImport.ConfirmarNoJapones
            return
        }
        _estado.value = EstadoImport.Importando
        viewModelScope.launch {
            try {
                val historia = withContext(ioDispatcher) {
                    importador.importar(
                        titulo = f.titulo,
                        autor = f.autor,
                        dificultad = DIFICULTAD_SCHEMA.getValue(f.dificultad),
                        texto = f.texto,
                    )
                }
                _estado.value = EstadoImport.Listo(historia.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("importar() falló", e)
                _estado.value = EstadoImport.Error("Import failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Decodifica UTF-8 estricto: bytes inválidos → Error, nunca mojibake
     *  silencioso (String(bytes) reemplazaría con U+FFFD sin avisar). */
    fun cargarArchivo(bytes: ByteArray) {
        try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val texto = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            _form.value = _form.value.copy(texto = texto)
            _estado.value = EstadoImport.Idle
        } catch (e: Exception) {
            _estado.value = EstadoImport.Error("File is not valid UTF-8 text")
        }
    }
}
```

- [ ] **Step 4: Correr y ver que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests '*ImportViewModelTest*'
```
Esperado: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/importar/ImportViewModel.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/ui/importar/ImportViewModelTest.kt
git commit -m "feat(app): ImportViewModel con aviso de no-japonés y carga de .txt (Plan 4b)"
```

---

### Task 7: ImportScreen + navegación + botón en Biblioteca

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/importar/ImportScreen.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt` (ruta `importar`)
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaScreen.kt` (callback `onImportar` + botón "Import" junto a Export)

**Interfaces:**
- Consumes: `ImportViewModel` completo (Task 6), `contenedor.importador` (Task 5).
- Produces: ruta `importar`; `BibliotecaScreen` gana parámetro `onImportar: () -> Unit`.

Sin test unitario de Screen (las Screens del repo no se testean por unit test); el gate es compilación + smoke manual en Task 10.

- [ ] **Step 1: ImportScreen**

Estructura (seguir el estilo visual de `ExportScreen`: `Scaffold` + `TopAppBar` con back, `Column` con `verticalScroll`):

- `OutlinedTextField` título (single line, label "Title *").
- `OutlinedTextField` autor (single line, label "Author").
- Fila de `FilterChip` para dificultad: Easy / Medium / Hard (seleccionado = `form.dificultad`).
- `OutlinedTextField` de texto grande (`minLines = 10`, label "Japanese text — paste here").
- Botón "Open .txt" con `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())`, `launch(arrayOf("text/plain"))`; en el callback, `contentResolver.openInputStream(uri)?.use { it.readBytes() }` → `vm.cargarArchivo(bytes)`.
- Botón primario "Import": `enabled = form.titulo.isNotBlank() && form.texto.isNotBlank() && estado !is EstadoImport.Importando`; al tocar, `vm.importar()`. Spinner dentro del botón durante `Importando` (mismo patrón del botón de Export, commit `debc03b`).
- `estado is ConfirmarNoJapones` → `AlertDialog` "This doesn't look like Japanese text. Import anyway?" con Import anyway (`vm.importar(forzar = true)`) / Cancel (`vm.descartarAviso()`).
- `estado is Error` → `Snackbar` con el mensaje (y `vm.descartarAviso()` al mostrarse).
- `estado is Listo` → `LaunchedEffect(estado)` que llama `onImportado(estado.id)`.

Firma:

```kotlin
@Composable
fun ImportScreen(
    vm: ImportViewModel,
    onImportado: (String) -> Unit,   // vuelve a biblioteca (popBackStack)
    onCerrar: () -> Unit,
)
```

- [ ] **Step 2: Ruta en MainActivity** (dentro del `NavHost`, después de `export`):

```kotlin
composable("importar") {
    val vm: ImportViewModel = viewModel(factory = viewModelFactory {
        initializer { ImportViewModel(contenedor.importador) }
    })
    ImportScreen(
        vm = vm,
        onImportado = { nav.popBackStack() },
        onCerrar = { nav.popBackStack() },
    )
}
```

Y en el `composable("biblioteca")`: pasar `onImportar = { nav.navigate("importar") }` a `BibliotecaScreen`.

- [ ] **Step 3: Botón en BibliotecaScreen** — agregar parámetro `onImportar: () -> Unit` y un botón/ícono "Import" al lado del de Export existente (mismo componente y estilo que use Export; leer el archivo antes de tocar).

- [ ] **Step 4: Compilar**

```bash
./gradlew :app:compileDebugKotlin
```
Esperado: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A app/app/src/main
git commit -m "feat(app): pantalla de import con picker de .txt y aviso de no-japonés (Plan 4b)"
```

---

### Task 8: Biblioteca — badge "imported" + borrado

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaViewModel.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaScreen.kt`
- Test: ampliar el test existente en `app/app/src/test/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/`

**Interfaces:**
- Consumes: `HistoriasRepo.esImportada(id)`, `borrarImportada(id)` (Task 5).
- Produces: `ItemBiblioteca` gana `val importada: Boolean = false`; VM gana `fun borrarImportada(id: String)` que borra y re-llama `cargar()`.

- [ ] **Step 1: Test que falla** (en el test de VM existente — seguir su patrón de fakes/dispatcher; el repo del test necesita `dirImportadas` desde Task 5):

```kotlin
    @Test
    fun `historia importada se marca y se puede borrar`() = runTest(dispatcher) {
        // guardar una importada en el repo del test
        repo.guardarImportada(ParserHistoria.parsear(momotaroJson).copy(id = "propia", titulo = "自作"))
        vm.cargar()
        advanceUntilIdle()
        val item = vm.locales.value.first { it.historia.id == "propia" }
        assertTrue(item.importada)
        assertFalse(vm.locales.value.first { it.historia.id == "momotaro" }.importada)

        vm.borrarImportada("propia")
        advanceUntilIdle()
        assertTrue(vm.locales.value.none { it.historia.id == "propia" })
    }
```

- [ ] **Step 2: Correr y ver que falla**

```bash
./gradlew :app:testDebugUnitTest --tests '*BibliotecaViewModelTest*'
```
Esperado: FAIL (no existe `importada` ni `borrarImportada`).

- [ ] **Step 3: Implementación VM**

```kotlin
data class ItemBiblioteca(
    val historia: Historia,
    val progresoPct: Int,
    val metadata: EntradaCatalogo?,
    val importada: Boolean = false,
)
```

En `cargar()`, dentro del map: `ItemBiblioteca(historia, pct, metadata, importada = historiasRepo.esImportada(historia.id))`.

```kotlin
    fun borrarImportada(id: String) {
        viewModelScope.launch {
            withContext(ioDispatcher) { historiasRepo.borrarImportada(id) }
            cargar()
        }
    }
```

- [ ] **Step 4: UI** — en `BibliotecaScreen`, tarjeta de historia local: si `item.importada`, mostrar badge "Imported" (un `AssistChip`/`Badge` chico junto al título, estilo del tema) y un `IconButton` de borrar (`Icons.Default.Delete`) que abre `AlertDialog` "Delete this imported story? Reading progress will be kept." con Delete (`vm.borrarImportada(id)`) / Cancel. Leer la tarjeta actual antes de tocar y seguir su estructura.

- [ ] **Step 5: Correr tests + compilar**

```bash
./gradlew :app:testDebugUnitTest --tests '*BibliotecaViewModelTest*' && ./gradlew :app:compileDebugKotlin
```
Esperado: PASS + BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A app/app/src
git commit -m "feat(app): badge y borrado de historias importadas en la biblioteca (Plan 4b)"
```

---

### Task 9: Export Stories con selección de historias

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazos.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportViewModel.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportScreen.kt`
- Test: ampliar `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/` y `.../ui/export/`

**Interfaces:**
- Consumes: `ArmadorMazos.armarHistorias(historias)` existente.
- Produces:
  - `ArmadorMazos`: `data class HistoriaResumen(val id: String, val titulo: String)` + `fun resumenHistorias(): List<HistoriaResumen>` (id/título de `historiasLocales()`, en su orden); `armarHistorias` gana parámetro `seleccion: Set<String>? = null` — `null` = todas (compat), set = filtra por id.
  - `ExportViewModel`: `val historiasStories: StateFlow<List<HistoriaResumen>>` (cargado en `cargar()`), `val seleccionadas: StateFlow<Set<String>>` (default: todos los ids), `fun toggleHistoria(id: String)`; `exportar(STORIES)` pasa `seleccion = seleccionadas.value`; botón Stories deshabilitado si la selección está vacía (la UI decide con `seleccionadas`).

- [ ] **Step 1: Tests que fallan**

En el test de `ArmadorMazos` existente (seguir sus fakes):

```kotlin
    @Test
    fun `armarHistorias con seleccion filtra los mazos`() = runTest {
        // armador con ≥2 historias locales (fixture del test existente)
        val todas = armador.armarHistorias()
        val unId = todas.mazos.first().idHistoria
        val filtrado = armador.armarHistorias(seleccion = setOf(unId))
        assertEquals(listOf(unId), filtrado.mazos.map { it.idHistoria })
    }

    @Test
    fun `resumenHistorias devuelve id y titulo de las locales`() {
        val resumen = armador.resumenHistorias()
        assertTrue(resumen.isNotEmpty())
        assertTrue(resumen.all { it.id.isNotBlank() && it.titulo.isNotBlank() })
    }
```

En el test de `ExportViewModel` existente:

```kotlin
    @Test
    fun `stories exporta solo las historias seleccionadas`() = runTest(dispatcher) {
        vm.cargar(); advanceUntilIdle()
        val ids = vm.historiasStories.value.map { it.id }
        assertEquals(ids.toSet(), vm.seleccionadas.value)  // default: todas
        vm.toggleHistoria(ids.first())                     // des-seleccionar una
        vm.exportar(TipoExport.STORIES); advanceUntilIdle()
        // el fake escribirMazos captura los mazos: no debe incluir la destildada
        assertEquals(ids.drop(1).toSet(), mazosEscritos.map { it.deckIdOrigen }.toSet())
        // ajustar la aserción al mecanismo real del fake existente (captura de
        // escribirMazos) — la invariante: mazos escritos == seleccionadas.
    }
```

- [ ] **Step 2: Correr y ver que falla**

```bash
./gradlew :app:testDebugUnitTest --tests '*ArmadorMazos*' --tests '*ExportViewModel*'
```
Esperado: FAIL de compilación.

- [ ] **Step 3: Implementación**

`ArmadorMazos.kt`:

```kotlin
data class HistoriaResumen(val id: String, val titulo: String)

    fun resumenHistorias(): List<HistoriaResumen> =
        historiasRepo.historiasLocales().map { HistoriaResumen(it.id, it.titulo) }

    suspend fun armarHistorias(
        historias: List<Historia> = historiasRepo.historiasLocales(),
        seleccion: Set<String>? = null,
    ): ResultadoHistorias {
        val elegidas = if (seleccion == null) historias else historias.filter { it.id in seleccion }
        // …resto igual, iterando `elegidas` en vez de `historias`
    }
```

`ExportViewModel.kt`:

```kotlin
    private val _historiasStories = MutableStateFlow<List<HistoriaResumen>>(emptyList())
    val historiasStories: StateFlow<List<HistoriaResumen>> = _historiasStories

    private val _seleccionadas = MutableStateFlow<Set<String>>(emptySet())
    val seleccionadas: StateFlow<Set<String>> = _seleccionadas

    fun toggleHistoria(id: String) {
        _seleccionadas.value =
            if (id in _seleccionadas.value) _seleccionadas.value - id
            else _seleccionadas.value + id
    }
```

En `cargar()`: `_historiasStories.value = armadorMazos.resumenHistorias()` y `_seleccionadas.value = _historiasStories.value.map { it.id }.toSet()` (dentro del mismo `withContext`). En `exportar`, rama STORIES: `armadorMazos.armarHistorias(seleccion = _seleccionadas.value)` y guard `if (_seleccionadas.value.isEmpty()) { _estado.value = EstadoExport.Error("Select at least one story"); return@launch }` antes de `Generando` (nota: poner el guard ANTES de setear Generando).

`ExportScreen.kt`: en la sección Stories, debajo del botón, lista de historias con `Checkbox` + título (una fila por historia de `historiasStories`, checked = `id in seleccionadas`, onCheckedChange = `vm.toggleHistoria(id)`). Botón Stories `enabled` también exige `seleccionadas.isNotEmpty()`.

- [ ] **Step 4: Correr y ver que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests '*ArmadorMazos*' --tests '*ExportViewModel*' && ./gradlew :app:compileDebugKotlin
```
Esperado: PASS + BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A app/app/src
git commit -m "feat(app): selección de historias para el mazo Stories (Plan 4b)"
```

---

### Task 10: Docs + verificación final

**Files:**
- Modify: `app/README.md` (sección "Importing your own text")
- Modify: `docs/ESTADO.md` (fila 4b + nota backlog "más historias base")

- [ ] **Step 1: Sección en `app/README.md`** (en español, como el resto del README — leerlo primero y seguir su tono). Contenido mínimo:
  - Cómo llegar: Library → Import.
  - Dos vías: pegar texto / Open .txt (UTF-8; si el archivo viene de Windows en Shift-JIS, convertirlo antes — límite conocido v1).
  - Metadata: título obligatorio, dificultad manual, autor opcional.
  - La furigana es automática (Kuromoji) y puede errar lecturas de nombres propios — límite conocido.
  - Borrado: ícono en la tarjeta (el progreso de lectura se conserva).
  - Las importadas entran al mazo "Dokusho — Stories" (elegibles por checkbox en Export).

- [ ] **Step 2: `docs/ESTADO.md`**:
  - Fila 4b → `✅ Completo (PR pendiente — actualizar con #N al abrir)`.
  - En "Datos operativos", agregar línea `**Import (4b)**: …` con: segmentador Kotlin en `dominio/SegmentadorTexto.kt` (port fiel, regla de fusión incluida), furigana persistida (Kuromoji + trim de okurigana), historias importadas en `filesDir/importadas/` (nunca pisan ids de catálogo), export Stories con selección.
  - En backlog (sección nueva o al final): `- Agregar más historias base al catálogo (pipeline Plan 2).`

- [ ] **Step 3: Verificación completa**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/app
./gradlew test
grep -rcP '\x1f' app/src/main/kotlin | grep -v ':0$' ; echo "exit=$? (1 = ok, ningún archivo con U+001F literal)"
```
Esperado: BUILD SUCCESSFUL, todos los tests PASS, grep sin matches.

- [ ] **Step 4: Smoke manual en tablet** (adb install — ver quirks MIUI en memoria de entorno):
  1. Import con texto pegado (título japonés) → aparece en Library con badge → se lee con furigana y toggle カナ → tap de palabra abre el sheet.
  2. Import de un `.txt` UTF-8 desde el file picker.
  3. Import de texto en inglés → aparece el aviso → Cancel no guarda.
  4. Borrar la importada → desaparece; re-importar mismo título → id `-2`.
  5. Export Stories destildando una historia → el .apkg en AnkiDroid solo trae los subdecks tildados.

- [ ] **Step 5: Commit + PR**

```bash
git add app/README.md docs/ESTADO.md
git commit -m "docs: guía de import de texto propio + estado Plan 4b"
git push -u origin feature/plan-4b-import-texto
gh pr create --title "Plan 4b: import de texto propio" --body "..."
```
(El body del PR se redacta al cierre con el resumen real de lo implementado; terminar con la línea de atribución de Claude Code.)

---

## Self-Review (hecho al escribir)

- **Cobertura del spec**: decisiones 1-6 → Tasks 2/6/7 (furigana persistida), 6/7 (entrada doble + UTF-8), 6 (metadata + mapeo dificultad), 5/8 (borrado), 9 (Anki selección), 5 (persistencia importadas). Heurística CJK → Task 3+6. Docs → Task 10. ✔
- **Placeholders**: los pasos de UI puro (Tasks 7/8/9 Screen) describen estructura sin código Compose completo a propósito — el implementer debe leer la Screen existente y calcar su estilo; los contratos y firmas están completos. ✔
- **Consistencia de tipos**: `ImportadorHistoria.importar(titulo, autor, dificultad, texto)` igual en Tasks 5/6; `dirImportadas` como 4º parámetro del constructor en Tasks 5/6/8; `HistoriaResumen` definido en Task 9 donde se usa. ✔
