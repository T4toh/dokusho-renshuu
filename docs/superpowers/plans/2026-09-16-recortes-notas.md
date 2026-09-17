# Recortes (notas de captura) — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que lo capturado deje de ser una `Historia` y pase a ser un tipo propio, `Recorte`, con su almacenamiento, su lista, su vista liviana con imagen y su mazo de Anki.

**Architecture:** `Recorte` reusa el modelo de texto existente (`Parrafo`/`Oracion`/`Furigana`) pero no `Historia`. Se persiste como JSON + JPEG en `filesDir/recortes/`. La vista reusa el renderizado de oraciones del lector, que para eso se promueve de `private` en `LectorScreen.kt` a un componente compartido. El mazo de Anki se separa mediante el prefijo `recorte:` en `palabras_tocadas.idHistoria`, con un test que fija la separación.

**Tech Stack:** Kotlin, Jetpack Compose M3, Room (sin migración), kotlinx-serialization (JsonObject manual), Kuromoji, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-16-recortes-notas-design.md`

**Depende de:** la rama `feat/captura-ocr-overlay` (PR #19), que trajo la captura. Este plan cambia el destino de lo capturado.

## Global Constraints

- **minSdk 26**, compileSdk/targetSdk 36, JDK 17+ (JDK 21 instalado).
- **Código y comentarios en español; strings de UI en inglés.** En el Plan E esta constraint se violó tres veces, siempre con strings que no eran un `Text(...)` de Compose: nombres de canal de notificación, títulos de notificación y propiedades del manifest. Revisar *todo* string visible, no solo los de Compose.
- Los comentarios explican el **por qué**, no el qué.
- **Package base** `com.tatoh.dokushorenshu`.
- **Sin migración de Room.** El discriminador va en la columna TEXT existente.
- **Comando de tests filtrado:** `./gradlew testDebugUnitTest --tests "..."` desde `app/`. **`./gradlew test --tests` FALLA** con "Unknown command-line option" porque `test` es una task agregada de AGP. La suite completa sin filtro sí es `./gradlew test`.
- **Los commits NO llevan líneas de co-autoría ni atribución a agentes.** Regla dura del proyecto.
- **Ningún `git push`** sin pedido explícito del usuario.
- Suite al arrancar: **248 tests, 0 failures**.
- Directorio de trabajo de Gradle: `app/`. El módulo es `app/app/`.

---

### Task 1: Modelo `Recorte` y su serializador

El tipo y su ida y vuelta a JSON. JVM puro, es lo más testeable del plan.

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/Recorte.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/RecorteTest.kt`

**Interfaces:**
- Consumes: `Parrafo`, `Oracion`, `Furigana` de `datos/ModelosHistoria.kt` (ya existen).
- Produces:
  - `data class Recorte(val id: String, val texto: String, val parrafos: List<Parrafo>, val timestamp: Long, val tieneImagen: Boolean)`
  - `object SerializadorRecorte { fun serializar(r: Recorte): String }`
  - `object ParserRecorte { fun parsear(texto: String): Recorte }`

- [ ] **Step 1: Escribir el test que falla**

Crear `app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/RecorteTest.kt`:

```kotlin
package com.tatoh.dokushorenshu.datos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecorteTest {

    private val ejemplo = Recorte(
        id = "1758035000000",
        texto = "昔々、ある所におじいさんがいました。",
        parrafos = listOf(
            Parrafo(
                listOf(
                    Oracion(
                        texto = "昔々、ある所におじいさんがいました。",
                        furigana = listOf(Furigana(0, 2, "むかしむかし"), Furigana(6, 7, "ところ")),
                    )
                )
            )
        ),
        timestamp = 1758035000000L,
        tieneImagen = true,
    )

    @Test
    fun `round-trip conserva todos los campos`() {
        val vuelto = ParserRecorte.parsear(SerializadorRecorte.serializar(ejemplo))
        assertEquals(ejemplo, vuelto)
    }

    @Test
    fun `round-trip conserva tieneImagen en false`() {
        val sinImagen = ejemplo.copy(tieneImagen = false)
        assertEquals(sinImagen, ParserRecorte.parsear(SerializadorRecorte.serializar(sinImagen)))
    }

    @Test
    fun `round-trip conserva varios parrafos y oraciones`() {
        val multiple = ejemplo.copy(
            texto = "一行目。\n二行目です。",
            parrafos = listOf(
                Parrafo(listOf(Oracion("一行目。", emptyList()))),
                Parrafo(listOf(Oracion("二行目です。", listOf(Furigana(0, 1, "に"))))),
            ),
        )
        assertEquals(multiple, ParserRecorte.parsear(SerializadorRecorte.serializar(multiple)))
    }

    @Test
    fun `json invalido lanza IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { ParserRecorte.parsear("{no es json") }
    }

    @Test
    fun `json sin campo obligatorio lanza IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ParserRecorte.parsear("""{"id":"1","texto":"あ"}""")
        }
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
cd app && ./gradlew testDebugUnitTest --tests "com.tatoh.dokushorenshu.datos.RecorteTest"
```

Esperado: FAIL de compilación — `Unresolved reference: Recorte` / `SerializadorRecorte` / `ParserRecorte`.

- [ ] **Step 3: Escribir la implementación**

Crear `app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/Recorte.kt`:

