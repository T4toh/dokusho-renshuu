# Plan 4a: mazos Anki (.apkg) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
> Spec: `docs/superpowers/specs/2026-07-09-plan-4a-mazos-anki-design.md`. PC secundaria (Java/Android OK). AnkiDroid instalado en el POCO para el gate final.

**Goal:** Exportar mazos Anki .apkg ("Dokusho — Words" con todas las palabras tocadas y "Dokusho — Kanji" con los kanjis taggeados) con cartas estilo Kaishi: furigana HTML real de las historias, oraciones de ejemplo que rotan por review vía JS, GUIDs estables para re-exportar sin duplicar, compartidos por share intent.

**Architecture:** `dominio/anki/` en tres unidades: `ModeloNotas` (constantes/IDs, data classes de notas con campos String formateados, GUID base91/SHA-256 estilo genanki, templates HTML+JS+CSS), `EscritorApkg` (SQLite con schema Anki 2.1 verificado contra el source de genanki + zip), `ArmadorMazos` (enriquece Room+Diccionario+historias con ruby HTML desde spans reales, cap 5 oraciones, prioridad historias>Tatoeba). UI: `ui/export/` (VM con estados + Screen) + botón en Biblioteca + FileProvider/share.

**Tech Stack:** Kotlin puro + android.database.sqlite + java.util.zip (SIN dependencias nuevas salvo androidx.core-ktx para FileProvider si el brief de Task 5 lo indica — verificada >7 días, regla org). Patrones post-3.7.

## Global Constraints

- Branch `feature/plan-4a-mazos` desde main actualizado; PR al final. Prefijo `feat(app):` (docs con `docs(...)`).
- Código/comentarios español; strings de UI en inglés.
- **Contrato de notas (Task 1 produce, 3-4 consumen)**: `NotaWords(palabra, lectura, significados: String, tag: String = "", oraciones: List<String> = emptyList())`; `NotaKanji(kanji, onYomi: String, kunYomi: String, significados: String, dificultad, oraciones: List<String> = emptyList())` — lecturas unidas con `、`, glosas con `; `. `guidDe(clave)` determinístico (base91/SHA-256, primeros 8 bytes). `EscritorApkg.escribir(destino: File, notasWords, notasKanji)`.
- **Schema .apkg**: DDL y col JSON verificados contra genanki (fuente curl'd) — el plan los trae completos; desviaciones documentadas: csum real de Anki (sha1 8 hex del primer campo), guid por clave estable (no por hash de campos: requisito de re-export sin duplicar).
- **Cartas**: sin romaji; ruby = HTML `<ruby>漢字<rt>かんじ</rt></ruby>` desde spans fin-exclusivo; rotación JS con fallback Oracion1; cap 5 (historias primero, Tatoeba relleno).
- **Kanji deck**: solo taggeados (dificultad != null); kanji fuera del db → skip contado. Words: todas las tocadas; sin definición → lectura sola, nunca falla.
- Patrones establecidos: ioDispatcher inyectable, fakes compartidos, AGP 9.2 built-in Kotlin, tests maxHeapSize 2g. Suite base: app 88. TDD por task; suite completa verde + counts exactos antes de cada commit; `assembleDebug` gate para UI.
- El separador de campos en notes.flds es el carácter U+001F: escribirlo SIEMPRE como el escape Kotlin backslash-u-0-0-1-f (seis caracteres visibles en el código fuente), NUNCA como carácter de control literal — desaparece silenciosamente al copiar/pegar.
- Orden: Tasks 1-2 (writer) → 3 (armador) → 4 (UI) → 5 (share) → 6 (cierre).

---
## Plan 4a — mazos Anki (.apkg), Tareas 1-2 (`dominio/anki/`) — draft de tareas

> Draft parcial: cubre solo el corazón del writer (`ModeloNotas.kt` + `EscritorApkg.kt`)
> del spec `docs/superpowers/specs/2026-07-09-plan-4a-mazos-anki-design.md`. Quedan
> fuera de este draft (ciclos futuros del plan): `ArmadorMazos.kt` (junta datos desde
> Room/Diccionario/HistoriasRepo), `ui/export/` (pantalla + ViewModel), FileProvider.
> Tareas numeradas 1..2 acá; se renumeran al ensamblar el plan final.
>
> **Para agentic workers:** REQUIRED SUB-SKILL: usar `superpowers:subagent-driven-development`
> (recomendado) o `superpowers:executing-plans` para ejecutar tarea por tarea.

**Goal:** Escribir un `.apkg` válido (importable por AnkiDroid) sin ninguna
dependencia nueva: `dominio/anki/ModeloNotas.kt` define los IDs fijos de modelo/mazo,
las notas (`NotaWords`/`NotaKanji`), el GUID determinístico y los templates
HTML+CSS+JS (rotación de oraciones); `dominio/anki/EscritorApkg.kt` genera el zip
(`collection.anki2` SQLite + `media`) a partir de listas de notas ya armadas.

**Investigación de schema (base de este draft):** el `.apkg` es un zip con
`collection.anki2` (SQLite, "Anki 2 legacy", `ver = 11`) + `media` (JSON). Se extrajo
el DDL y los JSON exactos del `col` desde el código fuente real de **genanki**
(`kerrickstaley/genanki`, MIT), descargado con `curl` de
`raw.githubusercontent.com/kerrickstaley/genanki/master/genanki/{apkg_schema,apkg_col,note,model,deck,card,package,util,builtin_models}.py`
(contenido verbatim, no resumen de IA — WebFetch se usó primero pero devolvía resúmenes
truncados; curl trajo el texto completo). Puntos verificados directamente del código:

- DDL completo de `col`/`notes`/`cards`/`revlog`/`graves` + 7 índices
  (`genanki/apkg_schema.py`, `APKG_SCHEMA`) — reproducido palabra por palabra abajo.
- Fila `col`: JSON de `conf`/`dconf` (`genanki/apkg_col.py`, `APKG_COL`) — reproducido
  y adaptado a nuestros IDs; `models`/`decks` construidos desde `Model.to_json`
  (`genanki/model.py`) y `Deck.to_json` (`genanki/deck.py`).
- `notes`: orden de columnas y `flds` (`\x1f`-joined) desde `Note.write_to_db`
  (`genanki/note.py`). **Un punto donde nos apartamos de genanki a propósito**:
  genanki graba `csum = 0` ("can be ignored", ver comentario en su código); nosotros
  SÍ calculamos `csum` real (sha1 del primer campo, primeros 8 hex como entero) porque
  el spec de la 4a lo pide explícitamente y es el algoritmo real de Anki
  (`fieldChecksum` en `anki/utils.py`, no parte de genanki pero de dominio público/muy
  documentado) — no afecta la importación, solo alimenta `ix_notes_csum` (que Anki usa
  para su función "Find Duplicates", no para el import en sí).
- `cards`: orden de columnas desde `genanki/card.py` (`type=0`, `queue=0` para carta
  nueva no suspendida).
- `guid`: `genanki/util.py`, función `guid_for` — **SHA-256** (no SHA-1) de la clave,
  primeros 8 bytes como entero sin signo, convertido a la tabla base91 de 91 símbolos
  de Anki. Reproducida exacta (alfabeto completo transcripto).
- **Desviación deliberada de genanki (pedida por el spec de la 4a):** genanki calcula
  el guid sobre TODOS los campos de la nota (`guid_for(*self.fields)`); nosotros lo
  calculamos sobre una clave estable más angosta (`"words:" + palabra` /
  `"kanji:" + kanji`), para que el guid NO cambie si cambian las oraciones de ejemplo
  en un re-export (requisito del spec: "re-export actualiza, no duplica").

**Riesgo/assumption a verificar en dispositivo real** (gate final del spec, no de esta
tarea): el comportamiento de "actualiza en vez de duplicar" en el import depende de que
AnkiDroid, al encontrar un guid ya existente, actualice la nota en vez de saltarla o
duplicarla — esto es comportamiento del **importador** de Anki/AnkiDroid, no algo que
`EscritorApkg` controle; el writer solo garantiza que el guid es estable. Si AnkiDroid
en la práctica solo "salta" duplicados (no actualiza campos), un cambio en
`Significados`/oraciones no se reflejaría hasta que el usuario borre y reimporte — esto
queda anotado para el gate de dispositivo del spec, no bloquea esta tarea.

**Tech Stack:** Kotlin, `android.database.sqlite.SQLiteDatabase` (nativo Android, sin
Room), `kotlinx.serialization.json` (ya dependencia del proyecto, se reusa su DSL
`buildJsonObject`/`buildJsonArray` para el `col`), `java.util.zip` (nativo JDK),
JUnit4 + Robolectric (SQLite real vía shadow nativo, mismo patrón que
`DiccionarioSqliteTest`).

## Global Constraints (del spec, aplican a toda tarea)

- Código/comentarios en español.
- **Cero dependencias nuevas**: solo `android.database.sqlite.*`, `java.util.zip.*`,
  `java.security.MessageDigest`, `kotlinx.serialization.json` (ya en
  `app/app/build.gradle.kts`).
- IDs de modelo/mazo son **constantes fijas en el código**, nunca derivadas de
  timestamp de ejecución — si cambiaran en cada export, el re-export duplicaría
  mazos/modelos en vez de actualizar.
- Sin placeholders: cada step lleva DDL/JSON/código completo, comandos exactos.
- Antes de cada commit: `cd app && ./gradlew test` verde.

---

### Task 1: `dominio/anki/ModeloNotas.kt` — IDs, notas, GUID determinístico, templates

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ModeloNotas.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/ModeloNotasTest.kt`

**Interfaces:**
- Produces:
  - `ModeloNotas.MODEL_ID_WORDS: Long`, `ModeloNotas.MODEL_ID_KANJI: Long`,
    `ModeloNotas.DECK_ID_WORDS: Long`, `ModeloNotas.DECK_ID_KANJI: Long` — constantes
    fijas (estilo timestamp-ms de Anki, nunca recalculadas).
  - `ModeloNotas.NOMBRE_DECK_WORDS`/`NOMBRE_DECK_KANJI: String` — `"Dokusho — Words"` /
    `"Dokusho — Kanji"` (nombres del spec).
  - `ModeloNotas.CAMPOS_WORDS`/`CAMPOS_KANJI: List<String>` — nombres de campo en
    orden exacto, usados por `EscritorApkg` (Task 2) para construir el JSON de modelo.
  - `ModeloNotas.CSS: String`, `ModeloNotas.QFMT_WORDS/AFMT_WORDS/QFMT_KANJI/AFMT_KANJI: String`.
  - `ModeloNotas.guidDe(clave: String): String` — determinístico, SHA-256 + base91.
  - `data class NotaWords(palabra, lectura, significados, tag = "", oraciones: List<String> = emptyList())`
    con `.campos(): List<String>` (9 campos en el orden de `CAMPOS_WORDS`) y
    `.claveGuid: String`.
  - `data class NotaKanji(kanji, onYomi, kunYomi, significados, dificultad, oraciones: List<String> = emptyList())`
    con `.campos(): List<String>` (10 campos) y `.claveGuid: String`.
- Consumes: nada (dominio puro, sin Room ni Diccionario).

- [ ] **Step 1: Write the failing test**

Crear `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/ModeloNotasTest.kt`:

```kotlin
package com.tatoh.dokushorenshu.dominio.anki

import org.junit.Assert.*
import org.junit.Test

class ModeloNotasTest {

    // --- guidDe: determinístico estilo genanki (SHA-256 sobre una CLAVE estable,
    // no sobre los campos completos — así el guid no cambia si cambian las
    // oraciones en un re-export). ---

    @Test
    fun `guid estable entre llamadas para la misma clave`() {
        val a = ModeloNotas.guidDe("words:物語")
        val b = ModeloNotas.guidDe("words:物語")
        assertEquals(a, b)
    }

    @Test
    fun `guid distinto para claves distintas`() {
        assertNotEquals(ModeloNotas.guidDe("words:物語"), ModeloNotas.guidDe("words:犬"))
        assertNotEquals(ModeloNotas.guidDe("words:犬"), ModeloNotas.guidDe("kanji:犬"))
    }

    @Test
    fun `guid no esta vacio y usa solo el alfabeto base91 de Anki`() {
        val alfabeto = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
            "!#\$%&()*+,-./:;<=>?@[]^_`{|}~").toSet()
        val guid = ModeloNotas.guidDe("words:物語")
        assertTrue(guid.isNotEmpty())
        assertTrue(guid.all { it in alfabeto })
    }

    // --- NotaWords/NotaKanji: orden de campos y cap de 5 oraciones ---

    @Test
    fun `NotaWords campos en el orden del modelo, oraciones faltantes quedan vacias`() {
        val nota = NotaWords(
            palabra = "物語", lectura = "ものがたり", significados = "tale",
            oraciones = listOf("oración 1", "oración 2"),
        )
        assertEquals(
            listOf("物語", "ものがたり", "tale", "", "oración 1", "oración 2", "", "", ""),
            nota.campos(),
        )
        assertEquals("words:物語", nota.claveGuid)
    }

    @Test
    fun `NotaKanji campos en el orden del modelo`() {
        val nota = NotaKanji(
            kanji = "犬", onYomi = "ケン", kunYomi = "いぬ", significados = "dog",
            dificultad = "easy", oraciones = listOf("oración kanji"),
        )
        assertEquals(
            listOf("犬", "ケン", "いぬ", "dog", "easy", "oración kanji", "", "", "", ""),
            nota.campos(),
        )
        assertEquals("kanji:犬", nota.claveGuid)
    }

    @Test
    fun `mas de 5 oraciones lanza excepcion en ambas notas`() {
        val seis = List(6) { "o$it" }
        assertThrows(IllegalArgumentException::class.java) {
            NotaWords(palabra = "x", lectura = "x", significados = "x", oraciones = seis)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotaKanji(kanji = "x", onYomi = "x", kunYomi = "x", significados = "x", dificultad = "x", oraciones = seis)
        }
    }

    // --- Templates: contienen los marcadores de campo y el mecanismo de rotación ---

    @Test
    fun `template Words referencia sus campos y tiene el div de rotacion`() {
        assertTrue(ModeloNotas.QFMT_WORDS.contains("{{Palabra}}"))
        assertTrue(ModeloNotas.AFMT_WORDS.contains("{{Lectura}}"))
        assertTrue(ModeloNotas.AFMT_WORDS.contains("{{Significados}}"))
        assertTrue(ModeloNotas.AFMT_WORDS.contains("{{Tag}}"))
        for (i in 1..5) assertTrue(ModeloNotas.AFMT_WORDS.contains("{{Oracion$i}}"))
        assertTrue(ModeloNotas.AFMT_WORDS.contains("id=\"oracion\""))
        assertTrue(ModeloNotas.AFMT_WORDS.contains("<script>"))
    }

    @Test
    fun `template Kanji referencia sus campos y tiene el div de rotacion`() {
        assertTrue(ModeloNotas.QFMT_KANJI.contains("{{Kanji}}"))
        assertTrue(ModeloNotas.AFMT_KANJI.contains("{{OnYomi}}"))
        assertTrue(ModeloNotas.AFMT_KANJI.contains("{{KunYomi}}"))
        assertTrue(ModeloNotas.AFMT_KANJI.contains("{{Significados}}"))
        assertTrue(ModeloNotas.AFMT_KANJI.contains("{{Dificultad}}"))
        for (i in 1..5) assertTrue(ModeloNotas.AFMT_KANJI.contains("{{Oracion$i}}"))
        assertTrue(ModeloNotas.AFMT_KANJI.contains("id=\"oracion\""))
        assertTrue(ModeloNotas.AFMT_KANJI.contains("<script>"))
    }

    @Test
    fun `IDs de modelo y mazo son distintos entre si (nunca colisionan)`() {
        val ids = setOf(
            ModeloNotas.MODEL_ID_WORDS, ModeloNotas.MODEL_ID_KANJI,
            ModeloNotas.DECK_ID_WORDS, ModeloNotas.DECK_ID_KANJI,
        )
        assertEquals(4, ids.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && ./gradlew testDebugUnitTest --tests '*ModeloNotasTest*'`
Expected: FAIL en compilación — el paquete `dominio.anki` y `ModeloNotas`/`NotaWords`/`NotaKanji` no existen todavía.

- [ ] **Step 3: Write minimal implementation**

Crear `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ModeloNotas.kt` completo:

```kotlin
package com.tatoh.dokushorenshu.dominio.anki

import java.security.MessageDigest

/** Nota del mazo "Dokusho — Words". Campos en el orden exacto de
 *  [ModeloNotas.CAMPOS_WORDS] (9): Palabra, Lectura, Significados, Tag (vacío para
 *  este mazo — el tag va en el mazo Kanji), Oracion1..5 (cap 5, historias primero,
 *  Tatoeba después — armado en `ArmadorMazos`, fuera de esta tarea). Palabra sin
 *  definición en el db llega igual acá con `significados` vacío (el spec nunca aborta
 *  el export por eso). */
data class NotaWords(
    val palabra: String,
    val lectura: String,
    val significados: String,
    val tag: String = "",
    val oraciones: List<String> = emptyList(),
) {
    init {
        require(oraciones.size <= 5) { "NotaWords: máximo 5 oraciones, llegaron ${oraciones.size}" }
    }

    /** Campos en el orden exacto del modelo Anki: Palabra, Lectura, Significados, Tag,
     *  Oracion1..5 — las oraciones faltantes quedan como campo vacío (nunca se
     *  reordena ni se comprime la lista, así el JS de rotación siempre sabe qué
     *  campo mira). */
    fun campos(): List<String> =
        listOf(palabra, lectura, significados, tag) + List(5) { i -> oraciones.getOrElse(i) { "" } }

    /** Clave estable para el GUID: solo el término. A propósito NO incluye
     *  significados/oraciones — si el diccionario o las oraciones de ejemplo cambian
     *  en un re-export, el guid no debe cambiar (spec: "re-export actualiza, no
     *  duplica"). */
    val claveGuid: String get() = "words:$palabra"
}

/** Nota del mazo "Dokusho — Kanji" (Kanji, OnYomi, KunYomi, Significados, Dificultad,
 *  Oracion1..5 — 10 campos). Solo entran acá kanjis TAGGEADOS (easy/medium/hard);
 *  tocados-sin-tag se excluyen antes de llegar (decisión de `ArmadorMazos`, fuera de
 *  esta tarea). */
data class NotaKanji(
    val kanji: String,
    val onYomi: String,
    val kunYomi: String,
    val significados: String,
    val dificultad: String,
    val oraciones: List<String> = emptyList(),
) {
    init {
        require(oraciones.size <= 5) { "NotaKanji: máximo 5 oraciones, llegaron ${oraciones.size}" }
    }

    fun campos(): List<String> =
        listOf(kanji, onYomi, kunYomi, significados, dificultad) +
            List(5) { i -> oraciones.getOrElse(i) { "" } }

    /** Clave estable para el GUID: solo el kanji (mismo criterio que [NotaWords.claveGuid]). */
    val claveGuid: String get() = "kanji:$kanji"
}

/** Constantes de modelo/mazo, GUID determinístico y templates HTML+CSS+JS del Plan 4a
 *  (mazos Anki). Schema/formato validados contra el código fuente de genanki
 *  (kerrickstaley/genanki, referencia Python) — ver
 *  `docs/superpowers/specs/2026-07-09-plan-4a-mazos-anki-design.md` para el detalle de
 *  la investigación. Los IDs son CONSTANTES FIJAS: si cambiaran entre ejecuciones,
 *  cada export crearía un modelo/mazo nuevo en vez de actualizar el existente. */
object ModeloNotas {
    // Estilo Anki: enteros de 13 dígitos (parecen timestamps epoch-ms), pero FIJOS
    // en el código — nunca se recalculan en tiempo de ejecución.
    const val MODEL_ID_WORDS: Long = 1720000000001L
    const val MODEL_ID_KANJI: Long = 1720000000002L
    const val DECK_ID_WORDS: Long = 1720000000101L
    const val DECK_ID_KANJI: Long = 1720000000102L

    const val NOMBRE_DECK_WORDS: String = "Dokusho — Words"
    const val NOMBRE_DECK_KANJI: String = "Dokusho — Kanji"
    const val NOMBRE_MODELO_WORDS: String = "Dokusho Words"
    const val NOMBRE_MODELO_KANJI: String = "Dokusho Kanji"

    val CAMPOS_WORDS: List<String> =
        listOf("Palabra", "Lectura", "Significados", "Tag", "Oracion1", "Oracion2", "Oracion3", "Oracion4", "Oracion5")
    val CAMPOS_KANJI: List<String> =
        listOf("Kanji", "OnYomi", "KunYomi", "Significados", "Dificultad", "Oracion1", "Oracion2", "Oracion3", "Oracion4", "Oracion5")

    // Alfabeto base91 EXACTO de Anki/genanki (91 símbolos, orden verbatim de
    // genanki/util.py BASE91_TABLE) — usado para convertir el hash del guid.
    private val TABLA_BASE91: List<Char> = listOf(
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's',
        't', 'u', 'v', 'w', 'x', 'y', 'z', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L',
        'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '0', '1', '2', '3', '4',
        '5', '6', '7', '8', '9', '!', '#', '$', '%', '&', '(', ')', '*', '+', ',', '-', '.', '/', ':',
        ';', '<', '=', '>', '?', '@', '[', ']', '^', '_', '`', '{', '|', '}', '~',
    )

    /** GUID determinístico estilo genanki (`guid_for` en genanki/util.py): SHA-256 de
     *  [clave] → primeros 8 bytes como entero SIN signo → convertido a la base91 de
     *  Anki. A diferencia de genanki (que hashea todos los campos de la nota),
     *  [clave] es solo el término/kanji — ver doc de [NotaWords.claveGuid]. */
    fun guidDe(clave: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(clave.toByteArray(Charsets.UTF_8))
        var n = 0UL
        for (i in 0 until 8) n = (n shl 8) or hash[i].toUByte().toULong()
        if (n == 0UL) return TABLA_BASE91[0].toString() // caso de borde, no debería darse con SHA-256 real
        val digitos = StringBuilder()
        var resto = n
        while (resto > 0UL) {
            digitos.append(TABLA_BASE91[(resto % 91UL).toInt()])
            resto /= 91UL
        }
        return digitos.reverse().toString()
    }

    // --- CSS estilo Kaishi: palabra/kanji grande, dark-friendly. Anki desktop marca
    // el modo noche con la clase `.night_mode` en <body> (no con
    // prefers-color-scheme); AnkiDroid respeta esa misma clase. Por eso el estilo
    // "oscuro" acá es el default (`.card`) y el claro es la EXCEPCIÓN
    // (`.night_mode` invertido no hace falta: ver comentario abajo). ---
    val CSS: String = """
        .card {
            font-family: "Noto Sans JP", "Hiragino Sans", "Yu Gothic", sans-serif;
            text-align: center;
            background-color: #1e1e1e;
            color: #f0f0f0;
        }
        .palabra, .kanji {
            font-size: 64px;
            font-weight: bold;
            margin: 24px 0 8px 0;
        }
        .lectura, .lecturas {
            font-size: 22px;
            color: #b0b0b0;
            margin-bottom: 10px;
        }
        .significados {
            font-size: 20px;
            margin: 10px 0;
        }
        .tag, .dificultad {
            display: inline-block;
            font-size: 14px;
            padding: 2px 10px;
            border-radius: 10px;
            background-color: #333333;
            color: #cccccc;
            margin-bottom: 10px;
        }
        hr#answer {
            margin: 18px auto;
            width: 60%;
            border: none;
            border-top: 1px solid #444444;
        }
        #oracion {
            font-size: 20px;
            line-height: 2;
            margin-top: 16px;
        }
        #oracion ruby rt {
            font-size: 11px;
            color: #999999;
            user-select: none;
        }
        #oracion .traduccion {
            display: block;
            font-size: 15px;
            color: #a0a0a0;
            margin-top: 4px;
        }
        /* Anki desktop en modo claro NO agrega clase al body (el modo oscuro sí
           agrega `.night_mode`) — por eso el override es ":not(.night_mode)" y no al
           revés. AnkiDroid respeta la misma convención de clase. */
        .card:not(.night_mode) {
            background-color: #ffffff;
            color: #111111;
        }
        .card:not(.night_mode) .lectura, .card:not(.night_mode) .lecturas {
            color: #555555;
        }
        .card:not(.night_mode) .tag, .card:not(.night_mode) .dificultad {
            background-color: #eeeeee;
            color: #333333;
        }
        .card:not(.night_mode) hr#answer {
            border-top-color: #cccccc;
        }
        .card:not(.night_mode) #oracion ruby rt, .card:not(.night_mode) #oracion .traduccion {
            color: #777777;
        }
    """.trimIndent()

    // --- Mecanismo de rotación (spec Plan 4a): Oracion1 se renderiza SIEMPRE en
    // #oracion (fallback garantizado sin JS); las 5 van también en divs ocultos; un
    // script al final junta las no-vacías y elige una al azar para reemplazar el
    // contenido de #oracion. El HTML de cada Oracion ya trae <ruby> embebida (armado
    // en ArmadorMazos, fuera de esta tarea) — el script mueve innerHTML tal cual, sin
    // tocar el marcado. ---
    private fun scriptRotacion(): String = """
        <div style="display:none">
            <div class="o1">{{Oracion1}}</div>
            <div class="o2">{{Oracion2}}</div>
            <div class="o3">{{Oracion3}}</div>
            <div class="o4">{{Oracion4}}</div>
            <div class="o5">{{Oracion5}}</div>
        </div>
        <script>
        (function() {
            var candidatas = [];
            var ocultos = document.querySelectorAll('.o1, .o2, .o3, .o4, .o5');
            for (var i = 0; i < ocultos.length; i++) {
                if (ocultos[i].innerHTML.trim() !== '') candidatas.push(ocultos[i].innerHTML);
            }
            if (candidatas.length > 0) {
                var elegido = candidatas[Math.floor(Math.random() * candidatas.length)];
                document.getElementById('oracion').innerHTML = elegido;
            }
        })();
        </script>
    """.trimIndent()

    val QFMT_WORDS: String = """<div class="palabra">{{Palabra}}</div>"""

    val AFMT_WORDS: String = """
        {{FrontSide}}
        <hr id="answer">
        <div class="lectura">{{Lectura}}</div>
        <div class="significados">{{Significados}}</div>
        {{#Tag}}<div class="tag">{{Tag}}</div>{{/Tag}}
        <div id="oracion">{{Oracion1}}</div>
        ${scriptRotacion()}
    """.trimIndent()

    val QFMT_KANJI: String = """<div class="kanji">{{Kanji}}</div>"""

    val AFMT_KANJI: String = """
        {{FrontSide}}
        <hr id="answer">
        <div class="lecturas"><span class="on">{{OnYomi}}</span> <span class="kun">{{KunYomi}}</span></div>
        <div class="significados">{{Significados}}</div>
        {{#Dificultad}}<div class="dificultad">[{{Dificultad}}]</div>{{/Dificultad}}
        <div id="oracion">{{Oracion1}}</div>
        ${scriptRotacion()}
    """.trimIndent()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && ./gradlew testDebugUnitTest --tests '*ModeloNotasTest*'`
Expected: BUILD SUCCESSFUL, todos los tests en verde. Confirmar suite completa:
`cd app && ./gradlew test`.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ModeloNotas.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/ModeloNotasTest.kt
git commit -m "$(cat <<'EOF'
feat(app): modelo de notas Anki (Words/Kanji) con GUID determinístico

Plan 4a: IDs de modelo/mazo fijos, NotaWords/NotaKanji con cap de 5
oraciones, guidDe() estilo genanki (SHA-256 + base91 sobre una clave
estable por término/kanji, no sobre los campos — así el re-export no
duplica aunque cambien significados/oraciones) y templates HTML+CSS+JS
con rotación aleatoria de Oracion1..5 (fallback a Oracion1 sin JS).
Schema/algoritmos validados contra el código fuente de genanki.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `dominio/anki/EscritorApkg.kt` — genera el .apkg (SQLite + zip)

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/EscritorApkg.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/EscritorApkgTest.kt`

**Interfaces:**
- Consumes: `ModeloNotas` completo (Task 1) — IDs, `CAMPOS_WORDS/KANJI`, `CSS`,
  `QFMT_*/AFMT_*`, `guidDe`; `NotaWords`/`NotaKanji` (`.campos()`).
- Produces: `EscritorApkg.escribir(destino: File, notasWords: List<NotaWords>, notasKanji: List<NotaKanji>): Unit`
  — crea `destino` (un `.apkg`, zip de `collection.anki2` + `media`). No conoce
  Room/Diccionario/HistoriasRepo (recibe las notas ya armadas).

- [ ] **Step 1: Write the failing test**

Crear `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/EscritorApkgTest.kt`:

```kotlin
package com.tatoh.dokushorenshu.dominio.anki

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
class EscritorApkgTest {

    private fun notaWords(palabra: String) = NotaWords(
        palabra = palabra, lectura = "よみ", significados = "significado",
        oraciones = listOf("<ruby>物<rt>もの</rt></ruby>語です。"),
    )

    private fun notaKanji(kanji: String) = NotaKanji(
        kanji = kanji, onYomi = "オン", kunYomi = "くん", significados = "significado",
        dificultad = "easy", oraciones = listOf("oración kanji"),
    )

    /** Extrae collection.anki2 del zip a un archivo temporal real (SQLite necesita ruta,
     *  mismo motivo que DiccionarioSqliteTest.abrirFixture). */
    private fun unzipCollection(apkg: File): File {
        val destino = File.createTempFile("collection", ".anki2")
        ZipFile(apkg).use { zip ->
            val entrada = zip.getEntry("collection.anki2")!!
            zip.getInputStream(entrada).use { entrada2 -> destino.outputStream().use { entrada2.copyTo(it) } }
        }
        return destino
    }

    @Test
    fun `apkg generado pasa integrity_check y tiene los counts correctos`() {
        val destino = File.createTempFile("mazo", ".apkg")
        EscritorApkg.escribir(destino, listOf(notaWords("物語"), notaWords("犬")), listOf(notaKanji("犬")))

        val sqlite = unzipCollection(destino)
        SQLiteDatabase.openDatabase(sqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ok", c.getString(0))
            }
            db.rawQuery("SELECT COUNT(*) FROM notes", null).use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM cards", null).use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM notes WHERE mid = ?", arrayOf(ModeloNotas.MODEL_ID_WORDS.toString())).use { c ->
                c.moveToFirst(); assertEquals(2, c.getInt(0))
            }
            db.rawQuery("SELECT COUNT(*) FROM notes WHERE mid = ?", arrayOf(ModeloNotas.MODEL_ID_KANJI.toString())).use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
        }
    }

    @Test
    fun `guid y flds estables entre dos corridas independientes`() {
        val notas = listOf(notaWords("物語"))
        val destino1 = File.createTempFile("mazo1", ".apkg")
        val destino2 = File.createTempFile("mazo2", ".apkg")
        EscritorApkg.escribir(destino1, notas, emptyList())
        EscritorApkg.escribir(destino2, notas, emptyList())

        fun guidYFlds(apkg: File): Pair<String, String> {
            val sqlite = unzipCollection(apkg)
            return SQLiteDatabase.openDatabase(sqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT guid, flds FROM notes", null).use { c ->
                    c.moveToFirst()
                    c.getString(0) to c.getString(1)
                }
            }
        }
        assertEquals(guidYFlds(destino1), guidYFlds(destino2))
    }

    @Test
    fun `media es exactamente el objeto json vacio`() {
        val destino = File.createTempFile("mazo", ".apkg")
        EscritorApkg.escribir(destino, listOf(notaWords("物語")), emptyList())
        ZipFile(destino).use { zip ->
            val texto = zip.getInputStream(zip.getEntry("media")!!).bufferedReader().readText()
            assertEquals("{}", texto)
        }
    }

    @Test
    fun `flds usa el separador 0x1f y sfld es el primer campo`() {
        val destino = File.createTempFile("mazo", ".apkg")
        EscritorApkg.escribir(destino, listOf(notaWords("物語")), emptyList())
        val sqlite = unzipCollection(destino)
        SQLiteDatabase.openDatabase(sqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT flds, sfld FROM notes LIMIT 1", null).use { c ->
                c.moveToFirst()
                val campos = c.getString(0).split("\u001f")
                assertEquals(9, campos.size) // CAMPOS_WORDS
                assertEquals("物語", campos[0])
                assertEquals("物語", c.getString(1))
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && ./gradlew testDebugUnitTest --tests '*EscritorApkgTest*'`
Expected: FAIL en compilación — `EscritorApkg` no existe todavía.

- [ ] **Step 3: Write minimal implementation**

Crear `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/EscritorApkg.kt` completo:

```kotlin
package com.tatoh.dokushorenshu.dominio.anki

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Genera un `.apkg` (zip de `collection.anki2` SQLite + `media`) a partir de listas
 *  de notas ya armadas por `ArmadorMazos` (fuera de esta tarea). Puro respecto a
 *  datos: no conoce Room ni el Diccionario, solo [NotaWords]/[NotaKanji] y
 *  [ModeloNotas]. Schema "Anki 2 legacy" (`ver = 11`), DDL y JSON del `col`
 *  verificados contra el código fuente de genanki — ver cabecera del draft de este
 *  plan para el detalle de la investigación. */
object EscritorApkg {

    // DDL EXACTO de genanki/apkg_schema.py (APKG_SCHEMA), una sentencia por elemento
    // porque SQLiteDatabase.execSQL no acepta scripts multi-sentencia (a diferencia
    // de sqlite3.executescript en Python).
    private val DDL: List<String> = listOf(
        """CREATE TABLE col (
            id integer primary key,
            crt integer not null,
            mod integer not null,
            scm integer not null,
            ver integer not null,
            dty integer not null,
            usn integer not null,
            ls integer not null,
            conf text not null,
            models text not null,
            decks text not null,
            dconf text not null,
            tags text not null
        )""",
        """CREATE TABLE notes (
            id integer primary key,
            guid text not null,
            mid integer not null,
            mod integer not null,
            usn integer not null,
            tags text not null,
            flds text not null,
            sfld integer not null,
            csum integer not null,
            flags integer not null,
            data text not null
        )""",
        """CREATE TABLE cards (
            id integer primary key,
            nid integer not null,
            did integer not null,
            ord integer not null,
            mod integer not null,
            usn integer not null,
            type integer not null,
            queue integer not null,
            due integer not null,
            ivl integer not null,
            factor integer not null,
            reps integer not null,
            lapses integer not null,
            left integer not null,
            odue integer not null,
            odid integer not null,
            flags integer not null,
            data text not null
        )""",
        """CREATE TABLE revlog (
            id integer primary key,
            cid integer not null,
            usn integer not null,
            ease integer not null,
            ivl integer not null,
            lastIvl integer not null,
            factor integer not null,
            time integer not null,
            type integer not null
        )""",
        """CREATE TABLE graves (
            usn integer not null,
            oid integer not null,
            type integer not null
        )""",
        "CREATE INDEX ix_notes_usn on notes (usn)",
        "CREATE INDEX ix_cards_usn on cards (usn)",
        "CREATE INDEX ix_revlog_usn on revlog (usn)",
        "CREATE INDEX ix_cards_nid on cards (nid)",
        "CREATE INDEX ix_cards_sched on cards (did, queue, due)",
        "CREATE INDEX ix_revlog_cid on revlog (cid)",
        "CREATE INDEX ix_notes_csum on notes (csum)",
    )

    fun escribir(destino: File, notasWords: List<NotaWords>, notasKanji: List<NotaKanji>) {
        val sqliteTemp = File.createTempFile("apkg_", ".sqlite", destino.parentFile)
        try {
            SQLiteDatabase.openOrCreateDatabase(sqliteTemp, null).use { db ->
                for (sentencia in DDL) db.execSQL(sentencia)

                val ahoraSegundos = System.currentTimeMillis() / 1000
                insertarCol(db, ahoraSegundos)

                var idGen = ahoraSegundos * 1000 // estilo genanki: contador base epoch-ms
                var due = 1L
                for (nota in notasWords) {
                    escribirNota(
                        db, idGen, idGen + 1, ModeloNotas.guidDe(nota.claveGuid),
                        ModeloNotas.MODEL_ID_WORDS, ahoraSegundos, ModeloNotas.DECK_ID_WORDS,
                        nota.campos(), due,
                    )
                    idGen += 2
                    due += 1
                }
                for (nota in notasKanji) {
                    escribirNota(
                        db, idGen, idGen + 1, ModeloNotas.guidDe(nota.claveGuid),
                        ModeloNotas.MODEL_ID_KANJI, ahoraSegundos, ModeloNotas.DECK_ID_KANJI,
                        nota.campos(), due,
                    )
                    idGen += 2
                    due += 1
                }
            }
            zipear(sqliteTemp, destino)
        } finally {
            sqliteTemp.delete()
        }
    }

    private fun escribirNota(
        db: SQLiteDatabase,
        idNota: Long,
        idCarta: Long,
        guid: String,
        mid: Long,
        modSegundos: Long,
        did: Long,
        campos: List<String>,
        due: Long,
    ) {
        val flds = campos.joinToString("\u001f")
        val sfld = campos[0]
        db.execSQL(
            "INSERT INTO notes(id, guid, mid, mod, usn, tags, flds, sfld, csum, flags, data)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(idNota, guid, mid, modSegundos, -1L, " ", flds, sfld, csumDe(sfld), 0L, ""),
        )
        db.execSQL(
            "INSERT INTO cards(id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, reps, lapses, left, odue, odid, flags, data)" +
                " VALUES (?, ?, ?, 0, ?, ?, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, '')",
            arrayOf<Any>(idCarta, idNota, did, modSegundos, -1L, due),
        )
    }

    /** Algoritmo real de Anki para `csum` (no genanki, que lo hardcodea a 0 — ver
     *  cabecera del draft): SHA-1 del primer campo, primeros 8 hex como entero.
     *  Alimenta `ix_notes_csum` (usado por Anki para "Find Duplicates"); no afecta
     *  el import ni el guid, que es lo que garantiza que el re-export no duplica. */
    private fun csumDe(primerCampo: String): Long {
        val hash = MessageDigest.getInstance("SHA-1").digest(primerCampo.toByteArray(Charsets.UTF_8))
        val hex = hash.joinToString("") { "%02x".format(it) }
        return hex.take(8).toLong(16)
    }

    private fun insertarCol(db: SQLiteDatabase, ahoraSegundos: Long) {
        db.execSQL(
            "INSERT INTO col(id, crt, mod, scm, ver, dty, usn, ls, conf, models, decks, dconf, tags)" +
                " VALUES (NULL, ?, ?, ?, 11, 0, 0, 0, ?, ?, ?, ?, '{}')",
            arrayOf<Any>(
                ahoraSegundos, ahoraSegundos, ahoraSegundos * 1000,
                confJson(ahoraSegundos).toString(),
                modelsJson(ahoraSegundos).toString(),
                decksJson(ahoraSegundos).toString(),
                dconfJson().toString(),
            ),
        )
    }

    // --- JSON del `col`, construido con kotlinx.serialization (ya dependencia del
    // proyecto). Estructura y claves EXACTAS de genanki (apkg_col.py APKG_COL,
    // model.py Model.to_json, deck.py Deck.to_json), adaptadas a nuestros IDs. ---

    private fun confJson(modSegundos: Long) = buildJsonObject {
        put("activeDecks", buildJsonArray { add(ModeloNotas.DECK_ID_WORDS); add(ModeloNotas.DECK_ID_KANJI) })
        put("addToCur", true)
        put("collapseTime", 1200)
        put("curDeck", ModeloNotas.DECK_ID_WORDS)
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

    private fun dconfJson() = buildJsonObject {
        put("1", buildJsonObject {
            put("autoplay", true)
            put("id", 1)
            put("lapse", buildJsonObject {
                put("delays", buildJsonArray { add(10) })
                put("leechAction", 0)
                put("leechFails", 8)
                put("minInt", 1)
                put("mult", 0)
            })
            put("maxTaken", 60)
            put("mod", 0)
            put("name", "Default")
            put("new", buildJsonObject {
                put("bury", true)
                put("delays", buildJsonArray { add(1); add(10) })
                put("initialFactor", 2500)
                put("ints", buildJsonArray { add(1); add(4); add(7) })
                put("order", 1)
                put("perDay", 20)
                put("separate", true)
            })
            put("replayq", true)
            put("rev", buildJsonObject {
                put("bury", true)
                put("ease4", 1.3)
                put("fuzz", 0.05)
                put("ivlFct", 1)
                put("maxIvl", 36500)
                put("minSpace", 1)
                put("perDay", 100)
            })
            put("timer", 0)
            put("usn", 0)
        })
    }

    private fun campoJson(nombre: String, orden: Int) = buildJsonObject {
        put("name", nombre)
        put("ord", orden)
        put("font", "Liberation Sans")
        put("media", buildJsonArray {})
        put("rtl", false)
        put("size", 20)
        put("sticky", false)
    }

    private fun templateJson(nombre: String, orden: Int, qfmt: String, afmt: String) = buildJsonObject {
        put("name", nombre)
        put("ord", orden)
        put("qfmt", qfmt)
        put("afmt", afmt)
        put("bafmt", "")
        put("bqfmt", "")
        put("bfont", "")
        put("bsize", 0)
        put("did", JsonNull)
    }

    private fun modeloJson(
        id: Long,
        nombre: String,
        campos: List<String>,
        deckId: Long,
        modSegundos: Long,
        qfmt: String,
        afmt: String,
    ) = buildJsonObject {
        put("css", ModeloNotas.CSS)
        put("did", deckId)
        put("flds", buildJsonArray { campos.forEachIndexed { i, c -> add(campoJson(c, i)) } })
        put("id", id.toString())
        put("latexPost", "\\end{document}")
        put(
            "latexPre",
            "\\documentclass[12pt]{article}\n\\special{papersize=3in,5in}\n\\usepackage[utf8]{inputenc}\n" +
                "\\usepackage{amssymb,amsmath}\n\\pagestyle{empty}\n\\setlength{\\parindent}{0in}\n\\begin{document}\n",
        )
        put("latexsvg", false)
        put("mod", modSegundos)
        put("name", nombre)
        // Único template, referencia solo el campo 0 (Palabra/Kanji) en el qfmt:
        // "required" = [[0, "all", [0]]] (misma lógica de genanki Model._req para
        // este caso simple de un solo campo obligatorio).
        put("req", buildJsonArray { add(buildJsonArray { add(0); add("all"); add(buildJsonArray { add(0) }) }) })
        put("sortf", 0)
        put("tags", buildJsonArray {})
        put("tmpls", buildJsonArray { add(templateJson("Card 1", 0, qfmt, afmt)) })
        put("type", 0) // FRONT_BACK
        put("usn", -1)
        put("vers", buildJsonArray {})
    }

    private fun modelsJson(modSegundos: Long) = buildJsonObject {
        put(
            ModeloNotas.MODEL_ID_WORDS.toString(),
            modeloJson(
                ModeloNotas.MODEL_ID_WORDS, ModeloNotas.NOMBRE_MODELO_WORDS, ModeloNotas.CAMPOS_WORDS,
                ModeloNotas.DECK_ID_WORDS, modSegundos, ModeloNotas.QFMT_WORDS, ModeloNotas.AFMT_WORDS,
            ),
        )
        put(
            ModeloNotas.MODEL_ID_KANJI.toString(),
            modeloJson(
                ModeloNotas.MODEL_ID_KANJI, ModeloNotas.NOMBRE_MODELO_KANJI, ModeloNotas.CAMPOS_KANJI,
                ModeloNotas.DECK_ID_KANJI, modSegundos, ModeloNotas.QFMT_KANJI, ModeloNotas.AFMT_KANJI,
            ),
        )
    }

    private fun deckJson(id: Long, nombre: String, modSegundos: Long) = buildJsonObject {
        put("collapsed", false)
        put("conf", 1)
        put("desc", "")
        put("dyn", 0)
        put("extendNew", 0)
        put("extendRev", 50)
        put("id", id)
        put("lrnToday", buildJsonArray { add(0); add(0) })
        put("mod", modSegundos)
        put("name", nombre)
        put("newToday", buildJsonArray { add(0); add(0) })
        put("revToday", buildJsonArray { add(0); add(0) })
        put("timeToday", buildJsonArray { add(0); add(0) })
        put("usn", -1)
    }

    private fun decksJson(modSegundos: Long) = buildJsonObject {
        put(ModeloNotas.DECK_ID_WORDS.toString(), deckJson(ModeloNotas.DECK_ID_WORDS, ModeloNotas.NOMBRE_DECK_WORDS, modSegundos))
        put(ModeloNotas.DECK_ID_KANJI.toString(), deckJson(ModeloNotas.DECK_ID_KANJI, ModeloNotas.NOMBRE_DECK_KANJI, modSegundos))
    }

    private fun zipear(sqliteFile: File, destino: File) {
        ZipOutputStream(destino.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("collection.anki2"))
            sqliteFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("media"))
            zip.write("{}".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && ./gradlew testDebugUnitTest --tests '*EscritorApkgTest*'`
Expected: BUILD SUCCESSFUL, todos los tests en verde. Confirmar suite completa:
`cd app && ./gradlew test`.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/EscritorApkg.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/EscritorApkgTest.kt
git commit -m "$(cat <<'EOF'
feat(app): EscritorApkg genera el .apkg (SQLite Anki 2 legacy + zip)

Plan 4a: escribir() crea collection.anki2 (DDL exacto de genanki:
col/notes/cards/revlog/graves + 7 índices, ver=11), inserta la fila col
con conf/models/decks/dconf (IDs fijos de ModeloNotas), una nota+carta
por elemento de NotaWords/NotaKanji (flds con separador 0x1f, csum sha1
real del primer campo, guid vía ModeloNotas.guidDe) y zipea junto a un
media = "{}" (sin assets). Robolectric verifica integrity_check, counts
por modelo, guid/flds estables entre corridas y el contenido de media.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```
## Plan 4a — Tasks 3-5 (armado de mazos, VM/UI de export, share+FileProvider) — draft

> Draft parcial: cubre las tareas 3, 4 y 5 del spec
> `docs/superpowers/specs/2026-07-09-plan-4a-mazos-anki-design.md`. Las tareas 1-2
> (`dominio/anki/ModeloNotas.kt` y `dominio/anki/EscritorApkg.kt`) las arma otro
> drafter en paralelo; acá se **consumen como contrato dado** (ver sección
> "Contrato asumido de Tasks 1-2" más abajo — es la única pieza que puede
> requerir ajuste fino al ensamblar el plan final). Tareas numeradas 3, 4, 5 acá
> (siguen la numeración final del plan).
>
> **Para agentic workers:** REQUIRED SUB-SKILL: usar
> `superpowers:subagent-driven-development` (recomendado) o
> `superpowers:executing-plans` para ejecutar tarea por tarea.

**Goal (de estas 3 tareas):** Juntar los datos ya persistidos (`palabras_tocadas`,
`kanjis_tocados` taggeados, diccionario offline, historias locales) en las notas
Anki (Task 3); exponer un flujo de export en la UI con progreso/errores y counts
(Task 4); completar el circuito real con share intent hacia AnkiDroid vía
FileProvider (Task 5).

**Architecture:** Extiende, byte-a-byte, las estructuras reales del código
post-Plan 3.7: `ProgresoDao`/`PalabraTocada`/`KanjiTocado` (Room,
`datos/progreso/ProgresoDb.kt`), `Diccionario` (`buscarPalabra`/`buscarPorLectura`
con fallback ya usado por `BuscadorPalabras`, `buscarKanji`, `oracionesDePalabra`/
`oracionesDeKanji` con Tatoeba), `HistoriasRepo.historiasLocales()` (síncrona,
escanea assets + descargadas), `Historia`/`Parrafo`/`Oracion`/`Furigana`
(fin-exclusivo, `datos/ModelosHistoria.kt`). Patrón de ViewModel establecido
(`BibliotecaViewModel`, `DetalleKanjiViewModel`): `ioDispatcher` inyectable,
`StateFlow` de estado, `LaunchedEffect(Unit) { vm.cargar() }` en la Screen,
`NavHost` de `MainActivity` + `Contenedor` de `App.kt` para DI manual.

**Tech Stack:** Kotlin, Jetpack Compose M3, Room 2.8.4, kotlinx.serialization,
JUnit4 + Robolectric, AGP 9.2 (Kotlin built-in, sin `kotlinOptions`), FileProvider
(`androidx.core`, dependencia nueva — ver Task 5).

## Contrato asumido de Tasks 1-2

`dominio/anki/ModeloNotas.kt` (otro drafter) declara, según el brief compartido:

```kotlin
data class NotaWords(
    val palabra: String,
    val lectura: String,
    val significados: String,   // ya formateado ("wash; laundry")
    val tag: String = "",
    val oraciones: List<String> = emptyList(),  // máx 5, HTML ya formateado (ruby o "jp<br>en")
)
data class NotaKanji(
    val kanji: String,
    val onYomi: String,         // ya formateado ("セン、あら.う" se une con 、)
    val kunYomi: String,
    val significados: String,   // ya formateado ("wash; inquire into")
    val dificultad: String,
    val oraciones: List<String> = emptyList(),
)
fun guidDe(clave: String): String
```

`dominio/anki/EscritorApkg.kt`:
```kotlin
object EscritorApkg {
    fun escribir(destino: File, notasWords: List<NotaWords>, notasKanji: List<NotaKanji>)
}
```

Se asumió `onYomi`/`kunYomi`/`significados` como `List<String>` (mismo tipo que
`KanjiInfo`/`Palabra` en `Diccionario.kt`) para que `ArmadorMazos` no haga
formato de texto (eso es trabajo de `ModeloNotas`, que sabe de templates HTML) —
ver "Arquitectura" del spec: *"ModeloNotas.kt — ... formato furigana Anki"* vs
*"ArmadorMazos.kt — junta los datos ... arma las notas"*. Si Tasks 1-2 terminan
con `String` (ya unidos) en vez de `List<String>`, Task 3 solo cambia 2 líneas
(`info.onYomi` → `info.onYomi.joinToString("、")`, etc.) — el resto del task no
se ve afectado. `EscritorApkg` se asumió `object` (stateless); si termina siendo
una `class`, Task 4 solo cambia el default del parámetro `escribir` (ver ahí).
**Marcar esto en la revisión de ensamblado.**

## Global Constraints (del spec, aplican a toda tarea)

- Código/comentarios en español. Strings de UI en inglés.
- Patrón `ioDispatcher: CoroutineDispatcher = Dispatchers.IO` inyectable en todo
  ViewModel/clase que toque Room/diccionario/archivos — nunca I/O en el hilo
  principal ni en composición.
- `HistoriasRepo.historiasLocales()`/`cargarHistoria()` son síncronas (bloqueantes):
  siempre se llaman dentro de `withContext(ioDispatcher)` (lección de Plan 3
  Task 8, verificada en Tasks 9/10).
- Fakes compartidos: `datos/Fakes.kt` (`DiccionarioFake`) y
  `datos/progreso/Fakes.kt` (`ProgresoDaoFake`) — extender, no duplicar. Cualquier
  método nuevo en `ProgresoDao` exige la implementación correspondiente en
  `ProgresoDaoFake` en el MISMO commit (si no, ningún test del proyecto compila).
- AGP 9.2 con Kotlin built-in: nunca usar el bloque `kotlinOptions {}`.
- Palabra sin definición en el db → nota con lectura sola, **nunca aborta el
  export**. Kanji taggeado que ya no está en el db → se omite con contador,
  nunca aborta.
- Antes de cada commit: `cd app && ./gradlew test` verde + `./gradlew assembleDebug`
  sin errores. Suite base: **88** (confirmado `grep -c @Test` sobre
  `app/src/test` en `main` al momento de este draft).
- No placeholders: cada step lleva código completo, comandos exactos.

---

### Task 3: `ArmadorMazos` — juntar datos en notas + ruby HTML de las oraciones

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazos.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/progreso/ProgresoDb.kt`
  (2 queries nuevas en `ProgresoDao`)
- Modify: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/progreso/Fakes.kt`
  (implementar las 2 queries nuevas en `ProgresoDaoFake`)
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/progreso/ProgresoDaoTest.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazosTest.kt` (nuevo)
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/OracionARubyHtmlTest.kt` (nuevo)

**Interfaces:**
- Consumes: `ProgresoDao` (`Room`, `datos/progreso/ProgresoDb.kt`); `Diccionario`
  (`buscarPalabra`, `buscarPorLectura`, `buscarKanji`, `oracionesDePalabra`,
  `oracionesDeKanji` — `datos/Diccionario.kt`); `HistoriasRepo.historiasLocales()`
  (`datos/HistoriasRepo.kt`); `Historia`/`Parrafo`/`Oracion`/`Furigana`
  (`datos/ModelosHistoria.kt`); `PalabraTocada(idHistoria, termino, timestamp)`,
  `KanjiTocado(kanji, dificultad, timestamp)`; `NotaWords`/`NotaKanji` (Tasks 1-2,
  ver "Contrato asumido" arriba).
- Produces:
  - `ProgresoDao.todasPalabras(): List<PalabraTocada>` — `SELECT * FROM palabras_tocadas`
    (todas las historias, sin filtrar; el dedupe por término lo hace el caller).
  - `ProgresoDao.kanjisTaggeados(): List<KanjiTocado>` — `SELECT * FROM kanjis_tocados WHERE dificultad IS NOT NULL`.
  - `internal fun oracionARubyHtml(oracion: Oracion): String` — pura, en
    `dominio/anki/ArmadorMazos.kt`.
  - `data class ResultadoArmado(val notasWords: List<NotaWords>, val notasKanji: List<NotaKanji>, val kanjisOmitidos: Int)`.
  - `class ArmadorMazos(progresoDao: ProgresoDao, diccionario: Diccionario, historiasRepo: HistoriasRepo)`
    con `suspend fun armar(): ResultadoArmado`, `suspend fun armarWords(historias: List<Historia> = historiasRepo.historiasLocales()): List<NotaWords>`,
    `suspend fun armarKanji(historias: List<Historia> = historiasRepo.historiasLocales()): Pair<List<NotaKanji>, Int>`
    (notas, omitidos) — usado por `ExportViewModel` (Task 4).

- [ ] **Step 1: Write the failing tests**

`ProgresoDaoTest.kt` — agregar estos 2 tests al final de la clase (antes del
`}` final), después del test `reabrir un kanji taggeado preserva...`:

```kotlin
    @Test
    fun `todasPalabras devuelve las filas de todas las historias sin filtrar`() = runTest {
        val dao = db().dao()
        dao.registrarPalabra(PalabraTocada("momotaro", "犬", timestamp = 1L))
        dao.registrarPalabra(PalabraTocada("urashima_taro", "亀", timestamp = 2L))
        assertEquals(2, dao.todasPalabras().size)
    }

    @Test
    fun `kanjisTaggeados excluye los vistos sin dificultad`() = runTest {
        val dao = db().dao()
        dao.registrarAperturaKanji("見", 1L)  // visto, nunca taggeado
        dao.registrarAperturaKanji("洗", 2L)
        dao.setDificultadKanji("洗", "hard")
        val taggeados = dao.kanjisTaggeados()
        assertEquals(1, taggeados.size)
        assertEquals("洗", taggeados[0].kanji)
    }
```

`OracionARubyHtmlTest.kt` (nuevo — pura, sin fakes, usa spans reales copiados a
mano de `momotaro.json` para 2 de los 6 casos):

```kotlin
package com.tatoh.dokushorenshu.dominio.anki

import com.tatoh.dokushorenshu.datos.Furigana
import com.tatoh.dokushorenshu.datos.Oracion
import org.junit.Assert.*
import org.junit.Test

class OracionARubyHtmlTest {

    @Test
    fun `span de furigana al inicio de la oracion (fixture real momotaro)`() {
        // texto y furigana copiados literales de app/src/test/resources/momotaro.json
        val oracion = Oracion(
            "桃太郎はどこか外国へ出かけて、腕いっぱい、力だめしをしてみたくなりました。",
            listOf(Furigana(0, 3, "ももたろう")),
        )
        assertEquals(
            "<ruby>桃太郎<rt>ももたろう</rt></ruby>はどこか外国へ出かけて、腕いっぱい、力だめしをしてみたくなりました。",
            oracionARubyHtml(oracion),
        )
    }

    @Test
    fun `span en medio y span cerca del final combinados (fixture real momotaro)`() {
        // "まいにち、おじいさんは山へしば刈りに、おばあさんは川へ洗濯に行きました。"
        // furigana real: 刈→か [15,16], 洗濯→せんたく [27,29]
        val oracion = Oracion(
            "まいにち、おじいさんは山へしば刈りに、おばあさんは川へ洗濯に行きました。",
            listOf(Furigana(15, 16, "か"), Furigana(27, 29, "せんたく")),
        )
        assertEquals(
            "まいにち、おじいさんは山へしば<ruby>刈<rt>か</rt></ruby>りに、おばあさんは川へ" +
                "<ruby>洗濯<rt>せんたく</rt></ruby>に行きました。",
            oracionARubyHtml(oracion),
        )
    }

    @Test
    fun `oracion sin furigana devuelve el texto plano (fixture real momotaro)`() {
        val oracion = Oracion("むかし、むかし、あるところに、おじいさんとおばあさんがありました。", emptyList())
        assertEquals(oracion.texto, oracionARubyHtml(oracion))
    }

    @Test
    fun `escapa caracteres HTML en el texto`() {
        val oracion = Oracion("5<10 & mas texto", emptyList())
        assertEquals("5&lt;10 &amp; mas texto", oracionARubyHtml(oracion))
    }

    @Test
    fun `span que llega exactamente al final de la oracion`() {
        val oracion = Oracion("ここに犬", listOf(Furigana(3, 4, "いぬ")))
        assertEquals("ここに<ruby>犬<rt>いぬ</rt></ruby>", oracionARubyHtml(oracion))
    }

    @Test
    fun `spans solapados se resuelven ignorando el segundo (defensivo)`() {
        // bug de datos conocido (ledger Plan 3.6: momotaro.json trajo furigana
        // solapada en algún momento) — nunca debe lanzar, ignora el span que
        // arranca antes de que termine el anterior.
        val oracion = Oracion("かえる", listOf(Furigana(0, 2, "かえ"), Furigana(1, 3, "える")))
        assertEquals("<ruby>かえ<rt>かえ</rt></ruby>る", oracionARubyHtml(oracion))
    }
}
```

`ArmadorMazosTest.kt` (nuevo):

```kotlin
package com.tatoh.dokushorenshu.dominio.anki

import com.tatoh.dokushorenshu.datos.DiccionarioFake
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.OracionEjemplo
import com.tatoh.dokushorenshu.datos.Palabra
import com.tatoh.dokushorenshu.datos.progreso.KanjiTocado
import com.tatoh.dokushorenshu.datos.progreso.PalabraTocada
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDaoFake
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ArmadorMazosTest {
    private val momotaroJson =
        javaClass.classLoader!!.getResourceAsStream("momotaro.json")!!.readBytes().decodeToString()

    private fun historiasRepo(): HistoriasRepo = HistoriasRepo(
        leerAsset = { nombre -> if (nombre == "historias/momotaro.json") momotaroJson else null },
        listarAssetsHistorias = { listOf("momotaro.json") },
        dirDescargas = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
    )

    private fun armador(
        dao: ProgresoDaoFake = ProgresoDaoFake(),
        diccionario: DiccionarioFake = DiccionarioFake(),
    ) = ArmadorMazos(dao, diccionario, historiasRepo())

    // --- armarWords: enriquecido con Diccionario ---

    @Test
    fun `palabra con definicion en el diccionario usa lectura y significados reales`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "犬", timestamp = 1L))
        val diccionario = DiccionarioFake().apply {
            palabras["犬"] = listOf(Palabra("犬", "いぬ", listOf("dog"), emptyList(), popularidad = 10))
        }
        val notas = armador(dao, diccionario).armarWords()
        assertEquals(1, notas.size)
        assertEquals("犬", notas[0].palabra)
        assertEquals("いぬ", notas[0].lectura)
        assertEquals("dog", notas[0].significados)
        assertEquals("", notas[0].tag)  // campo reservado, spec: Nota Words sin tag propio
    }

    @Test
    fun `termino en kana puro sin entrada propia cae al fallback por lectura`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "おじいさん", timestamp = 1L))
        val diccionario = DiccionarioFake().apply {
            // entrada indexada por 祖父 pero con lectura おじいさん — buscarPalabra("おじいさん")
            // da vacío, buscarPorLectura("おじいさん") la encuentra (mismo fallback que BuscadorPalabras).
            palabras["祖父"] = listOf(Palabra("祖父", "おじいさん", listOf("grandfather"), emptyList(), popularidad = 5))
        }
        val notas = armador(dao, diccionario).armarWords()
        assertEquals(1, notas.size)
        assertEquals("おじいさん", notas[0].lectura)
        assertEquals("grandfather", notas[0].significados)
    }

    @Test
    fun `palabra sin ninguna entrada da nota con lectura sola, nunca falla`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "未知語", timestamp = 1L))
        val notas = armador(dao, DiccionarioFake()).armarWords()
        assertEquals(1, notas.size)
        assertEquals("未知語", notas[0].palabra)
        assertEquals("未知語", notas[0].lectura)
        assertEquals("", notas[0].significados)
    }

    @Test
    fun `mismo termino tocado en dos historias da una sola nota`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "犬", timestamp = 1L))
        dao.registrarPalabra(PalabraTocada("urashima_taro", "犬", timestamp = 2L))
        val notas = armador(dao).armarWords()
        assertEquals(1, notas.size)
    }

    // --- armarKanji: solo taggeados, skip defensivo de los que salieron del db ---

    @Test
    fun `kanji visto pero sin tag no entra al mazo de kanji`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarAperturaKanji("見", 1L)  // visto, dificultad null
        val (notas, omitidos) = armador(dao).armarKanji()
        assertTrue(notas.isEmpty())
        assertEquals(0, omitidos)
    }

    @Test
    fun `kanji taggeado que ya no esta en el db se omite y cuenta`() = runTest {
        val dao = ProgresoDaoFake()
        dao.insertarKanjiSiNoExiste(KanjiTocado("洗", "hard", 1L))
        val (notas, omitidos) = armador(dao, DiccionarioFake()).armarKanji()  // dict fake vacío
        assertTrue(notas.isEmpty())
        assertEquals(1, omitidos)
    }

    @Test
    fun `kanji taggeado presente en el db arma la nota con sus lecturas`() = runTest {
        val dao = ProgresoDaoFake()
        dao.insertarKanjiSiNoExiste(KanjiTocado("洗", "hard", 1L))
        val diccionario = DiccionarioFake().apply {
            kanjis["洗"] = KanjiInfo("洗", listOf("wash"), listOf("セン"), listOf("あら.う"), jlpt = 3, strokes = 9)
        }
        val (notas, omitidos) = armador(dao, diccionario).armarKanji()
        assertEquals(1, notas.size)
        assertEquals(0, omitidos)
        assertEquals("洗", notas[0].kanji)
        assertEquals("セン", notas[0].onYomi)
        assertEquals("あら.う", notas[0].kunYomi)
        assertEquals("hard", notas[0].dificultad)
    }

    // --- oraciones: prioridad historias > Tatoeba, cap 5 ---

    @Test
    fun `historias no alcanzan el cap - Tatoeba rellena el resto`() = runTest {
        // "洗濯" aparece 3 veces en momotaro.json (fixture real) — se completa con
        // 2 oraciones de Tatoeba (fake) hasta llegar a 5.
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "洗濯", timestamp = 1L))
        val diccionario = DiccionarioFake().apply {
            ejemplosPalabra["洗濯"] = listOf(
                OracionEjemplo("洗濯物を干す。", "Hang out the laundry."),
                OracionEjemplo("洗濯機が壊れた。", "The washing machine broke."),
                OracionEjemplo("これは使われない。", "This one is not used (excede el cap)."),
            )
        }
        val notas = armador(dao, diccionario).armarWords()
        val oraciones = notas.single { it.palabra == "洗濯" }.oraciones
        assertEquals(5, oraciones.size)
        assertEquals(3, oraciones.count { !it.contains("<br>") })  // de las historias, ruby sin <br>
        assertEquals(2, oraciones.count { it.contains("<br>") })   // relleno Tatoeba, "jp<br>en"
        // prioridad: las 3 primeras son de historias, las 2 últimas de Tatoeba
        assertTrue(oraciones.take(3).none { it.contains("<br>") })
        assertTrue(oraciones.takeLast(2).all { it.contains("<br>") })
    }

    @Test
    fun `cap de 5 oraciones aunque las historias tengan muchas mas coincidencias`() = runTest {
        // "桃太郎" aparece 34 veces en momotaro.json — nunca se llama a Tatoeba.
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "桃太郎", timestamp = 1L))
        val notas = armador(dao).armarWords()
        val oraciones = notas.single { it.palabra == "桃太郎" }.oraciones
        assertEquals(5, oraciones.size)
        assertTrue(oraciones.none { it.contains("<br>") })
    }

    // --- armar(): combina ambos mazos ---

    @Test
    fun `armar combina notasWords y notasKanji con el contador de omitidos`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "犬", timestamp = 1L))
        dao.insertarKanjiSiNoExiste(KanjiTocado("見", "easy", 1L))  // fuera del dict fake -> omitido
        val resultado = armador(dao, DiccionarioFake()).armar()
        assertEquals(1, resultado.notasWords.size)
        assertTrue(resultado.notasKanji.isEmpty())
        assertEquals(1, resultado.kanjisOmitidos)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && ./gradlew testDebugUnitTest --tests '*ProgresoDaoTest*' --tests '*ArmadorMazosTest*' --tests '*OracionARubyHtmlTest*'`
Expected: FAIL — no compila (`ProgresoDao.todasPalabras`/`.kanjisTaggeados` no
existen; `com.tatoh.dokushorenshu.dominio.anki.ArmadorMazos`/`oracionARubyHtml`
no existen). `OracionARubyHtmlTest` también falla en compilación porque
`ArmadorMazos.kt` (donde vive `oracionARubyHtml`) todavía no existe.

- [ ] **Step 3: Write minimal implementation**

`ProgresoDb.kt` — agregar las 2 queries a la interfaz `ProgresoDao` (después de
`kanjisPorDificultad`, sin tocar el resto del archivo):

```kotlin
    @Query("SELECT * FROM kanjis_tocados WHERE dificultad = :dificultad ORDER BY timestamp ASC")
    suspend fun kanjisPorDificultad(dificultad: String): List<KanjiTocado>

    /** Todas las filas, de todas las historias — el dedupe por término (una
     *  palabra puede tocarse en más de una historia) lo hace el caller
     *  (`ArmadorMazos`, Plan 4a). */
    @Query("SELECT * FROM palabras_tocadas")
    suspend fun todasPalabras(): List<PalabraTocada>

    /** Solo kanjis taggeados (easy/medium/hard) — insumo del mazo de kanji
     *  (Plan 4a): los vistos-sin-tag son ruido de consulta, spec explícito. */
    @Query("SELECT * FROM kanjis_tocados WHERE dificultad IS NOT NULL")
    suspend fun kanjisTaggeados(): List<KanjiTocado>
}
```

`Fakes.kt` (`datos/progreso/Fakes.kt`) — agregar al final de la clase
`ProgresoDaoFake` (antes del `}` final):

```kotlin
    override suspend fun todasPalabras(): List<PalabraTocada> = palabras.toList()

    override suspend fun kanjisTaggeados(): List<KanjiTocado> =
        kanjisTocados.values.filter { it.dificultad != null }
}
```

`ArmadorMazos.kt` (nuevo, completo):

```kotlin
package com.tatoh.dokushorenshu.dominio.anki