```kotlin
package com.tatoh.dokushorenshu.datos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Un recorte de captura: texto con furigana, sin la metadata de catálogo que
 *  arrastra [Historia] (autor, licencia, dificultad, version, fuente) y que no
 *  significa nada para tres líneas de manga.
 *
 *  [texto] es la fuente de verdad y [parrafos] la caché derivada: al editar se
 *  regeneran los párrafos desde el texto, así no hay dos estados que puedan
 *  desincronizarse.
 *
 *  [tieneImagen] es derivable de si existe `<id>.jpg`, pero se guarda para no
 *  hacer un File.exists() por fila al pintar la lista. */
data class Recorte(
    val id: String,
    val texto: String,
    val parrafos: List<Parrafo>,
    val timestamp: Long,
    val tieneImagen: Boolean,
)

object SerializadorRecorte {
    fun serializar(recorte: Recorte): String = buildJsonObject {
        put("id", recorte.id)
        put("texto", recorte.texto)
        put("timestamp", recorte.timestamp)
        put("tieneImagen", recorte.tieneImagen)
        putJsonArray("parrafos") {
            for (parrafo in recorte.parrafos) addJsonObject {
                putJsonArray("oraciones") {
                    for (oracion in parrafo.oraciones) addJsonObject {
                        put("texto", oracion.texto)
                        // mismo formato de terna que el catálogo: [inicio, fin, lectura]
                        putJsonArray("furigana") {
                            for (f in oracion.furigana) addJsonArray {
                                add(f.inicio); add(f.fin); add(f.lectura)
                            }
                        }
                    }
                }
            }
        }
    }.toString()
}

object ParserRecorte {
    private val json = Json { ignoreUnknownKeys = true }

    /** Falla con IllegalArgumentException ante cualquier estructura inválida —
     *  el caller descarta el archivo corrupto y sigue (spec: un recorte roto
     *  nunca tumba la lista). */
    fun parsear(texto: String): Recorte = try {
        val raiz = json.parseToJsonElement(texto).jsonObject
        Recorte(
            id = raiz.req("id").jsonPrimitive.content,
            texto = raiz.req("texto").jsonPrimitive.content,
            timestamp = raiz.req("timestamp").jsonPrimitive.long,
            tieneImagen = raiz.req("tieneImagen").jsonPrimitive.boolean,
            parrafos = raiz.req("parrafos").jsonArray.map { p ->
                Parrafo(p.jsonObject.req("oraciones").jsonArray.map { o ->
                    val obj = o.jsonObject
                    Oracion(
                        texto = obj.req("texto").jsonPrimitive.content,
                        furigana = obj.req("furigana").jsonArray.map { f ->
                            val terna = f.jsonArray
                            Furigana(
                                terna[0].jsonPrimitive.int,
                                terna[1].jsonPrimitive.int,
                                terna[2].jsonPrimitive.content,
                            )
                        },
                    )
                })
            },
        )
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("JSON de recorte inválido: ${e.message}", e)
    }

    private fun JsonObject.req(clave: String) =
        this[clave] ?: throw IllegalArgumentException("falta el campo '$clave'")
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

```bash
cd app && ./gradlew testDebugUnitTest --tests "com.tatoh.dokushorenshu.datos.RecorteTest"
```

Esperado: PASS, 5 tests.

- [ ] **Step 5: Correr la suite completa**

```bash
cd app && ./gradlew test
```

Esperado: 253 tests, 0 failures (248 + 5).

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/Recorte.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/RecorteTest.kt
git commit -m "feat(recortes): modelo Recorte con serializador y parser"
```

---

### Task 2: `RecortesRepo`