import com.tatoh.dokushorenshu.datos.Diccionario
import com.tatoh.dokushorenshu.datos.Historia
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.Oracion
import com.tatoh.dokushorenshu.datos.OracionEjemplo
import com.tatoh.dokushorenshu.datos.progreso.KanjiTocado
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDao

/** Cap de oraciones por nota (spec Plan 4a): historias locales primero,
 *  Tatoeba rellena el resto hasta llegar acá. */
private const val CAP_ORACIONES = 5

/** Resultado combinado de armar los dos mazos. `kanjisOmitidos` cuenta kanjis
 *  taggeados que ya no están en el diccionario (release nuevo, entrada
 *  movida/borrada) — el export nunca aborta por esto, solo informa (spec
 *  "Manejo de errores": "exported N, skipped M"). */
data class ResultadoArmado(
    val notasWords: List<NotaWords>,
    val notasKanji: List<NotaKanji>,
    val kanjisOmitidos: Int,
)

/** Junta los datos ya persistidos (Room + diccionario offline + historias
 *  locales) en las notas que consume `EscritorApkg`. Sin conocimiento de Anki:
 *  solo arma modelos de dominio — `ModeloNotas.kt` decide templates/formato de
 *  campo (Tasks 1-2). */
class ArmadorMazos(
    private val progresoDao: ProgresoDao,
    private val diccionario: Diccionario,
    private val historiasRepo: HistoriasRepo,
) {
    /** Arma ambos mazos leyendo las historias locales una sola vez (evita I/O
     *  duplicado: `historiasLocales()` re-lee todos los JSON de assets/filesDir
     *  en cada llamada). */
    suspend fun armar(): ResultadoArmado {
        val historias = historiasRepo.historiasLocales()
        val notasWords = armarWords(historias)
        val (notasKanji, omitidos) = armarKanji(historias)
        return ResultadoArmado(notasWords, notasKanji, omitidos)
    }

    /** Una nota por término único tocado — `palabras_tocadas` tiene primary key
     *  (idHistoria, termino), la misma palabra puede repetirse en varias
     *  historias y no debe duplicar nota. */
    suspend fun armarWords(historias: List<Historia> = historiasRepo.historiasLocales()): List<NotaWords> {
        val terminos = progresoDao.todasPalabras().map { it.termino }.distinct()
        return terminos.map { termino -> armarNotaWords(termino, historias) }
    }

    /** Solo kanjis taggeados (dificultad != null); uno fuera del db se salta y
     *  cuenta en el segundo componente del `Pair` — nunca aborta el export. */
    suspend fun armarKanji(
        historias: List<Historia> = historiasRepo.historiasLocales(),
    ): Pair<List<NotaKanji>, Int> {
        var omitidos = 0
        val notas = progresoDao.kanjisTaggeados().mapNotNull { tocado ->
            val info = diccionario.buscarKanji(tocado.kanji)
            if (info == null) {
                omitidos++
                null
            } else {
                armarNotaKanji(tocado, info, historias)
            }
        }
        return notas to omitidos
    }

    private fun armarNotaWords(termino: String, historias: List<Historia>): NotaWords {
        // buscarPalabra por superficie; tokens en kana puro sin entrada propia
        // (p.ej. おじいさん) caen al índice de lectura — mismo fallback que
        // BuscadorPalabras (Plan 3.5 Frente C).
        val palabra = diccionario.buscarPalabra(termino).firstOrNull()
            ?: diccionario.buscarPorLectura(termino).firstOrNull()
        return NotaWords(
            palabra = termino,
            lectura = palabra?.lectura ?: termino,
            significados = palabra?.significados?.joinToString("; ") ?: "",
            tag = "",  // campo reservado vacío — spec: "Nota Words ... Tag (vacío)"
            oraciones = armarOraciones(historias, termino) { limite ->
                diccionario.oracionesDePalabra(termino, limite)
            },
        )
    }

    private fun armarNotaKanji(tocado: KanjiTocado, info: KanjiInfo, historias: List<Historia>): NotaKanji =
        NotaKanji(
            kanji = tocado.kanji,
            // ModeloNotas espera strings ya formateados (contrato de Task 1)
            onYomi = info.onYomi.joinToString("、"),
            kunYomi = info.kunYomi.joinToString("、"),
            significados = info.significados.joinToString("; "),
            dificultad = requireNotNull(tocado.dificultad) {
                "kanjisTaggeados() no debería traer dificultad null"
            },
            oraciones = armarOraciones(historias, tocado.kanji) { limite ->
                diccionario.oracionesDeKanji(tocado.kanji, limite)
            },
        )

    /** Prioridad historias > Tatoeba, cap 5. Las oraciones de historias no
     *  llevan traducción (las historias no traducen); las de Tatoeba sí,
     *  formato `"oración<br>traducción"`. */
    private fun armarOraciones(
        historias: List<Historia>,
        termino: String,
        tatoeba: (limite: Int) -> List<OracionEjemplo>,
    ): List<String> {
        val deHistorias = historias.asSequence()
            .flatMap { it.parrafos.asSequence() }
            .flatMap { it.oraciones.asSequence() }
            .filter { it.texto.contains(termino) }
            .map { oracionARubyHtml(it) }
            .take(CAP_ORACIONES)
            .toList()
        if (deHistorias.size >= CAP_ORACIONES) return deHistorias
        val relleno = tatoeba(CAP_ORACIONES - deHistorias.size)
            .map { "${it.japones}<br>${it.ingles}" }
        return deHistorias + relleno
    }
}

/** Convierte una oración con spans de furigana fin-exclusivo a HTML con
 *  `<ruby>` (formato que Anki/AnkiDroid renderiza en cualquier cliente, a
 *  diferencia del filtro `{{furigana:}}` que depende del parsing de
 *  corchetes). Pura, sin dependencias de Android — testeable en JVM plano. */
internal fun oracionARubyHtml(oracion: Oracion): String {
    val texto = oracion.texto
    val sb = StringBuilder()
    var cursor = 0
    for (f in oracion.furigana.sortedBy { it.inicio }) {
        // defensivo: spans solapados son un bug de datos conocido (ledger Plan
        // 3.6 — momotaro.json llegó a traer furigana solapada); se ignora el
        // segundo span en vez de lanzar con un rango de substring inválido.
        if (f.inicio < cursor) continue
        if (f.inicio > cursor) sb.append(escapeHtml(texto.substring(cursor, f.inicio)))
        sb.append("<ruby>").append(escapeHtml(texto.substring(f.inicio, f.fin)))
            .append("<rt>").append(escapeHtml(f.lectura)).append("</rt></ruby>")
        cursor = f.fin
    }
    if (cursor < texto.length) sb.append(escapeHtml(texto.substring(cursor)))
    return sb.toString()
}

private fun escapeHtml(texto: String): String =
    texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && ./gradlew testDebugUnitTest --tests '*ProgresoDaoTest*' --tests '*ArmadorMazosTest*' --tests '*OracionARubyHtmlTest*'`
Expected: BUILD SUCCESSFUL, los 17 tests nuevos (2 + 9 + 6) en verde. Confirmar
la suite completa: `cd app && ./gradlew test` → 88 + 17 = **105** en verde.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazos.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/progreso/ProgresoDb.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/progreso/Fakes.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/progreso/ProgresoDaoTest.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazosTest.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/OracionARubyHtmlTest.kt
git commit -m "$(cat <<'EOF'
feat(app): ArmadorMazos junta palabras/kanjis tocados en notas Anki con ruby HTML

Dos queries nuevas en ProgresoDao (todasPalabras, kanjisTaggeados) alimentan
ArmadorMazos: armarWords() enriquece cada término único tocado contra el
diccionario (buscarPalabra→buscarPorLectura de fallback, nunca falla sin
definición); armarKanji() solo taggeados, salta con contador los que salieron
del db. Oraciones de ejemplo: escaneo de historias locales con prioridad sobre
Tatoeba, cap 5 por nota. oracionARubyHtml() convierte los spans fin-exclusivo
de furigana a <ruby> HTML (defensivo contra spans solapados, escapa HTML del
texto plano).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `ExportViewModel` + `ExportScreen` + wiring en Biblioteca/NavHost

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportViewModel.kt`
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportScreen.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaScreen.kt`
  (botón "Export" en el top bar)
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt`
  (ruta `"export"` + `onExport` de Biblioteca)
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/App.kt`
  (`Contenedor.armadorMazos`, `Contenedor.dirExportMazos`)
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/ui/export/ExportViewModelTest.kt` (nuevo)