Persistencia: JSON + JPEG, escritura atómica, borrado, y el listado que saltea corruptos.

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/RecortesRepo.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/RecortesRepoTest.kt`

**Interfaces:**
- Consumes: `Recorte`, `SerializadorRecorte`, `ParserRecorte` (Task 1).
- Produces:
  - `class RecortesRepo(private val dir: File)` con `companion object { fun desde(contexto: Context): RecortesRepo }`
  - `fun listar(): List<Recorte>` — orden por `timestamp` descendente
  - `fun cargar(id: String): Recorte?`
  - `fun guardar(recorte: Recorte, imagenPendiente: File? = null): Recorte` — devuelve el recorte con `tieneImagen` ya resuelto
  - `fun borrar(id: String): Boolean` — borra JSON e imagen
  - `fun quitarImagen(id: String): Boolean` — borra solo la imagen
  - `fun archivoImagen(id: String): File?` — null si no existe
  - `fun idLibre(timestamp: Long): String`

- [ ] **Step 1: Escribir el test que falla**

Crear `app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/RecortesRepoTest.kt`:

```kotlin
package com.tatoh.dokushorenshu.datos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecortesRepoTest {

    @get:Rule val carpeta = TemporaryFolder()

    private fun repo() = RecortesRepo(carpeta.root)

    private fun recorte(id: String, ts: Long, texto: String = "あ") = Recorte(
        id = id,
        texto = texto,
        parrafos = listOf(Parrafo(listOf(Oracion(texto, emptyList())))),
        timestamp = ts,
        tieneImagen = false,
    )

    @Test
    fun `guardar y cargar devuelve el mismo recorte`() {
        val r = repo()
        r.guardar(recorte("100", 100L))
        assertEquals(recorte("100", 100L), r.cargar("100"))
    }

    @Test
    fun `cargar un id inexistente devuelve null`() {
        assertNull(repo().cargar("noexiste"))
    }

    @Test
    fun `listar ordena por timestamp descendente`() {
        val r = repo()
        r.guardar(recorte("100", 100L))
        r.guardar(recorte("300", 300L))
        r.guardar(recorte("200", 200L))
        assertEquals(listOf("300", "200", "100"), r.listar().map { it.id })
    }

    @Test
    fun `listar saltea un archivo corrupto sin tumbar la lista`() {
        val r = repo()
        r.guardar(recorte("100", 100L))
        File(carpeta.root, "999.json").writeText("{roto")
        assertEquals(listOf("100"), r.listar().map { it.id })
    }

    @Test
    fun `guardar con imagen pendiente la mueve y marca tieneImagen`() {
        val r = repo()
        val pendiente = carpeta.newFile("captura-pendiente.jpg")
        pendiente.writeBytes(byteArrayOf(1, 2, 3))
        val guardado = r.guardar(recorte("100", 100L), pendiente)
        assertTrue(guardado.tieneImagen)
        assertFalse("el pendiente se mueve, no se copia", pendiente.exists())
        assertEquals(listOf<Byte>(1, 2, 3), r.archivoImagen("100")!!.readBytes().toList())
    }

    @Test
    fun `guardar sin imagen deja tieneImagen en false y archivoImagen en null`() {
        val r = repo()
        val guardado = r.guardar(recorte("100", 100L), null)
        assertFalse(guardado.tieneImagen)
        assertNull(r.archivoImagen("100"))
    }

    @Test
    fun `quitarImagen borra la imagen y conserva el recorte`() {
        val r = repo()
        val pendiente = carpeta.newFile("captura-pendiente.jpg").apply { writeBytes(byteArrayOf(1)) }
        r.guardar(recorte("100", 100L), pendiente)
        assertTrue(r.quitarImagen("100"))
        assertNull(r.archivoImagen("100"))
        assertFalse("el recorte sobrevive", r.cargar("100")!!.tieneImagen)
    }

    @Test
    fun `borrar elimina el json y la imagen`() {
        val r = repo()
        val pendiente = carpeta.newFile("captura-pendiente.jpg").apply { writeBytes(byteArrayOf(1)) }
        r.guardar(recorte("100", 100L), pendiente)
        assertTrue(r.borrar("100"))
        assertNull(r.cargar("100"))
        assertNull(r.archivoImagen("100"))
    }

    @Test
    fun `idLibre incrementa si el timestamp ya esta tomado`() {
        val r = repo()
        r.guardar(recorte("100", 100L))
        assertNotEquals("100", r.idLibre(100L))
        assertEquals("101", r.idLibre(100L))
    }

    @Test
    fun `tieneImagen true con el archivo ausente no rompe archivoImagen`() {
        val r = repo()
        val pendiente = carpeta.newFile("captura-pendiente.jpg").apply { writeBytes(byteArrayOf(1)) }
        r.guardar(recorte("100", 100L), pendiente)
        r.archivoImagen("100")!!.delete()  // borrado externo
        assertNull(r.archivoImagen("100"))
        assertTrue("el recorte sigue cargando", r.cargar("100") != null)
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
cd app && ./gradlew testDebugUnitTest --tests "com.tatoh.dokushorenshu.datos.RecortesRepoTest"
```

Esperado: FAIL de compilación — `Unresolved reference: RecortesRepo`.

- [ ] **Step 3: Escribir la implementación**

Crear `app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/RecortesRepo.kt`:

```kotlin
package com.tatoh.dokushorenshu.datos

import android.content.Context
import java.io.File

/** Recortes en `filesDir/recortes/`: `<id>.json` con el texto y `<id>.jpg` con la
 *  imagen original de la captura.
 *
 *  Repo aparte y no un quinto origen dentro de HistoriasRepo: ese ya maneja assets,
 *  descargas, catálogo remoto e importadas. */
class RecortesRepo(private val dir: File) {

    companion object {
        /** Slot fijo donde el Service deja la imagen recién capturada. Uno solo, porque
         *  `isCapturing` garantiza una captura en vuelo a la vez: si el usuario cancela,
         *  el huérfano se pisa en la próxima en vez de quedar acumulándose. */
        const val NOMBRE_PENDIENTE = "captura-pendiente.jpg"

        fun desde(contexto: Context): RecortesRepo =
            RecortesRepo(File(contexto.filesDir, "recortes"))

        fun imagenPendiente(contexto: Context): File =
            File(contexto.filesDir, NOMBRE_PENDIENTE)
    }

    private fun json(id: String) = File(dir, "$id.json")
    private fun jpg(id: String) = File(dir, "$id.jpg")

    /** Descendente por timestamp: la última captura arriba. Un JSON corrupto se
     *  saltea — mismo criterio que historiasLocales(): nunca tumbar la lista entera
     *  por un archivo roto. */
    fun listar(): List<Recorte> = (dir.listFiles() ?: emptyArray())
        .filter { it.name.endsWith(".json") }
        .mapNotNull { archivo ->
            try {
                ParserRecorte.parsear(archivo.readText())
            } catch (e: Exception) {
                android.util.Log.w("RecortesRepo", "recorte corrupto, se saltea: ${archivo.name}", e)
                null
            }
        }
        .sortedByDescending { it.timestamp }

    fun cargar(id: String): Recorte? = try {
        json(id).takeIf { it.exists() }?.let { ParserRecorte.parsear(it.readText()) }
    } catch (e: Exception) {
        android.util.Log.w("RecortesRepo", "recorte corrupto: $id", e)
        null
    }

    /** Mueve `imagenPendiente` a `<id>.jpg` si viene, y escribe el JSON de forma
     *  atómica (tmp → rename), igual que guardarImportada().
     *
     *  Si el movimiento de la imagen falla, el recorte se guarda igual con
     *  `tieneImagen = false`: perder la imagen NUNCA puede costar el texto. */
    fun guardar(recorte: Recorte, imagenPendiente: File? = null): Recorte {
        dir.mkdirs()
        val conImagen = if (imagenPendiente != null && imagenPendiente.exists()) {
            moverImagen(imagenPendiente, jpg(recorte.id))
        } else {
            false
        }
        val definitivo = recorte.copy(tieneImagen = conImagen)
        val crudo = SerializadorRecorte.serializar(definitivo)
        ParserRecorte.parsear(crudo)  // round-trip antes de escribir: nunca JSON a medias
        val tmp = File(dir, "${recorte.id}.json.tmp")
        try {
            tmp.writeText(crudo)
            check(tmp.renameTo(json(recorte.id))) { "no se pudo renombrar $tmp" }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        return definitivo
    }

    private fun moverImagen(origen: File, destino: File): Boolean = try {
        // renameTo falla entre dispositivos de archivo distintos; ambos están en
        // filesDir, pero el copy+delete es el fallback barato y seguro.
        if (origen.renameTo(destino)) true
        else {
            origen.copyTo(destino, overwrite = true)
            origen.delete()
            true
        }
    } catch (e: Exception) {
        android.util.Log.e("RecortesRepo", "no se pudo guardar la imagen", e)
        false
    }

    fun borrar(id: String): Boolean {
        jpg(id).delete()
        return json(id).delete()
    }

    /** Borra solo la imagen y reescribe el recorte con `tieneImagen = false`. */
    fun quitarImagen(id: String): Boolean {
        val recorte = cargar(id) ?: return false
        jpg(id).delete()
        guardar(recorte.copy(tieneImagen = false), null)
        return true
    }

    fun archivoImagen(id: String): File? = jpg(id).takeIf { it.exists() }

    /** El id es el timestamp en millis; si ya está tomado se incrementa. Dos capturas
     *  en el mismo milisegundo es prácticamente imposible, pero pisar un recorte del
     *  usuario no es un riesgo que valga la pena correr por una línea. */
    fun idLibre(timestamp: Long): String {
        var candidato = timestamp
        while (json(candidato.toString()).exists()) candidato++
        return candidato.toString()
    }
}
```

- [ ] **Step 4: Correr el test y verificar que pasa**

```bash
cd app && ./gradlew testDebugUnitTest --tests "com.tatoh.dokushorenshu.datos.RecortesRepoTest"
```

Esperado: PASS, 10 tests.

- [ ] **Step 5: Correr la suite completa**

```bash
cd app && ./gradlew test
```

Esperado: 263 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/RecortesRepo.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/datos/RecortesRepoTest.kt
git commit -m "feat(recortes): repo con persistencia de texto e imagen"
```

---

### Task 3: Pipeline de creación

Texto plano → `Recorte` con furigana. Es el pipeline de import sin el formulario.

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/CreadorRecortes.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/CreadorRecortesTest.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/App.kt`

**Interfaces:**
- Consumes: `SegmentadorTexto.segmentar(texto): List<Pair<Int,Int>>`, `GeneradorFurigana.generar(oracion: String): List<Furigana>`, `RecortesRepo` (Task 2).
- Produces: `class CreadorRecortes(generadorFurigana, recortesRepo)` con `fun crear(texto: String, imagenPendiente: File? = null): Recorte`.

- [ ] **Step 1: Escribir el test que falla**

Crear `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/CreadorRecortesTest.kt`:

```kotlin
package com.tatoh.dokushorenshu.dominio

import com.tatoh.dokushorenshu.datos.RecortesRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CreadorRecortesTest {

    @get:Rule val carpeta = TemporaryFolder()

    private fun creador() = CreadorRecortes(
        GeneradorFurigana(Tokenizador()),
        RecortesRepo(carpeta.root),
    )

    @Test
    fun `una linea con dos oraciones produce un parrafo con dos oraciones`() {
        val r = creador().crear("今日はいい天気です。明日も晴れます。")
        assertEquals(1, r.parrafos.size)
        assertEquals(2, r.parrafos[0].oraciones.size)
    }

    @Test
    fun `dos lineas producen dos parrafos`() {
        val r = creador().crear("一行目です。\n二行目です。")
        assertEquals(2, r.parrafos.size)
    }

    @Test
    fun `las lineas vacias se descartan`() {
        val r = creador().crear("一行目です。\n\n  \n二行目です。")
        assertEquals(2, r.parrafos.size)
    }

    @Test
    fun `genera furigana sobre los kanji`() {
        val r = creador().crear("天気がいい。")
        assertTrue("debería anotar la lectura de 天気", r.parrafos[0].oraciones[0].furigana.isNotEmpty())
    }

    @Test
    fun `conserva el texto crudo tal cual`() {
        val texto = "一行目です。\n二行目です。"
        assertEquals(texto, creador().crear(texto).texto)
    }

    @Test
    fun `texto sin contenido lanza IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { creador().crear("   \n  ") }
    }

    @Test
    fun `dos recortes seguidos no comparten id`() {
        val c = creador()
        assertTrue(c.crear("あ。").id != c.crear("い。").id)
    }

    @Test
    fun `el recorte queda persistido`() {
        val repo = RecortesRepo(carpeta.root)
        val creado = CreadorRecortes(GeneradorFurigana(Tokenizador()), repo).crear("天気。")
        assertEquals(creado, repo.cargar(creado.id))
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
cd app && ./gradlew testDebugUnitTest --tests "com.tatoh.dokushorenshu.dominio.CreadorRecortesTest"
```

Esperado: FAIL de compilación — `Unresolved reference: CreadorRecortes`.

- [ ] **Step 3: Escribir la implementación**

Crear `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/CreadorRecortes.kt`:

```kotlin
package com.tatoh.dokushorenshu.dominio

import com.tatoh.dokushorenshu.datos.Oracion
import com.tatoh.dokushorenshu.datos.Parrafo
import com.tatoh.dokushorenshu.datos.Recorte
import com.tatoh.dokushorenshu.datos.RecortesRepo
import java.io.File

/** Texto plano → Recorte persistido. Es el pipeline de ImportadorHistoria sin el
 *  formulario: una línea no vacía = un párrafo, SegmentadorTexto corta oraciones,
 *  Kuromoji genera la furigana.
 *
 *  Corre en el ioDispatcher del caller: Kuromoji tarda en textos largos. */
class CreadorRecortes(
    private val generadorFurigana: GeneradorFurigana,
    private val recortesRepo: RecortesRepo,
    private val ahora: () -> Long = System::currentTimeMillis,
) {
    fun crear(texto: String, imagenPendiente: File? = null): Recorte {
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

        val timestamp = ahora()
        val id = recortesRepo.idLibre(timestamp)
        return recortesRepo.guardar(
            Recorte(
                id = id,
                texto = texto,
                parrafos = parrafos,
                timestamp = timestamp,
                tieneImagen = false,  // lo resuelve guardar() según la imagen pendiente
            ),
            imagenPendiente,
        )
    }
}
```

- [ ] **Step 4: Exponerlo en el contenedor de DI**

En `app/app/src/main/kotlin/com/tatoh/dokushorenshu/App.kt`, agregar los imports y dos propiedades lazy dentro de `class Contenedor`, después de `val importador`:

```kotlin
import com.tatoh.dokushorenshu.datos.RecortesRepo
import com.tatoh.dokushorenshu.dominio.CreadorRecortes
```

```kotlin
    val recortes by lazy { RecortesRepo.desde(app) }
    val creadorRecortes by lazy { CreadorRecortes(GeneradorFurigana(tokenizador), recortes) }
```

- [ ] **Step 5: Correr el test y la suite**

```bash
cd app && ./gradlew assembleDebug test
```

Esperado: BUILD SUCCESSFUL, 271 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/CreadorRecortes.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/CreadorRecortesTest.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/App.kt
git commit -m "feat(recortes): pipeline de creación desde texto plano"
```

---

### Task 4: El Service guarda la imagen

Amplía el contrato de `ScreenCaptureService`: además del texto, deja la imagen en el slot fijo.

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/ScreenCaptureService.kt`

**Interfaces:**
- Consumes: `RecortesRepo.imagenPendiente(contexto)` y `RecortesRepo.NOMBRE_PENDIENTE` (Task 2).
- Produces: `ScreenCaptureService.EXTRA_RUTA_IMAGEN` (String, la ruta absoluta del JPEG; ausente si falló el guardado).

**Contexto que el implementador necesita:** `processCapture()` ya tiene el `Bitmap` recortado (`recortado`) dentro de un `Thread` de fondo, y llama a `entregarTexto(texto)` cuando el OCR devuelve algo no vacío. Toda la lógica de reciclado de bitmaps y de teardown quedó afinada en tres rondas de review del Plan E: **no tocarla**. El único cambio es escribir el JPEG antes de entregar y sumar un extra al Intent.

- [ ] **Step 1: Agregar la constante del extra**

En el `companion object` de `ScreenCaptureService`, junto a `EXTRA_TEXTO_OCR`:

```kotlin
        /** Ruta absoluta del JPEG de la captura. Ausente si el guardado falló —
         *  perder la imagen nunca puede costar el texto. */
        const val EXTRA_RUTA_IMAGEN = "ruta_imagen"
```

- [ ] **Step 2: Escribir el JPEG antes de entregar**

Agregar el método privado:

```kotlin
    /** Guarda la captura como JPEG 90 en el slot fijo de RecortesRepo.
     *
     *  JPEG y no PNG: el PNG de una captura de pantalla pesa 2-5 MB y se guarda una
     *  por nota. En JPEG 90 una pantalla completa queda en ~700 KB, con calidad de
     *  sobra para releer el original cuando el OCR salió ilegible.
     *
     *  Devuelve null si falla: el recorte se crea igual, sin imagen. */
    private fun guardarImagen(bitmap: Bitmap): File? = try {
        val destino = RecortesRepo.imagenPendiente(this)
        destino.outputStream().use { salida ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, salida)
        }
        destino
    } catch (e: Throwable) {
        android.util.Log.e("ScreenCapture", "no se pudo guardar la imagen de la captura", e)
        null
    }
```

`catch (e: Throwable)` y no `Exception`: comprimir un bitmap de pantalla completa puede tirar `OutOfMemoryError`, que es un `Error`. Es la misma razón por la que el `catch` de `prepararBitmaps` es `Throwable` — ver su comentario.

- [ ] **Step 3: Llamarlo y pasar la ruta en el Intent**

Dentro del `Thread { ... }` de `processCapture()`, **antes** de los `recycle()`, guardar la imagen mientras el bitmap sigue vivo:

```kotlin
            // Antes de reciclar: el bitmap recortado es lo que el usuario eligió, y es
            // lo que queremos conservar como imagen del recorte.
            val rutaImagen = if (texto.isNotBlank()) guardarImagen(recortado) else null
```

Y cambiar la firma y el cuerpo de `entregarTexto`:

```kotlin
    private fun entregarTexto(texto: String, rutaImagen: File?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_TEXTO_OCR
            putExtra(EXTRA_TEXTO_OCR, texto)
            if (rutaImagen != null) putExtra(EXTRA_RUTA_IMAGEN, rutaImagen.absolutePath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }
```

Actualizar la llamada existente a `entregarTexto(texto)` para que pase `rutaImagen`.

- [ ] **Step 4: Verificar imports**

`ScreenCaptureService.kt` ya importa `android.graphics.*` (cubre `Bitmap`). Agregar:

```kotlin
import com.tatoh.dokushorenshu.datos.RecortesRepo
import java.io.File
```

- [ ] **Step 5: Verificar que compila y que la suite pasa**

```bash
cd app && ./gradlew assembleDebug test
```

Esperado: BUILD SUCCESSFUL, 271 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/ScreenCaptureService.kt
git commit -m "feat(captura): guardar la imagen de la captura como JPEG"
```

---

### Task 5: Promover el renderizado de oraciones a componente compartido

Refactor sin cambio de comportamiento. `ItemOracion` y `OracionPlana` viven hoy en el lector y la vista de nota los necesita.

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/comun/OracionRenderizada.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/lector/LectorScreen.kt` (borrar `ItemOracion`, importar del nuevo lugar)
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/lector/LectorViewModel.kt` (mover `OracionPlana`, dejar un alias o actualizar usos)

**Interfaces:**
- Produces:
  - `data class OracionPlana(val parrafo: Int, val oracionEnParrafo: Int, val oracion: Oracion, val tokens: List<PalabraToken>, val gruposFurigana: List<GrupoFurigana>)` — movida tal cual desde `LectorViewModel.kt`
  - `@Composable fun ItemOracion(esActual: Boolean, plana: OracionPlana, furiganaActiva: Boolean, katakanaActiva: Boolean, onTapPalabra: (PalabraToken) -> Unit, onLongPressPalabra: (PalabraToken) -> Unit, rangoSeleccion: IntRange?)` — movida tal cual desde `LectorScreen.kt`, ahora pública
  - `fun aplanar(parrafos: List<Parrafo>, tokenizador: Tokenizador): List<OracionPlana>` — extrae el bucle que hoy está inline en `LectorViewModel.cargar()`

- [ ] **Step 1: Mover el código sin cambiarlo**

Crear `ui/comun/OracionRenderizada.kt` con `OracionPlana`, `ItemOracion` (ahora sin `private`) y `aplanar`, copiando **textualmente** el código actual, incluidos todos sus comentarios. Los comentarios de `ItemOracion` sobre el alpha animado y el reflow de la `LazyColumn` documentan un fix de performance real reportado desde dispositivo (Plan 3.6): se conservan palabra por palabra.

`aplanar` extrae el bucle de `LectorViewModel.cargar()`:

```kotlin
/** Precomputa tokens y grupos de furigana por oración. Va acá y no en
 *  TextoConFurigana porque es caro: calcularlo en cada recomposición de item
 *  hacía tirones al scrollear (Plan 3.6, feedback de dispositivo).
 *  Llamar SIEMPRE desde un dispatcher de IO: Kuromoji bloquea. */
fun aplanar(parrafos: List<Parrafo>, tokenizador: Tokenizador): List<OracionPlana> =
    parrafos.flatMapIndexed { p, parrafo ->
        parrafo.oraciones.mapIndexed { o, oracion ->
            val tokens = tokenizador.tokenizar(oracion.texto)
            OracionPlana(p, o, oracion, tokens, calcularGruposFurigana(tokens, oracion.furigana))
        }
    }
```

- [ ] **Step 2: Actualizar el lector para usar el componente compartido**

Borrar `ItemOracion` de `LectorScreen.kt` y `OracionPlana` de `LectorViewModel.kt`, agregando los imports desde `ui.comun`. Reemplazar el bucle inline de `cargar()` por una llamada a `aplanar(historia.parrafos, tokenizador)`.

**No cambiar ninguna otra cosa del lector.** El objetivo de esta task es que el diff sea puramente un movimiento.

- [ ] **Step 3: Verificar que el comportamiento no cambió**

```bash
cd app && ./gradlew assembleDebug test
```

Esperado: BUILD SUCCESSFUL, 271 tests, 0 failures — **exactamente los mismos** que antes de la task. Si algún test del lector cambia de resultado, el movimiento no fue puro: revisarlo antes de seguir.

- [ ] **Step 4: Verificar que el diff es un movimiento**

```bash
git diff --stat
```

Esperado: las líneas borradas de `LectorScreen.kt` + `LectorViewModel.kt` deben ser aproximadamente las agregadas en `OracionRenderizada.kt`, más los imports. Un diff mucho más grande significa que se reescribió algo.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/comun/OracionRenderizada.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/lector/LectorScreen.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/lector/LectorViewModel.kt
git commit -m "refactor(ui): promover ItemOracion y OracionPlana a ui/comun"
```

---

### Task 6: Pestañas en Biblioteca y lista de notas

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaScreen.kt`
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/recortes/ListaRecortesScreen.kt`
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/recortes/RecortesViewModel.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt`

**Interfaces:**
- Consumes: `RecortesRepo` (Task 2).
- Produces:
  - `class RecortesViewModel(recortesRepo, ioDispatcher)` con `val recortes: StateFlow<List<Recorte>>`, `fun cargar()`, `fun borrar(id: String)`
  - `@Composable fun ListaRecortesScreen(vm, onAbrirRecorte: (String) -> Unit, onScan: () -> Unit)`
  - `BibliotecaScreen` pierde el parámetro `onScan` (se muda a la pestaña Notes)

- [ ] **Step 1: Escribir el ViewModel**

`RecortesViewModel` sigue el patrón de `BibliotecaViewModel`: `MutableStateFlow` privado + `StateFlow` público, carga en `viewModelScope.launch { withContext(ioDispatcher) { ... } }`. Leer `BibliotecaViewModel.kt` y calcar la forma.

```kotlin
class RecortesViewModel(
    private val recortesRepo: RecortesRepo,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _recortes = MutableStateFlow<List<Recorte>>(emptyList())
    val recortes: StateFlow<List<Recorte>> = _recortes

    fun cargar() {
        viewModelScope.launch {
            _recortes.value = withContext(ioDispatcher) { recortesRepo.listar() }
        }
    }

    fun borrar(id: String) {
        viewModelScope.launch {
            withContext(ioDispatcher) { recortesRepo.borrar(id) }
            cargar()
        }
    }
}
```

- [ ] **Step 2: Escribir la lista**

`ListaRecortesScreen`: `Scaffold` con `TopAppBar` cuyo único action es `TextButton(onClick = onScan) { Text("Scan") }`, y una `LazyColumn` de `Card`s.

Cada card muestra los primeros 40 caracteres del texto en una línea (`maxLines = 2`, `overflow = TextOverflow.Ellipsis`) y la fecha relativa debajo, en `labelSmall` con `onSurfaceVariant`. Fecha relativa con `DateUtils.getRelativeTimeSpanString(timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)` — es de Android, no hace falta librería.

Long-press abre un `AlertDialog` de confirmación de borrado. Strings en inglés: `"Delete note?"`, `"This also deletes its image."`, `"Delete"`, `"Cancel"`.

Estado vacío: `Text("No notes yet. Tap Scan to capture text from another app.")` centrado.

- [ ] **Step 3: Poner las pestañas en Biblioteca**

En `BibliotecaScreen`, envolver el contenido en un `Column` con un `TabRow` de dos `Tab`: `"Stories"` y `"Notes"`. El índice vive en `rememberSaveable { mutableStateOf(0) }` para sobrevivir rotación.

Quitar `TextButton(onClick = onScan) { Text("Scan") }` de las `actions` del `TopAppBar` y borrar el parámetro `onScan` de la firma. El top bar queda con tres acciones: `Import`, `Export`, `About` — que era el punto: el paso 1 del smoke del Plan E marcaba el riesgo de que cuatro labels no entraran en un teléfono en vertical.

La pestaña Notes renderiza `ListaRecortesScreen`.

- [ ] **Step 4: Actualizar el call site en MainActivity**

Quitar `onScan = { ... }` de la llamada a `BibliotecaScreen` y agregar el `RecortesViewModel` con `viewModelFactory`, siguiendo el patrón de los demás.

- [ ] **Step 5: Verificar que compila y que la suite pasa**

```bash
cd app && ./gradlew assembleDebug test
```

Esperado: BUILD SUCCESSFUL, 271 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/ \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt
git commit -m "feat(recortes): pestañas Stories/Notes y lista de notas"
```

---

### Task 7: Vista de nota

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/recortes/RecorteScreen.kt`
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/recortes/RecorteViewModel.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt`

**Interfaces:**
- Consumes: `OracionPlana`, `ItemOracion`, `aplanar` (Task 5); `RecortesRepo`, `CreadorRecortes`, `Tokenizador`, `BuscadorPalabras`, `PalabraSheet`.
- Produces: `@Composable fun RecorteScreen(vm, onVerKanji: (String) -> Unit, onCerrar: () -> Unit)`; ruta `"recorte/{id}"`.

- [ ] **Step 1: Escribir el ViewModel**

`RecorteViewModel(id, recortesRepo, tokenizador, buscador, generadorFurigana, ioDispatcher)` expone:
- `val estado: StateFlow<EstadoRecorte>` con `recorte`, `planas: List<OracionPlana>`, `editando: Boolean`, `textoEditado: String`, `imagenExpandida: Boolean`, `consulta: ConsultaPalabra?`
- `fun cargar()` — carga el recorte y llama a `aplanar(...)` en `ioDispatcher`
- `fun onTapPalabra(token)` — igual que `LectorViewModel`: `buscador.consultar(token)` + registrar en `palabras_tocadas` con `idHistoria = "recorte:$id"`
- `fun empezarEdicion()` / `fun setTextoEditado(t)` / `fun guardarEdicion()` — regenera párrafos vía el mismo pipeline y persiste
- `fun alternarImagen()`
- `fun quitarImagen()`

**Clave para la Task 9**: el `idHistoria` que se registra es `"recorte:$id"`. Ese prefijo es lo que separa el mazo Scans del mazo Words. Los dos puntos no son arbitrarios: `ImportadorHistoria.generarId()` sanea los títulos con `[\/:*?"<>|.\s　]+` → `_`, así que **un id de historia nunca puede contener `:`**, mientras que un `-` sí colisionaría (una historia titulada "recorte-123" produce el id `recorte-123`).

- [ ] **Step 2: Escribir la pantalla**

`Scaffold` con `TopAppBar` cuyas actions son `Edit` (o `Save` en modo edición), `Remove image` (solo si `tieneImagen`) y `Close`.

Contenido, en una `LazyColumn`:
1. **Miniatura**, solo si `tieneImagen` y el archivo existe. Colapsada por defecto: una fila `Text("Image")` + chevron que alterna `imagenExpandida`. Expandida muestra el JPEG. Cargarlo con `BitmapFactory.decodeFile(...)` dentro de un `remember(id)` — es un archivo local, no hace falta una librería de imágenes para una sola foto por pantalla.
2. **El texto**: `items(planas) { plana -> ItemOracion(esActual = true, plana = plana, ...) }`. `esActual = true` en todas: el atenuado por foco es del lector paginado y acá no hay oración "actual".
3. En modo edición, el bloque se reemplaza por un `OutlinedTextField` con `textoEditado`.

`PalabraSheet` en un `ModalBottomSheet` cuando `consulta != null`, igual que `LectorScreen`.

`Remove image` abre confirmación: `"Remove image?"`, `"The note keeps its text. This cannot be undone."`, `"Remove"`, `"Cancel"`.

- [ ] **Step 3: Rutear**

En `MainActivity`, agregar:

```kotlin
                    composable("recorte/{id}") { entrada ->
                        val id = entrada.arguments!!.getString("id")!!
                        val vm: RecorteViewModel = viewModel(factory = viewModelFactory {
                            initializer {
                                RecorteViewModel(
                                    id, contenedor.recortes, contenedor.tokenizador,
                                    contenedor.buscador, GeneradorFurigana(contenedor.tokenizador),
                                    contenedor.progresoDb.dao(),
                                )
                            }
                        })
                        RecorteScreen(vm, onVerKanji = { k -> nav.navigate("kanji/$k") },
                            onCerrar = { nav.popBackStack() })
                    }
```

Y conectar `onAbrirRecorte = { id -> nav.navigate("recorte/$id") }` en la lista.

- [ ] **Step 4: Verificar que compila y que la suite pasa**

```bash
cd app && ./gradlew assembleDebug test
```

Esperado: BUILD SUCCESSFUL, 271 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/recortes/ \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt
git commit -m "feat(recortes): vista de nota con imagen colapsable y edición"
```

---

### Task 8: Cablear captura → recorte

Cambia el destino del texto capturado: de `ImportScreen` a un `Recorte` recién creado.

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt`

**Interfaces:**
- Consumes: `ScreenCaptureService.EXTRA_TEXTO_OCR` y `.EXTRA_RUTA_IMAGEN` (Task 4); `CreadorRecortes` (Task 3); ruta `"recorte/{id}"` (Task 7).

**Lo que NO se toca:** toda la máquina de consumo-única del Plan E — limpiar el `Intent` al consumirlo (`action = null` + `removeExtra`), `launchSingleTop`, y el guard `rememberSaveable` del diálogo de MediaProjection. Esa lógica costó tres rondas de review y cubre rotación, back desde la pantalla siguiente, y segunda captura con la pantalla ya abierta. Lo único que cambia es a dónde navega.

- [ ] **Step 1: Leer también la ruta de la imagen**

Ampliar `leerIntent` para capturar ambos extras en un solo estado pendiente:

```kotlin
    /** Texto + imagen que dejó ScreenCaptureService, esperando a que se convierta en
     *  recorte. Es estado de la Activity y no del NavHost porque puede llegar por
     *  onNewIntent con la app ya abierta (launchMode singleTop). */
    private val capturaPendiente = mutableStateOf<Pair<String, String?>?>(null)
```

Y en `leerIntent`, guardar `texto to intent.getStringExtra(EXTRA_RUTA_IMAGEN)`. Conservar el borrado del Intent al consumirlo: `removeExtra` de **ambos** extras.

- [ ] **Step 2: Crear el recorte y navegar**

Reemplazar el `LaunchedEffect` que navegaba a `"importar"`:

```kotlin
                val captura by capturaPendiente
                LaunchedEffect(captura) {
                    val actual = captura ?: return@LaunchedEffect
                    capturaPendiente.value = null   // consumido antes de la IO: no se repite al rotar
                    val (texto, ruta) = actual
                    val recorte = withContext(Dispatchers.IO) {
                        runCatching {
                            contenedor.creadorRecortes.crear(texto, ruta?.let(::File))
                        }
                    }
                    recorte.onSuccess { nav.navigate("recorte/${it.id}") { launchSingleTop = true } }
                    recorte.onFailure { android.util.Log.e("MainActivity", "no se pudo crear el recorte", it) }
                }
```

`crear()` hace Kuromoji sobre el texto: va en `Dispatchers.IO`, nunca en el main thread.

- [ ] **Step 3: Devolver `ImportScreen` a su estado previo**

Quitar de la ruta `"importar"` el `LaunchedEffect` que precargaba texto y título, y borrar la función `tituloDeCaptura()` y sus imports de `java.time`. `ImportScreen` vuelve a ser solo el import manual, que es para lo que existe.

- [ ] **Step 4: Verificar que compila y que la suite pasa**

```bash
cd app && ./gradlew assembleDebug test
```

Esperado: BUILD SUCCESSFUL, 271 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt
git commit -m "feat(recortes): la captura crea un recorte en vez de una historia"
```

---

### Task 9: Mazo de Anki separado

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/datos/progreso/ProgresoDb.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazos.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ModeloNotas.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportViewModel.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/SeparacionMazosTest.kt`

**Interfaces:**
- Consumes: el prefijo `"recorte:"` que registra `RecorteViewModel` (Task 7); `RecortesRepo` (Task 2).
- Produces:
  - `ProgresoDao.palabrasDeHistorias()` y `.palabrasDeRecortes()`
  - `ModeloNotas.NOMBRE_DECK_SCANS = "Dokusho — Scans"`
  - `ArmadorMazos.armarScans(recortes: List<Recorte>): List<NotaWords>`

**El punto de falla silenciosa:** `armarWords()` usa hoy `progresoDao.todasPalabras()`, sin filtrar. Si esta task se hace a medias, las palabras de recortes se cuelan en el mazo Words y nadie se entera. El test del Step 4 es la única garantía real: el prefijo es una convención de string que no está tipada en ningún lado.

- [ ] **Step 1: Agregar las queries filtradas**

En `ProgresoDao`, junto a `todasPalabras()`:

```kotlin
    /** El prefijo `recorte:` separa las palabras tocadas en recortes de las tocadas
     *  en historias, sin migración de Room (idHistoria ya es TEXT). Un id de historia
     *  nunca puede contener `:` porque ImportadorHistoria.generarId() lo sanea. */
    @Query("SELECT * FROM palabras_tocadas WHERE idHistoria NOT LIKE 'recorte:%'")
    suspend fun palabrasDeHistorias(): List<PalabraTocada>

    @Query("SELECT * FROM palabras_tocadas WHERE idHistoria LIKE 'recorte:%'")
    suspend fun palabrasDeRecortes(): List<PalabraTocada>
```

- [ ] **Step 2: Cambiar `armarWords` y agregar `armarScans`**

En `ArmadorMazos`, cambiar la primera línea de `armarWords`:

```kotlin
        val terminos = progresoDao.palabrasDeHistorias().map { it.termino }.distinct()
```

Y agregar:

```kotlin
    /** Mazo "Dokusho — Scans": las palabras tocadas en recortes, con las oraciones de
     *  ejemplo salidas del propio recorte (o de Tatoeba si no aporta ninguna usable). */
    suspend fun armarScans(recortes: List<Recorte> = recortesRepo.listar()): List<NotaWords> {
        val terminos = progresoDao.palabrasDeRecortes().map { it.termino }.distinct()
        return terminos.map { termino -> armarNotaWords(termino, recortes.map { it.parrafos }) }
    }
```

**Refactor que esto requiere:** `armarNotaWords(termino, historias: List<Historia>)` pasa a recibir `List<List<Parrafo>>`. Los call sites existentes le pasan `historias.map { it.parrafos }`. Es el mismo shape: `Historia.parrafos` y `Recorte.parrafos` son ambos `List<Parrafo>`. Hacer lo mismo con `oracionesDeLaHistoria` si lo necesita.

`ArmadorMazos` suma `recortesRepo: RecortesRepo` a su constructor; actualizar el `Contenedor` en `App.kt`.

- [ ] **Step 3: Agregar el nombre del mazo y cablear el export**

En `ModeloNotas`, junto a los otros:

```kotlin
    const val NOMBRE_DECK_SCANS: String = "Dokusho — Scans"
```

En `ExportViewModel`, agregar el mazo Scans a las opciones de export, siguiendo el patrón de Words/Kanji. Strings de UI en inglés.

- [ ] **Step 4: Escribir el test que fija la separación**

Crear `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/SeparacionMazosTest.kt`. Usar los fakes que ya existen en `datos/Fakes.kt` (leerlo primero).

```kotlin
package com.tatoh.dokushorenshu.dominio.anki

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeparacionMazosTest {

    /** Palabras tocadas: 川上 en una historia, 稲妻 en un recorte. Cada mazo tiene que
     *  llevarse SOLO la suya. El prefijo `recorte:` es una convención de string sin
     *  tipo que lo respalde — este test es la única garantía de que la separación
     *  no se rompa en silencio. */
    @Test
    fun `el mazo Words excluye las palabras de recortes`() = runTest {
        val armador = armadorCon(
            palabrasEnHistoria = listOf("momotaro" to "川上"),
            palabrasEnRecorte = listOf("recorte:100" to "稲妻"),
        )
        val terminos = armador.armarWords().map { it.termino }
        assertTrue(terminos.contains("川上"))
        assertFalse("稲妻 salió de un recorte y no va al mazo Words", terminos.contains("稲妻"))
    }

    @Test
    fun `el mazo Scans excluye las palabras de historias`() = runTest {
        val armador = armadorCon(
            palabrasEnHistoria = listOf("momotaro" to "川上"),
            palabrasEnRecorte = listOf("recorte:100" to "稲妻"),
        )
        val terminos = armador.armarScans().map { it.termino }
        assertTrue(terminos.contains("稲妻"))
        assertFalse("川上 salió de una historia y no va al mazo Scans", terminos.contains("川上"))
    }

    @Test
    fun `sin recortes tocados el mazo Scans queda vacio`() = runTest {
        val armador = armadorCon(
            palabrasEnHistoria = listOf("momotaro" to "川上"),
            palabrasEnRecorte = emptyList(),
        )
        assertEquals(emptyList<String>(), armador.armarScans().map { it.termino })
    }
}
```

El helper `armadorCon(...)` construye un `ArmadorMazos` con un `ProgresoDao` fake que devuelve esas palabras y un diccionario fake. Modelarlo sobre los fakes de `datos/Fakes.kt`; si esos fakes no cubren `ProgresoDao`, agregar uno ahí siguiendo el mismo estilo.

- [ ] **Step 5: Correr el test y la suite**

```bash
cd app && ./gradlew testDebugUnitTest --tests "com.tatoh.dokushorenshu.dominio.anki.SeparacionMazosTest"
cd app && ./gradlew assembleDebug test
```

Esperado: 274 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/ \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/SeparacionMazosTest.kt
git commit -m "feat(recortes): mazo Anki propio para el vocabulario de recortes"
```

---

### Task 10: Documentación y smoke

**Files:**
- Modify: `docs/ESTADO.md`
- Create: `docs/smoke-recortes.md`
- Modify: `app/README.md`

- [ ] **Step 1: Escribir el checklist de smoke**

Crear `docs/smoke-recortes.md`. Nada de esto se puede testear sin dispositivo, y es la única verificación que la feature va a tener. Cada paso dice qué esperar, para que una falla sea reconocible:

1. Biblioteca muestra dos pestañas, `Stories` y `Notes`. El top bar tiene tres acciones (`Import` `Export` `About`) y entran holgadas en vertical.
2. Pestaña Notes vacía: mensaje explicativo y botón `Scan`.
3. Capturar texto japonés desde otra app. Esperado: se abre **la nota**, no `Import`.
4. La nota muestra el texto con furigana. Tap en una palabra → `PalabraSheet` con definición.
5. La miniatura de la imagen aparece **colapsada**. Expandirla muestra la captura original.
6. `Edit` → corregir el texto → `Save`. Esperado: la furigana se regenera sobre el texto nuevo.
7. Volver a Notes: la nota aparece con sus primeros caracteres y la fecha relativa.
8. **Rotar en la nota.** Esperado: no se pierde el texto ni el estado de edición.
9. **Segunda captura seguida.** Esperado: dos notas distintas, la nueva arriba.
10. `Remove image` → confirmar. Esperado: la miniatura desaparece, el texto queda.
11. Long-press en la lista → borrar. Esperado: desaparece la nota; verificar con `adb shell run-as com.tatoh.dokushorenshu ls files/recortes/` que se fueron **el `.json` y el `.jpg`**.
12. `Export` → mazo `Dokusho — Scans`. Esperado: contiene las palabras tocadas en notas y **ninguna** de las tocadas en historias. Verificar lo inverso en `Dokusho — Words`.
13. **Espacio en disco**: tras ~10 capturas, `adb shell run-as com.tatoh.dokushorenshu du -sh files/recortes/`. Esperado: del orden de unos pocos MB. Si da mucho más, revisar que se esté guardando JPEG y no PNG.
14. Import manual (`Import` en el top bar) sigue funcionando y crea una **historia**, no una nota.

- [ ] **Step 2: Actualizar `ESTADO.md`**

Agregar una fila a la tabla y un bullet denso en "Datos operativos", en el estilo de los que ya están: el modelo `Recorte` y su formato en disco, el discriminador `recorte:` con el motivo de los dos puntos, el JPEG 90 y por qué no PNG, el slot fijo de la imagen pendiente, el refactor de `ui/comun`, y el enlace a `docs/smoke-recortes.md` diciendo claramente si se corrió o no.

- [ ] **Step 3: Actualizar `app/README.md`**

Agregar un bullet de arquitectura para `datos/RecortesRepo` + `ui/recortes/`, en el estilo de los existentes.

- [ ] **Step 4: Commit**

```bash
git add docs/ app/README.md
git commit -m "docs: recortes en ESTADO, arquitectura y checklist de smoke"
```

---

## Self-Review

**Cobertura del spec:**

| Requisito del spec | Task |
|---|---|
| Modelo `Recorte` sin la metadata de `Historia` | 1 |
| Serialización propia | 1 |
| `filesDir/recortes/`, escritura atómica | 2 |
| Imagen JPEG 90, `tieneImagen`, borrado | 2, 4, 7 |
| Slot fijo `captura-pendiente.jpg` | 2, 4 |
| Ids sin colisión | 2 |
| Listado que saltea corruptos | 2 |
| Pipeline texto → recorte | 3 |
| El Service amplía el contrato con la ruta de la imagen | 4 |
| Promover `ItemOracion` a compartido | 5 |
| Pestañas Stories/Notes, `Scan` se muda | 6 |
| Lista de notas con fecha relativa y borrado | 6 |
| Vista de nota: bloque, miniatura colapsada, edición | 7 |
| `Remove image` | 7 |
| Captura → recorte (ya no `ImportScreen`) | 8 |
| Queries filtradas y mazo Scans | 9 |
| Test que fija la separación de mazos | 9 |
| Oraciones de ejemplo desde el recorte | 9 |
| Errores: imagen fallida no cuesta el texto | 2, 4 |
| Smoke | 10 |

**Consistencia de tipos:** `Recorte` se define en Task 1 y se consume con la misma forma en 2, 3, 7 y 9. `RecortesRepo.imagenPendiente()`/`NOMBRE_PENDIENTE` se definen en 2 y se usan en 4. `EXTRA_RUTA_IMAGEN` se define en 4 y se lee en 8. `OracionPlana`/`ItemOracion`/`aplanar` se mueven en 5 y se consumen en 7. El prefijo `"recorte:"` lo escribe la Task 7 y lo leen las queries de la Task 9 — **si una de las dos cambia el literal, el test de la Task 9 es lo único que lo detecta**.

**Dependencia de conteos de tests:** cada task declara el total esperado acumulado (248 → 253 → 263 → 271 → 274). Si una task anterior agrega o quita tests, los totales de las siguientes se corren: usar el delta declarado, no el absoluto, si no coinciden.