**Interfaces:**
- Consumes: `ArmadorMazos` (Task 3), `EscritorApkg.escribir` (Tasks 1-2, ver
  "Contrato asumido" — inyectado como función para no acoplar el VM a que sea
  `object` vs `class`), `ProgresoDao.todasPalabras()`/`.kanjisTaggeados()`
  (Task 3, para los counts).
- Produces:
  - `enum class TipoExport { WORDS, KANJI }`.
  - `data class ContadoresExport(val words: Int, val kanjisTaggeados: Int)`.
  - `sealed interface EstadoExport { Idle, Generando, Listo(archivo: File, resumen: String), Error(mensaje: String) }`.
  - `class ExportViewModel(progresoDao, armadorMazos, dirExport: File, escribir: (File, List<NotaWords>, List<NotaKanji>) -> Unit = EscritorApkg::escribir, ioDispatcher = Dispatchers.IO)`
    con `fun cargar()` (llena `contadores`) y `fun exportar(tipo: TipoExport)`.
  - `ExportScreen(vm, onCerrar)` — counts, 2 botones (deshabilitados + hint si
    count 0), progreso, snackbar de error. El botón "Share" del estado `Listo`
    queda con `onClick = {}` (placeholder explícito, ver Task 5 — ahí se cablea
    el intent real; no es un placeholder de lógica de negocio, es la frontera
    exacta entre estas dos tareas).
  - `BibliotecaScreen(..., onExport: () -> Unit)` — nuevo parámetro.
  - Ruta `"export"` en el `NavHost` de `MainActivity`.

- [ ] **Step 1: Write the failing test**

`ExportViewModelTest.kt` (nuevo):

```kotlin
package com.tatoh.dokushorenshu.ui.export

import com.tatoh.dokushorenshu.datos.DiccionarioFake
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.progreso.KanjiTocado
import com.tatoh.dokushorenshu.datos.progreso.PalabraTocada
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDaoFake
import com.tatoh.dokushorenshu.dominio.anki.ArmadorMazos
import com.tatoh.dokushorenshu.dominio.anki.NotaKanji
import com.tatoh.dokushorenshu.dominio.anki.NotaWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

class ExportViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun antes() { Dispatchers.setMain(dispatcher) }
    @After fun despues() { Dispatchers.resetMain() }

    private val momotaroJson =
        javaClass.classLoader!!.getResourceAsStream("momotaro.json")!!.readBytes().decodeToString()

    private fun historiasRepo() = HistoriasRepo(
        leerAsset = { n -> if (n == "historias/momotaro.json") momotaroJson else null },
        listarAssetsHistorias = { listOf("momotaro.json") },
        dirDescargas = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
    )

    private fun dirTemp(): File = File.createTempFile("export", "").let { it.delete(); it.mkdirs(); it }

    private fun vm(
        dao: ProgresoDaoFake = ProgresoDaoFake(),
        diccionario: DiccionarioFake = DiccionarioFake(),
        escribir: (File, List<NotaWords>, List<NotaKanji>) -> Unit = { _, _, _ -> },
        dirExport: File = dirTemp(),
    ): ExportViewModel {
        val armador = ArmadorMazos(dao, diccionario, historiasRepo())
        return ExportViewModel(dao, armador, dirExport, escribir, dispatcher)
    }

    @Test
    fun `contadores reflejan terminos unicos y kanjis taggeados`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "犬", timestamp = 1L))
        dao.registrarPalabra(PalabraTocada("urashima_taro", "犬", timestamp = 2L))  // mismo termino, no duplica
        dao.registrarPalabra(PalabraTocada("momotaro", "猿", timestamp = 3L))
        dao.insertarKanjiSiNoExiste(KanjiTocado("犬", "easy", 1L))
        dao.insertarKanjiSiNoExiste(KanjiTocado("猿", null, 2L))  // sin tag, no cuenta

        val viewModel = vm(dao = dao)
        viewModel.cargar()
        advanceUntilIdle()

        assertEquals(ContadoresExport(words = 2, kanjisTaggeados = 1), viewModel.contadores.value)
    }

    @Test
    fun `exportar termina en Listo con el archivo del mazo Words`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "犬", timestamp = 1L))
        val llamadas = mutableListOf<Triple<File, Int, Int>>()
        val dirExport = dirTemp()
        val viewModel = vm(
            dao = dao,
            escribir = { archivo, words, kanji -> llamadas.add(Triple(archivo, words.size, kanji.size)) },
            dirExport = dirExport,
        )
        assertEquals(EstadoExport.Idle, viewModel.estado.value)

        viewModel.exportar(TipoExport.WORDS)
        advanceUntilIdle()

        val listo = viewModel.estado.value as EstadoExport.Listo
        assertEquals(File(dirExport, "dokusho-words.apkg"), listo.archivo)
        assertEquals(1, llamadas.size)
        assertEquals(1, llamadas[0].second)  // 1 nota words
        assertEquals(0, llamadas[0].third)   // mazo Words no lleva notas de kanji
    }

    @Test
    fun `fallo del escritor pasa a Error y borra el archivo parcial`() = runTest {
        val dirExport = dirTemp()
        val viewModel = vm(
            escribir = { archivo, _, _ -> archivo.writeText("parcial"); throw IOException("disco lleno") },
            dirExport = dirExport,
        )

        viewModel.exportar(TipoExport.KANJI)
        advanceUntilIdle()

        assertTrue(viewModel.estado.value is EstadoExport.Error)
        assertFalse(File(dirExport, "dokusho-kanji.apkg").exists())
    }

    @Test
    fun `kanji omitido por no estar en el db queda reflejado en el resumen`() = runTest {
        val dao = ProgresoDaoFake()
        dao.insertarKanjiSiNoExiste(KanjiTocado("犬", "easy", 1L))  // taggeado...
        val viewModel = vm(dao = dao, diccionario = DiccionarioFake())  // ...pero sin entrada en el dict fake

        viewModel.exportar(TipoExport.KANJI)
        advanceUntilIdle()

        val listo = viewModel.estado.value as EstadoExport.Listo
        assertTrue(listo.resumen.contains("1 skipped"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && ./gradlew testDebugUnitTest --tests '*ExportViewModelTest*'`
Expected: FAIL — no compila (`ExportViewModel`, `ContadoresExport`,
`EstadoExport`, `TipoExport` no existen).

- [ ] **Step 3: Write minimal implementation**

`ExportViewModel.kt` (nuevo, completo):

```kotlin
package com.tatoh.dokushorenshu.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDao
import com.tatoh.dokushorenshu.dominio.anki.ArmadorMazos
import com.tatoh.dokushorenshu.dominio.anki.EscritorApkg
import com.tatoh.dokushorenshu.dominio.anki.NotaKanji
import com.tatoh.dokushorenshu.dominio.anki.NotaWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Un botón por mazo en la pantalla — nunca un archivo combinado (spec Plan 4a:
 *  "dos mazos"). */
enum class TipoExport { WORDS, KANJI }

data class ContadoresExport(val words: Int, val kanjisTaggeados: Int)

sealed interface EstadoExport {
    data object Idle : EstadoExport
    data object Generando : EstadoExport
    data class Listo(val archivo: File, val resumen: String) : EstadoExport
    data class Error(val mensaje: String) : EstadoExport
}

class ExportViewModel(
    private val progresoDao: ProgresoDao,
    private val armadorMazos: ArmadorMazos,
    private val dirExport: File,
    // inyectable solo para tests: mismo motivo que ClienteHttp/HistoriasRepo —
    // evita que el test real escriba/zippee un .apkg (eso lo cubre
    // EscritorApkgTest, Tasks 1-2). En producción, EscritorApkg::escribir.
    private val escribir: (File, List<NotaWords>, List<NotaKanji>) -> Unit = EscritorApkg::escribir,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _contadores = MutableStateFlow(ContadoresExport(0, 0))
    val contadores: StateFlow<ContadoresExport> = _contadores

    private val _estado = MutableStateFlow<EstadoExport>(EstadoExport.Idle)
    val estado: StateFlow<EstadoExport> = _estado

    fun cargar() {
        viewModelScope.launch {
            _contadores.value = withContext(ioDispatcher) {
                val words = progresoDao.todasPalabras().map { it.termino }.distinct().size
                val kanjis = progresoDao.kanjisTaggeados().size
                ContadoresExport(words, kanjis)
            }
        }
    }

    fun exportar(tipo: TipoExport) {
        viewModelScope.launch {
            _estado.value = EstadoExport.Generando
            val destino = File(dirExport, nombreArchivo(tipo))
            try {
                val resumen = withContext(ioDispatcher) {
                    dirExport.mkdirs()
                    val resultado = armadorMazos.armar()
                    when (tipo) {
                        TipoExport.WORDS -> {
                            escribir(destino, resultado.notasWords, emptyList())
                            "${resultado.notasWords.size} words"
                        }
                        TipoExport.KANJI -> {
                            escribir(destino, emptyList(), resultado.notasKanji)
                            val base = "${resultado.notasKanji.size} kanji"
                            if (resultado.kanjisOmitidos > 0) "$base (${resultado.kanjisOmitidos} skipped)" else base
                        }
                    }
                }
                _estado.value = EstadoExport.Listo(destino, "Exported $resumen")
            } catch (e: CancellationException) {
                throw e  // nunca tragar la cancelación del propio viewModelScope
            } catch (e: Exception) {
                destino.delete()  // nunca dejar un .apkg a medias en cache (spec "Manejo de errores")
                _estado.value = EstadoExport.Error("Export failed: ${e.message}")
            }
        }
    }

    private fun nombreArchivo(tipo: TipoExport): String = when (tipo) {
        TipoExport.WORDS -> "dokusho-words.apkg"
        TipoExport.KANJI -> "dokusho-kanji.apkg"
    }
}
```

`ExportScreen.kt` (nuevo, completo — el botón "Share" queda sin cablear, Task 5
lo completa):

```kotlin
package com.tatoh.dokushorenshu.ui.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(vm: ExportViewModel, onCerrar: () -> Unit) {
    val contadores by vm.contadores.collectAsState()
    val estado by vm.estado.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.cargar() }
    LaunchedEffect(estado) {
        val actual = estado
        if (actual is EstadoExport.Error) snackbarHostState.showSnackbar(actual.mensaje)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export") },
                navigationIcon = { TextButton(onClick = onCerrar) { Text("Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { relleno ->
        Column(Modifier.padding(relleno).padding(24.dp).fillMaxSize()) {
            Text(
                "${contadores.words} words · ${contadores.kanjisTaggeados} tagged kanji",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(24.dp))
            BotonExport(
                titulo = "Export Words deck",
                habilitado = contadores.words > 0,
                hint = "Read and tap words first",
                generando = estado is EstadoExport.Generando,
                onClick = { vm.exportar(TipoExport.WORDS) },
            )
            Spacer(Modifier.height(12.dp))
            BotonExport(
                titulo = "Export Kanji deck",
                habilitado = contadores.kanjisTaggeados > 0,
                hint = "Tag kanji as easy/medium/hard first",
                generando = estado is EstadoExport.Generando,
                onClick = { vm.exportar(TipoExport.KANJI) },
            )

            val listo = estado as? EstadoExport.Listo
            if (listo != null) {
                Spacer(Modifier.height(24.dp))
                Text(listo.resumen, style = MaterialTheme.typography.bodyMedium)
                // Task 5 cablea el intent real (FileProvider + ACTION_SEND).
                Button(onClick = { }, modifier = Modifier.padding(top = 8.dp)) { Text("Share") }
            }
        }
    }
}

@Composable
private fun BotonExport(
    titulo: String,
    habilitado: Boolean,
    hint: String,
    generando: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Button(onClick = onClick, enabled = habilitado && !generando) {
            if (generando) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(titulo)
        }
        if (!habilitado) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
```

`BibliotecaScreen.kt` — agregar el botón "Export" al `TopAppBar` (reemplaza solo
el bloque `topBar` y la firma de la función):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BibliotecaScreen(
    vm: BibliotecaViewModel,
    onAbrirHistoria: (String) -> Unit,
    onAcerca: () -> Unit,
    onVerKanji: (String) -> Unit,
    onExport: () -> Unit,
) {
    val locales by vm.locales.collectAsState()
    val catalogo by vm.catalogo.collectAsState()
    val review by vm.review.collectAsState()

    // carga inicial al entrar a la pantalla
    LaunchedEffect(Unit) { vm.cargar() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Dokusho Renshū") },
            actions = {
                TextButton(onClick = onExport) { Text("Export") }
                TextButton(onClick = onAcerca) { Text("About") }
            },
        )
    }) { relleno ->
```

(el resto del archivo, desde `Column(Modifier.padding(relleno)) {` hasta el
final, queda intacto — no lo repito acá).

`MainActivity.kt` — agregar el import y la ruta `"export"`, y pasar `onExport`
a `BibliotecaScreen` (reemplaza el bloque `composable("biblioteca")` y agrega
un `composable("export")` después de `composable("kanji/{kanji}")`):

```kotlin
import com.tatoh.dokushorenshu.ui.export.ExportScreen
import com.tatoh.dokushorenshu.ui.export.ExportViewModel
```

```kotlin
                    composable("biblioteca") {
                        val vm: BibliotecaViewModel = viewModel(factory = viewModelFactory {
                            initializer { BibliotecaViewModel(contenedor.historias, contenedor.progresoDb.dao(), contenedor.diccionario) }
                        })
                        // BibliotecaScreen dispara vm.cargar() con LaunchedEffect (Task 9).
                        BibliotecaScreen(
                            vm = vm,
                            onAbrirHistoria = { id -> nav.navigate("lector/$id") },
                            onAcerca = { nav.navigate("acerca") },
                            onVerKanji = { k -> nav.navigate("kanji/$k") },
                            onExport = { nav.navigate("export") },
                        )
                    }
```

```kotlin
                    composable("export") {
                        val vm: ExportViewModel = viewModel(factory = viewModelFactory {
                            initializer {
                                ExportViewModel(contenedor.progresoDb.dao(), contenedor.armadorMazos, contenedor.dirExportMazos)
                            }
                        })
                        ExportScreen(vm = vm, onCerrar = { nav.popBackStack() })
                    }
```

`App.kt` — agregar 2 propiedades al `Contenedor` (después de `buscador`):

```kotlin
import com.tatoh.dokushorenshu.dominio.anki.ArmadorMazos
import java.io.File
```

```kotlin
class Contenedor(private val app: Application) {
    val progresoDb by lazy { ProgresoDb.crear(app) }
    val prefs by lazy { PrefsRepo(progresoDb.dao()) }
    val diccionario: Diccionario by lazy { DiccionarioSqlite.abrir(app) }
    val historias by lazy { HistoriasRepo.desde(app) }
    val tokenizador by lazy { Tokenizador() }
    val buscador by lazy { BuscadorPalabras(diccionario) }
    val armadorMazos by lazy { ArmadorMazos(progresoDb.dao(), diccionario, historias) }
    // cache, no filesDir: el .apkg es descartable, se regenera en cada export
    // (mismo criterio que FileProvider — spec Plan 4a "sin permisos de storage").
    val dirExportMazos by lazy { File(app.cacheDir, "export").apply { mkdirs() } }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && ./gradlew testDebugUnitTest --tests '*ExportViewModelTest*'`
Expected: BUILD SUCCESSFUL, 4 tests nuevos en verde. Luego gate completo:
`cd app && ./gradlew test assembleDebug` → suite 105 + 4 = **109** en verde,
`assembleDebug` confirma que `BibliotecaScreen`/`MainActivity`/`App` compilan
con el wiring nuevo (Compose no corre en JVM plano, el gate de esa parte es
compilación).

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportViewModel.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportScreen.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaScreen.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/App.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/ui/export/ExportViewModelTest.kt
git commit -m "$(cat <<'EOF'
feat(app): pantalla de Export con counts, progreso y errores + wiring

ExportViewModel: contadores (términos únicos tocados, kanjis taggeados) y
exportar(tipo) que arma las notas (ArmadorMazos) y escribe el .apkg en
cacheDir/export, con estados Idle/Generando/Listo/Error — archivo parcial se
borra si el escritor falla, nunca crashea. ExportScreen: 2 botones (uno por
mazo, deshabilitados con hint si no hay datos), spinner durante la generación,
snackbar de error. Botón "Export" en la topbar de Biblioteca, ruta en el
NavHost, Contenedor expone armadorMazos/dirExportMazos. El botón "Share" del
estado Listo queda sin cablear — lo completa Task 5 (FileProvider).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Share intent + FileProvider

**Files:**
- Modify: `app/app/src/main/AndroidManifest.xml` (`<provider>` de FileProvider)
- Create: `app/app/src/main/res/xml/file_paths.xml`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportScreen.kt`
  (cablear el botón "Share" al intent real)
- Modify: `app/gradle/libs.versions.toml` (versión + alias de `androidx.core:core-ktx`)
- Modify: `app/app/build.gradle.kts` (dependencia nueva)

**Interfaces:**
- Consumes: `EstadoExport.Listo(archivo, resumen)` (Task 4).
- Produces: `<provider>` FileProvider (`${applicationId}.fileprovider`),
  `res/xml/file_paths.xml` (`cache-path` de `export/`), función privada
  `compartirMazo(context, archivo)` en `ExportScreen.kt` que dispara
  `Intent.ACTION_SEND` con `FLAG_GRANT_READ_URI_PERMISSION`.
- Produces nada testeable en JVM — **gate de este task: `./gradlew assembleDebug`**
  (FileProvider/manifest/intents no corren en Robolectric sin un test
  instrumentado; el estado `Listo`/`Error` que dispara la UI ya lo cubre
  `ExportViewModelTest`, Task 4). Verificación real: gate final en dispositivo
  del spec ("share a AnkiDroid en el POCO → importa").

**Investigación del mimeType (spec: "investigar qué acepta AnkiDroid mejor y
documentar"):** el manifest real de AnkiDroid (`ankidroid/Anki-Android`,
`AnkiDroid/src/main/AndroidManifest.xml`) declara un intent-filter de
`ACTION_SEND` que acepta explícitamente, entre otros, `application/apkg`,
`application/x-apkg`, `application/octet-stream`, `application/vnd.anki`. Se
eligió **`application/apkg`**: es el tipo más específico que AnkiDroid reconoce
para este formato (vs. `application/octet-stream`, que es el catch-all
genérico que usan proveedores como Gmail cuando no reconocen la extensión) —
con `ACTION_SEND` el receptor no necesita "renderizar" el tipo para aceptar el
stream, así que sigue funcionando igual de bien con Drive/Bluetooth/otros
receptores del chooser aunque no conozcan `application/apkg` puntualmente.

- [ ] **Step 1: Implementar (task UI-only — sin test de lógica nueva)**

`app/gradle/libs.versions.toml` — agregar versión y alias (edición puntual, no
reemplaza el archivo entero; mismo patrón que `androidx-test-core`, que ya
comparte nombre entre `[versions]` y `[libraries]` en este archivo — son tablas
distintas, no hay colisión):

En `[versions]`, después de `androidx-test-core = "1.7.0"`:
```toml
androidx-core-ktx = "1.19.0"
```

En `[libraries]`, después de `androidx-test-core = ...`:
```toml
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "androidx-core-ktx" }
```

> Nota de supply-chain (instrucción de organización): `androidx.core:core-ktx`
> `1.19.0` es la última estable publicada por Google (`dl.google.com/android/maven2`,
> metadata verificada), liberada con más de 7 días de antigüedad al momento de
> este draft — cumple el mínimo exigido antes de fijar una versión nueva.

`app/app/build.gradle.kts` — agregar la dependencia (después de
`implementation(libs.kuromoji.ipadic)`):

```kotlin
    implementation(libs.androidx.core.ktx)
```

`AndroidManifest.xml` (completo — reemplaza el archivo):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:name=".App"
        android:label="Dokusho Renshū"
        android:supportsRtl="false"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:theme="@style/Theme.Dokusho">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Share de los .apkg generados en cacheDir/export sin permisos de
             storage (spec Plan 4a). exported=false: solo se accede vía la URI
             content:// que entrega getUriForFile(), nunca por su propio
             authority público. -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

`app/app/src/main/res/xml/file_paths.xml` (nuevo, completo):

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- coincide con Contenedor.dirExportMazos = File(app.cacheDir, "export") -->
    <cache-path name="export" path="export/" />
</paths>
```

`ExportScreen.kt` — agregar los imports nuevos y reemplazar el bloque del
botón "Share" (todo lo demás del archivo queda igual):

```kotlin
import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
```

```kotlin
            val listo = estado as? EstadoExport.Listo
            if (listo != null) {
                Spacer(Modifier.height(24.dp))
                Text(listo.resumen, style = MaterialTheme.typography.bodyMedium)
                val context = LocalContext.current
                Button(
                    onClick = { compartirMazo(context, listo.archivo) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Share") }
            }
```

Y agregar, al final del archivo (fuera del `@Composable`):

```kotlin
/** Share intent hacia AnkiDroid (u otro receptor del chooser: Drive, Gmail,
 *  Bluetooth...) vía FileProvider — sin permisos de storage (spec Plan 4a).
 *  "application/apkg" es uno de los mimeTypes que el manifest real de
 *  AnkiDroid declara para su intent-filter de ACTION_SEND (junto con
 *  application/octet-stream, application/x-apkg, application/vnd.anki — ver
 *  "Investigación del mimeType" en el plan), así el share sheet puede
 *  ofrecerlo como destino directo. */
private fun compartirMazo(context: Context, archivo: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archivo)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/apkg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share Anki deck"))
}
```

- [ ] **Step 2: N/A (sin test de lógica nueva — ver Step 1)**

- [ ] **Step 3: N/A (implementación en Step 1)**

- [ ] **Step 4: Run build to verify**

```bash
cd app
./gradlew test           # confirma que ExportViewModelTest (Task 4) sigue verde
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL — 109 tests verdes (sin nuevos en este task) y el
APK debug compila con el provider/manifest/dependencia nuevos. Verificación
manual recomendada, no automatizable en JVM (gate final del spec, en
dispositivo): exportar un mazo real → botón Share → elegir AnkiDroid en el
chooser → confirmar que abre el flujo de import de AnkiDroid (no hace falta
completar el import acá, solo confirmar que el intent llega con el mimeType
correcto); re-export del mismo mazo no debe romper el Share subsecuente
(archivo se pisa, mismo nombre).

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/AndroidManifest.xml \
        app/app/src/main/res/xml/file_paths.xml \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportScreen.kt \
        app/gradle/libs.versions.toml \
        app/app/build.gradle.kts
git commit -m "$(cat <<'EOF'
feat(app): share de los mazos exportados via FileProvider

Provider androidx.core.content.FileProvider (${applicationId}.fileprovider,
exported=false) con file_paths.xml acotado a cacheDir/export — coincide con
Contenedor.dirExportMazos, sin permisos de storage. Botón Share dispara
ACTION_SEND con FLAG_GRANT_READ_URI_PERMISSION y type "application/apkg" (uno
de los mimeTypes que el manifest real de AnkiDroid declara para su
intent-filter de ACTION_SEND, investigado contra el repo público
ankidroid/Anki-Android). Dependencia nueva androidx.core:core-ktx 1.19.0
(última estable, >7 días de antigüedad verificados contra Maven).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Binding constraints verificadas

- Código/comentarios en español, UI en inglés: verificado en todo el código
  nuevo de las 3 tareas.
- `ioDispatcher` inyectable en `ArmadorMazos`-consumers (`ExportViewModel`) y
  patrón `LaunchedEffect(Unit) { vm.cargar() }` en `ExportScreen`: igual que
  `BibliotecaViewModel`/`DetalleKanjiViewModel`.
- Fakes compartidos extendidos, no duplicados: `ProgresoDaoFake` gana
  `todasPalabras()`/`kanjisTaggeados()` en el mismo commit que la interfaz
  `ProgresoDao` (si no, ningún test del módulo compila — mismo patrón que
  Plan 3.5 Task 10).
- AGP 9.2 / Kotlin built-in: ningún `kotlinOptions {}` nuevo.
- Suite base 88 verde al empezar; 105 tras Task 3, 109 tras Task 4, 109 tras
  Task 5 (UI-only, gate = `assembleDebug`).
- Sin placeholders: todo el código de los steps está completo (armado y
  trazado a mano contra los fixtures reales — `momotaro.json` para el ruby
  HTML y para los tests de cap/prioridad de oraciones).

## Riesgos / notas para quien ensambla

- **Contrato de Tasks 1-2**: la única pieza no verificada contra código real
  (`ModeloNotas.kt`/`EscritorApkg.kt` no existen todavía al momento de este
  draft). Si `NotaKanji.onYomi`/`kunYomi`/`significados` terminan siendo
  `String` en vez de `List<String>`, o si `EscritorApkg` es una `class` en vez
  de `object`, Task 3/4 necesitan un ajuste quirúrgico (documentado inline en
  "Contrato asumido de Tasks 1-2"), no una reescritura.
- El campo `Tag` de `NotaWords` queda siempre `""` (vacío) — es un campo
  reservado del template Kaishi-style para que el usuario taggee a mano en
  Anki, `ArmadorMazos` nunca lo llena (confirmado contra el spec, sección
  "Estructura de cartas").
- `armarOraciones` recorre TODAS las historias locales por cada término/kanji
  (no solo la historia donde se tocó) — coincide con el spec ("escanea las
  historias locales que contienen la palabra/kanji"), pero es O(términos ×
  oraciones-totales); con el volumen actual (unas pocas historias, cientos de
  oraciones) es intrascendente, pero si el catálogo crece mucho conviene un
  índice invertido (fuera de alcance de Plan 4a).
- `compartirMazo` usa `context.packageName` en vez de `BuildConfig.APPLICATION_ID`
  para armar el authority del FileProvider — a propósito: `packageName` en
  runtime siempre refleja el `applicationId` efectivo (con cualquier
  `applicationIdSuffix` de build type ya aplicado), igual que `${applicationId}`
  en el manifest se resuelve al mismo valor en build time — quedan alineados
  incluso si en el futuro se agrega un suffix de debug.
- El botón "Share" en Task 4 queda con `onClick = {}` intencionalmente (no es
  un placeholder de negocio: es la frontera exacta con Task 5, documentada en
  el commit de Task 4) — si se ejecuta Task 4 sin seguir con Task 5 en la
  misma sesión, el botón queda visualmente presente pero inerte; no bloquea
  `assembleDebug` ni la suite.


---

### Task 6: Gate en AnkiDroid + ESTADO.md + PR

**Files:**
- Modify: `docs/ESTADO.md`

**Interfaces:**
- Consumes: APK con todo el plan; POCO con AnkiDroid conectado.
- Produces: PR del branch.

- [ ] **Step 1: Gate real en AnkiDroid** (coordina el controller; requiere datos reales en el device — el usuario ya tiene palabras tocadas y kanjis taggeados):

```bash
cd app && ./gradlew installDebug   # en el POCO
```
Checklist:
1. Biblioteca → botón Export → pantalla con counts reales ("N words · M tagged kanji").
2. Export Words → share sheet → AnkiDroid → importa sin error; mazo "Dokusho — Words" con N notas.
3. Carta estilo Kaishi: palabra grande, lectura, glosas, oración con furigana ruby (sin romaji); en renders sucesivos de la MISMA carta la oración rota (si hay >1).
4. Export Kanji → mazo "Dokusho — Kanji"; carta muestra la dificultad.
5. Re-export Words → re-import en AnkiDroid → NO duplica notas (GUIDs estables; AnkiDroid reporta updated/skipped).
6. Sin datos (opcional, tablet con app recién instalada): botones deshabilitados con hint.

Si algo falla: fix con TDD donde sea testeable, re-validar.

- [ ] **Step 2: ESTADO.md**

Tabla: fila | 4a | mazos Anki (.apkg) | ✅ Completo (PR pendiente — actualizar con #N al abrir) |; la fila del Plan 4 original pasa a "4b — import de texto propio | ⏳ Siguiente. Brainstorming pendiente". Datos operativos: nota corta (dominio/anki/, GUIDs estables por término/kanji, share intent, schema genanki-verified). Commit `docs(ESTADO): plan 4a completo`.

- [ ] **Step 3: Push + PR**

```bash
git push -u origin feature/plan-4a-mazos
gh pr create --title "Plan 4a: mazos Anki (.apkg)" \
  --body "Export de mazos Anki desde el estado de la app: Words (todas las palabras tocadas) y Kanji (taggeados con su dificultad). Writer .apkg propio (schema verificado contra genanki), cartas estilo Kaishi con furigana HTML real de las historias y oraciones rotativas por JS, GUIDs estables (re-export actualiza, no duplica), share intent a AnkiDroid. Spec: docs/superpowers/specs/2026-07-09-plan-4a-mazos-anki-design.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```
