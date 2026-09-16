# Captura OCR con overlay flotante — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Traer la captura de pantalla con overlay flotante + OCR japonés de `Kanji-no-Ryoushi` a Dokusho, terminando en el pipeline de import que ya existe, y dar de baja el repo viejo.

**Architecture:** Los dos `Service` de Android se portan casi tal cual (ya son Kotlin nativo probado en dispositivo). El OCR pasa a correr dentro del `ScreenCaptureService` con ML Kit, que le entrega a `MainActivity` solo el texto por `Intent`. `MainActivity` precarga `ImportScreen` con ese texto; de ahí para abajo todo es el flujo de import existente (furigana, tokens tappeables, `PalabraSheet`, progreso, Anki). La lógica que históricamente rompió — escalado del recorte y unión de bloques OCR — se extrae a funciones JVM puras con tests.

**Tech Stack:** Kotlin, Jetpack Compose M3, ML Kit `text-recognition-japanese` 16.0.1, MediaProjection, `WindowManager` overlay, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-16-captura-ocr-overlay-design.md`

**Repo origen del código portado:** `../Kanji-no-Ryoushi` (clonado en `/Users/tatoh/Repos/Personal/Kanji-no-Ryoushi`, main `79ad93d`).

## Global Constraints

- **minSdk sigue en 26.** No se sube. La captura se ofrece solo en Android 10+ (`Build.VERSION_CODES.Q`, API 29); por debajo la entrada de UI queda deshabilitada con explicación.
- **compileSdk/targetSdk 36, JDK 17+** (probado con JDK 21). Builds JVM van en la PC secundaria.
- **Nombres y comentarios en español**, UI en inglés — convención del repo.
- **Comentarios que explican el *por qué*, no el *qué*.** Al portar código, los comentarios existentes sobre MIUI y Android 14 se conservan textualmente: documentan bugs reales de dispositivo.
- **Package base:** `com.tatoh.dokushorenshu`.
- **Dependencia nueva única:** `com.google.mlkit:text-recognition-japanese:16.0.1` (publicada 2024-08-07, mucho más de 7 días de antigüedad — chequeo de supply-chain OK). No se agrega `google_mlkit_language_id`: `DetectorJapones` ya cubre "¿esto es japonés?".
- **Los commits NO llevan líneas de co-autoría ni atribución a agentes.**
- **Ningún push ni `gh` sobre repos remotos sin pedido explícito del usuario.** La Task 6 se detiene antes de pushear.
- **Directorio de trabajo de Gradle:** `app/` (el proyecto Gradle es `app/`, el módulo es `app/app/`).

---

### Task 1: Lógica pura de recorte y unión de bloques

La única lógica del flujo que se puede testear sin dispositivo, y la que más veces
rompió en la versión Flutter (coordenadas de selección mal escaladas al bitmap).
Se extrae antes de portar nada, para que los `Service` la consuman ya probada.

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/ocr/TextoOcr.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/ocr/TextoOcrTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `data class Recorte(val left: Int, val top: Int, val ancho: Int, val alto: Int)`
  - `data class BloqueOcr(val lineas: List<String>)`
  - `fun escalarRecorte(seleccion: Recorte, anchoOverlay: Int, altoOverlay: Int, anchoBitmap: Int, altoBitmap: Int): Recorte?`
  - `fun unirBloques(bloques: List<BloqueOcr>): String`

- [ ] **Step 1: Escribir el test que falla**

Crear `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/ocr/TextoOcrTest.kt`:

```kotlin
package com.tatoh.dokushorenshu.dominio.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EscalarRecorteTest {
    @Test
    fun `overlay del mismo tamanio que el bitmap no escala`() {
        val r = escalarRecorte(Recorte(10, 20, 100, 50), 1080, 2400, 1080, 2400)
        assertEquals(Recorte(10, 20, 100, 50), r)
    }

    @Test
    fun `overlay mas chico que el bitmap duplica las coordenadas`() {
        // caso real: el overlay mide menos que la pantalla capturada por las barras
        // de sistema; sin escalar, el recorte cae corrido hacia arriba-izquierda.
        val r = escalarRecorte(Recorte(10, 20, 100, 50), 540, 1200, 1080, 2400)
        assertEquals(Recorte(20, 40, 200, 100), r)
    }

    @Test
    fun `seleccion que se sale del bitmap devuelve null`() {
        assertNull(escalarRecorte(Recorte(1000, 0, 200, 50), 1080, 2400, 1080, 2400))
    }

    @Test
    fun `seleccion degenerada devuelve null`() {
        assertNull(escalarRecorte(Recorte(10, 20, 0, 50), 1080, 2400, 1080, 2400))
        assertNull(escalarRecorte(Recorte(10, 20, 100, 0), 1080, 2400, 1080, 2400))
    }

    @Test
    fun `overlay de tamanio cero devuelve null sin dividir por cero`() {
        assertNull(escalarRecorte(Recorte(10, 20, 100, 50), 0, 0, 1080, 2400))
    }
}

class UnirBloquesTest {
    @Test
    fun `lineas del mismo bloque se concatenan sin separador`() {
        // el japonés no usa espacios; en manga vertical cada columna es una Line
        // del mismo globo y concatenarlas da el orden de lectura.
        val texto = unirBloques(listOf(BloqueOcr(listOf("日本語の", "テキスト"))))
        assertEquals("日本語のテキスト", texto)
    }

    @Test
    fun `bloques distintos se separan con salto de linea`() {
        val texto = unirBloques(listOf(BloqueOcr(listOf("おはよう")), BloqueOcr(listOf("こんばんは"))))
        assertEquals("おはよう\nこんばんは", texto)
    }

    @Test
    fun `bloques vacios o en blanco se descartan`() {
        val texto = unirBloques(
            listOf(BloqueOcr(listOf("あ")), BloqueOcr(listOf("  ")), BloqueOcr(emptyList()), BloqueOcr(listOf("い")))
        )
        assertEquals("あ\nい", texto)
    }

    @Test
    fun `sin bloques devuelve cadena vacia`() {
        assertEquals("", unirBloques(emptyList()))
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

```bash
cd app && ./gradlew test --tests "com.tatoh.dokushorenshu.dominio.ocr.*"
```

Esperado: FAIL de compilación — `Unresolved reference: escalarRecorte` / `Recorte` / `BloqueOcr` / `unirBloques`.

- [ ] **Step 3: Escribir la implementación mínima**

Crear `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/ocr/TextoOcr.kt`:

```kotlin
package com.tatoh.dokushorenshu.dominio.ocr

/** Rectángulo en píxeles. Tipo propio y no android.graphics.Rect para que el
 *  escalado —la parte que más veces rompió— se pueda testear en JVM plano, sin
 *  Robolectric. */
data class Recorte(val left: Int, val top: Int, val ancho: Int, val alto: Int)

/** Un TextBlock de ML Kit reducido a lo único que se usa: el texto de sus líneas. */
data class BloqueOcr(val lineas: List<String>)

/** Lleva la selección hecha sobre el overlay a las coordenadas del bitmap capturado.
 *  Los dos tamaños difieren: el overlay no cubre las barras de sistema y el bitmap
 *  sí. Sin este escalado el recorte sale corrido.
 *  Devuelve null si el resultado no es un rectángulo usable (fuera del bitmap,
 *  degenerado, o con un overlay de tamaño cero) — el llamador usa el bitmap entero. */
fun escalarRecorte(
    seleccion: Recorte,
    anchoOverlay: Int,
    altoOverlay: Int,
    anchoBitmap: Int,
    altoBitmap: Int,
): Recorte? {
    if (anchoOverlay <= 0 || altoOverlay <= 0) return null
    val escalaX = anchoBitmap.toDouble() / anchoOverlay
    val escalaY = altoBitmap.toDouble() / altoOverlay
    val left = (seleccion.left * escalaX).toInt()
    val top = (seleccion.top * escalaY).toInt()
    val right = ((seleccion.left + seleccion.ancho) * escalaX).toInt()
    val bottom = ((seleccion.top + seleccion.alto) * escalaY).toInt()
    if (left < 0 || top < 0 || right > anchoBitmap || bottom > altoBitmap) return null
    if (right - left <= 0 || bottom - top <= 0) return null
    return Recorte(left, top, right - left, bottom - top)
}

/** Une la salida de ML Kit en texto plano con el formato que espera
 *  ImportadorHistoria (una línea no vacía = un párrafo).
 *
 *  Líneas del mismo bloque van pegadas sin separador: el japonés no usa espacios
 *  y en texto vertical cada columna del mismo globo es una Line.
 *
 *  ponytail: se asume que ML Kit devuelve las Line de un bloque en orden de
 *  lectura. No está garantizado para vertical derecha-a-izquierda; si en uso real
 *  aparecen columnas desordenadas, ordenar por boundingBox antes de unir. Mientras
 *  tanto el texto queda editable en ImportScreen. */
fun unirBloques(bloques: List<BloqueOcr>): String =
    bloques
        .map { bloque -> bloque.lineas.joinToString("").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
```

- [ ] **Step 4: Correr el test y verificar que pasa**

```bash
cd app && ./gradlew test --tests "com.tatoh.dokushorenshu.dominio.ocr.*"
```

Esperado: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/ocr/TextoOcr.kt \
        app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/ocr/TextoOcrTest.kt
git commit -m "feat(ocr): escalado de recorte y unión de bloques como funciones puras"
```

---

### Task 2: Dependencia ML Kit y reconocedor japonés

El envoltorio fino sobre ML Kit. La lógica testeable ya salió en la Task 1; lo que
queda acá es la llamada al SDK, que no se puede testear sin dispositivo.

**Files:**
- Modify: `app/gradle/libs.versions.toml`
- Modify: `app/app/build.gradle.kts`
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/ocr/OcrJapones.kt`

**Interfaces:**
- Consumes: `unirBloques`, `BloqueOcr` (Task 1).
- Produces:
  - `class OcrJapones` con `fun reconocer(bitmap: Bitmap): String` (bloqueante, **nunca desde el main thread**) y `fun cerrar()`.

- [ ] **Step 1: Agregar la dependencia al catálogo de versiones**

En `app/gradle/libs.versions.toml`, agregar en `[versions]` después de `kuromoji = "0.9.0"`:

```toml
mlkitTextJa = "16.0.1"
```

y en `[libraries]` después de la línea de `kuromoji-ipadic`:

```toml
mlkit-text-recognition-japanese = { group = "com.google.mlkit", name = "text-recognition-japanese", version.ref = "mlkitTextJa" }
```

- [ ] **Step 2: Declarar la dependencia en el módulo**

En `app/app/build.gradle.kts`, en el bloque `dependencies`, después de `implementation(libs.kuromoji.ipadic)`:

```kotlin
    // OCR japonés on-device. El artefacto trae el modelo embebido: funciona offline
    // y sin Play Services, a cambio de ~15 MB de APK.
    implementation(libs.mlkit.text.recognition.japanese)
```

- [ ] **Step 3: Escribir el reconocedor**

Crear `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/ocr/OcrJapones.kt`:

```kotlin
package com.tatoh.dokushorenshu.dominio.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/** OCR japonés on-device. El TextRecognizer es caro de crear: una instancia por
 *  app (vive en el Contenedor). */
class OcrJapones(
    private val reconocedor: TextRecognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
) {
    /** Bloquea hasta tener el texto. ML Kit devuelve un Task asíncrono y Tasks.await()
     *  LANZA si se llama desde el main thread — el llamador (ScreenCaptureService)
     *  tiene que invocarlo desde un hilo de fondo.
     *  Timeout de 15 s: si el modelo se traba, preferimos texto vacío a un hilo colgado. */
    fun reconocer(bitmap: Bitmap): String {
        val entrada = InputImage.fromBitmap(bitmap, 0)
        val resultado = Tasks.await(reconocedor.process(entrada), 15, TimeUnit.SECONDS)
        return unirBloques(
            resultado.textBlocks.map { bloque -> BloqueOcr(bloque.lines.map { it.text }) }
        )
    }

    fun cerrar() = reconocedor.close()
}
```

- [ ] **Step 4: Exponerlo en el contenedor de DI**

En `app/app/src/main/kotlin/com/tatoh/dokushorenshu/App.kt`, agregar el import y la
propiedad lazy dentro de `class Contenedor`, después de `val buscador`:

```kotlin
import com.tatoh.dokushorenshu.dominio.ocr.OcrJapones
```

```kotlin
    val ocr by lazy { OcrJapones() }
```

- [ ] **Step 5: Verificar que compila y que los tests siguen pasando**

```bash
cd app && ./gradlew assembleDebug test
```

Esperado: BUILD SUCCESSFUL.

- [ ] **Step 6: Medir el peso que suma el modelo**

```bash
cd app && ./gradlew assembleRelease && ls -lh app/build/outputs/apk/release/*.apk
```

Esperado: un APK notablemente más grande que los 42.7 MB de la release vigente
(estimado 55-60 MB). Anotar el número exacto para `ESTADO.md` en la Task 6.

- [ ] **Step 7: Commit**

```bash
git add app/gradle/libs.versions.toml app/app/build.gradle.kts \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/ocr/OcrJapones.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/App.kt
git commit -m "feat(ocr): reconocedor japonés on-device con ML Kit"
```

---

### Task 3: Portar los dos Services

El grueso del código que se migra. Son ~1000 LOC ya probadas en dispositivo, con
workarounds de MIUI y de los `foregroundServiceType` de Android 14 que costaron
caro: **se copian los archivos enteros y se editan, no se reescriben**.

Cambios reales respecto del original, más allá del rename de package:

1. Se borra todo el puente a Flutter (`captureCallback`, `permissionExpiredCallback`).
   El Service hace el OCR y entrega el **texto** por `Intent`.
2. Se borran las ramas de SDK < 26 (`TYPE_PHONE`, `startService` sin foreground,
   `Settings.canDrawOverlays` condicional): con minSdk 26 son código muerto.
3. El escalado del recorte pasa a usar `escalarRecorte` de la Task 1.
4. `stopForeground(true)` → `stopForeground(STOP_FOREGROUND_REMOVE)`.

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/ScreenCaptureService.kt`
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/FloatingBubbleService.kt`

**Interfaces:**
- Consumes: `Recorte`, `escalarRecorte` (Task 1); `OcrJapones` (Task 2); `MainActivity` (existente).
- Produces:
  - `ScreenCaptureService.ACTION_START_CAPTURE`, `.EXTRA_RESULT_CODE`, `.EXTRA_RESULT_DATA`
  - `ScreenCaptureService.ACTION_TEXTO_OCR`, `.EXTRA_TEXTO_OCR` (contrato con `MainActivity`, Task 5)
  - `FloatingBubbleService.ACTION_START_BUBBLE`, `.ACTION_STOP_BUBBLE`, `.ACTION_PEDIR_PERMISO`
  - `FloatingBubbleService.isRunning: Boolean`, `.captureResultCode: Int`, `.captureResultData: Intent?`
  - `FloatingBubbleService.hideBubble()`, `.showBubble()`

- [ ] **Step 1: Copiar los archivos y renombrar el package**

```bash
ORIGEN=/Users/tatoh/Repos/Personal/Kanji-no-Ryoushi/android/app/src/main/kotlin/com/example/kanji_no_ryoushi
DESTINO=app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura
mkdir -p "$DESTINO"
cp "$ORIGEN/ScreenCaptureService.kt" "$ORIGEN/FloatingBubbleService.kt" "$DESTINO/"
sed -i '' \
  -e 's/^package com\.example\.kanji_no_ryoushi$/package com.tatoh.dokushorenshu.captura/' \
  -e 's/com\.example\.kanji_no_ryoushi\./com.tatoh.dokushorenshu.captura./g' \
  "$DESTINO/ScreenCaptureService.kt" "$DESTINO/FloatingBubbleService.kt"
grep -rn "kanji_no_ryoushi" "$DESTINO" || echo "sin referencias al repo viejo: OK"
```

Esperado: `sin referencias al repo viejo: OK`.

- [ ] **Step 2: Arreglar los imports del código portado**

En **ambos** archivos, agregar debajo del `package`:

```kotlin
import com.tatoh.dokushorenshu.MainActivity
import com.tatoh.dokushorenshu.R
```

En `ScreenCaptureService.kt`, agregar además:

```kotlin
import com.tatoh.dokushorenshu.App
import com.tatoh.dokushorenshu.dominio.ocr.Recorte
import com.tatoh.dokushorenshu.dominio.ocr.escalarRecorte
```

- [ ] **Step 3: Reemplazar el bloque `companion object` de `ScreenCaptureService`**

Buscar y reemplazar el `companion object` entero. Antes:

```kotlin
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "screen_capture_channel"
        const val ACTION_START_CAPTURE = "com.tatoh.dokushorenshu.captura.START_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        var captureCallback: ((ByteArray?) -> Unit)? = null
        var permissionExpiredCallback: (() -> Unit)? = null
    }
```

Después:

```kotlin
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "screen_capture_channel"
        const val ACTION_START_CAPTURE = "com.tatoh.dokushorenshu.captura.START_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** Contrato con MainActivity: el Service ya hizo el OCR y manda solo el texto.
         *  La versión Flutter mandaba el PNG crudo por MethodChannel con un callback
         *  estático; nativo no lo necesita y el texto entra holgado en un Intent. */
        const val ACTION_TEXTO_OCR = "com.tatoh.dokushorenshu.captura.TEXTO_OCR"
        const val EXTRA_TEXTO_OCR = "texto_ocr"
    }
```

- [ ] **Step 4: Reemplazar `processCapture()` entero**

Es el único cambio estructural: el OCR corre en un hilo de fondo (`Tasks.await`
lanza si se lo llama desde el main thread) y el bitmap se recicla recién después.

```kotlin
    private fun processCapture() {
        android.util.Log.d("ScreenCapture", "=== processCapture() INICIADO ===")

        val image = try {
            imageReader?.acquireLatestImage()
        } catch (e: Exception) {
            android.util.Log.e("ScreenCapture", "Error adquiriendo imagen", e)
            null
        }

        if (image == null) {
            android.util.Log.e("ScreenCapture", "No se pudo obtener imagen del ImageReader")
            cerrarConDemora()
            return
        }

        val bitmap = imageToBitmap(image)
        image.close()
        android.util.Log.d("ScreenCapture", "Bitmap creado: ${bitmap.width}x${bitmap.height}")

        val seleccion = selectionView?.getSelectionRect()
        val anchoOverlay = selectionView?.width ?: 0
        val altoOverlay = selectionView?.height ?: 0

        // El overlay y el bitmap no miden lo mismo (barras de sistema): sin escalar,
        // el recorte cae corrido. escalarRecorte devuelve null si el resultado no sirve
        // y ahí se usa el bitmap entero.
        val recorte = seleccion?.let {
            escalarRecorte(
                Recorte(it.left, it.top, it.width(), it.height()),
                anchoOverlay, altoOverlay, bitmap.width, bitmap.height,
            )
        }
        android.util.Log.d("ScreenCapture", "Recorte escalado: $recorte")

        val recortado = if (recorte != null) {
            Bitmap.createBitmap(bitmap, recorte.left, recorte.top, recorte.ancho, recorte.alto)
        } else {
            bitmap
        }

        // Tasks.await() de ML Kit lanza si corre en el main thread, y processCapture()
        // llega acá desde un Handler del main looper. De paso, el OCR de una captura
        // grande tarda cientos de ms y no debe bloquear la UI del overlay.
        Thread {
            val texto = try {
                (application as App).contenedor.ocr.reconocer(recortado)
            } catch (e: Exception) {
                android.util.Log.e("ScreenCapture", "OCR falló", e)
                ""
            }
            android.util.Log.d("ScreenCapture", "OCR devolvió ${texto.length} chars")
            recortado.recycle()
            if (recortado != bitmap) bitmap.recycle()
            entregarTexto(texto)
            Handler(Looper.getMainLooper()).post { stopOverlay() }
        }.start()
    }

    /** Abre la app con el texto reconocido. Arrancar una Activity desde background
     *  está bloqueado desde Android 10, pero la app queda exenta por tener
     *  SYSTEM_ALERT_WINDOW concedido — que es el permiso del propio overlay. */
    private fun entregarTexto(texto: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_TEXTO_OCR
            putExtra(EXTRA_TEXTO_OCR, texto)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    private fun cerrarConDemora() {
        // demora para que el Intent de apertura alcance a procesarse antes de
        // desmontar el overlay (comportamiento heredado que evita un parpadeo).
        Handler(Looper.getMainLooper()).postDelayed({ stopOverlay() }, 500)
    }
```

- [ ] **Step 5: Reemplazar los usos restantes de los callbacks borrados**

En `captureScreen()`, el bloque `catch (e: SecurityException)`. Antes:

```kotlin
            } catch (e: SecurityException) {
                android.util.Log.e("ScreenCapture", "SecurityException - Token de MediaProjection expirado o inválido", e)

                // INVALIDAR las credenciales guardadas para forzar nuevo permiso
                FloatingBubbleService.captureResultCode = 0
                FloatingBubbleService.captureResultData = null

                // Notificar que necesitamos pedir permiso de nuevo
                permissionExpiredCallback?.invoke()
                captureCallback?.invoke(null)
                stopOverlay()
            } catch (e: Exception) {
                android.util.Log.e("ScreenCapture", "Error creando MediaProjection", e)
                e.printStackTrace()
                captureCallback?.invoke(null)
                stopOverlay()
            }
```

Después:

```kotlin
            } catch (e: SecurityException) {
                android.util.Log.e("ScreenCapture", "SecurityException - Token de MediaProjection expirado o inválido", e)
                // Android 14+ invalida el token después de cada sesión. Se borran las
                // credenciales guardadas: el próximo tap del bubble abrirá la app para
                // pedirlo de nuevo, que es exactamente lo que hace falta.
                FloatingBubbleService.captureResultCode = 0
                FloatingBubbleService.captureResultData = null
                stopOverlay()
            } catch (e: Exception) {
                android.util.Log.e("ScreenCapture", "Error creando MediaProjection", e)
                stopOverlay()
            }
```

En el `setOnClickListener` del botón Cancelar. Antes:

```kotlin
            setOnClickListener {
                android.util.Log.d("ScreenCapture", "Botón CANCELAR presionado")
                captureCallback?.invoke(null)
                stopOverlay()
            }
```

Después:

```kotlin
            setOnClickListener {
                android.util.Log.d("ScreenCapture", "Botón CANCELAR presionado")
                stopOverlay()
            }
```

- [ ] **Step 6: Borrar el código muerto por minSdk 26 y arreglar la API deprecada**

En **`ScreenCaptureService.kt`**:

`createNotificationChannel()` — sacar el `if` de SDK O (minSdk 26 ya es O):

```kotlin
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Captura de Pantalla",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Servicio de captura de pantalla activo"
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
```

En `showOverlay()`, el tipo de ventana — antes:

```kotlin
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
```

después:

```kotlin
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
```

En `stopOverlay()` — antes `stopForeground(true)`, después:

```kotlin
        stopForeground(STOP_FOREGROUND_REMOVE)
```

En **`FloatingBubbleService.kt`**, los mismos tres cambios: `createNotificationChannel()`
sin el `if`, `TYPE_APPLICATION_OVERLAY` directo en `showBubble()`, y en `stopBubble()`
`stopForeground(STOP_FOREGROUND_REMOVE)`.

En `onStartCommand()` de `FloatingBubbleService` — antes:

```kotlin
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(
                            NOTIFICATION_ID,
                            createNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, createNotification())
                    }
```

Este `if` **se conserva**: distingue Android 14+ (que exige declarar el tipo), no
un SDK por debajo del minSdk. Igual con el bloque equivalente de `ScreenCaptureService`.

- [ ] **Step 7: Simplificar `onBubbleClicked()` en `FloatingBubbleService`**

La rama "sin credenciales" abría `MainActivity` y 500 ms después le mandaba un
segundo Intent. Un solo Intent con acción propia alcanza. Reemplazar el bloque
`else` entero:

```kotlin
        } else {
            android.util.Log.d("FloatingBubble", "NO hay credenciales - abriendo MainActivity para pedir permisos")
            // Un solo Intent con acción propia: MainActivity abre la pantalla de captura
            // y dispara el diálogo de MediaProjection. El original mandaba dos Intents
            // con un postDelayed de 500 ms entre medio, que era una carrera.
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = ACTION_PEDIR_PERMISO
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
```

Y agregar la constante al `companion object` de `FloatingBubbleService`, junto a las
otras acciones:

```kotlin
        /** Contrato con MainActivity: el bubble se tocó sin credenciales de
         *  MediaProjection vigentes; hay que pedirlas. */
        const val ACTION_PEDIR_PERMISO = "com.tatoh.dokushorenshu.captura.PEDIR_PERMISO"
```

- [ ] **Step 8: Verificar que compila**

```bash
cd app && ./gradlew assembleDebug
```

Esperado: BUILD SUCCESSFUL. Si falla por un `Unresolved reference` a algo de Flutter,
es un resto del puente viejo — borrarlo, no reimplementarlo.

- [ ] **Step 9: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/
git commit -m "feat(captura): portar overlay flotante y captura de pantalla desde Kanji-no-Ryoushi

Los dos Services vienen casi tal cual (código probado en dispositivo, con los
workarounds de MIUI y los foregroundServiceType de Android 14). Cambios: se
elimina el puente a Flutter —el Service hace el OCR y entrega texto por Intent—,
se borran las ramas de SDK < 26 y el escalado del recorte pasa a la función pura
testeada."
```

---

### Task 4: Manifest, permisos y pantalla de captura

Los tres permisos (overlay, notificaciones, MediaProjection) se conceden a mano y
dos de ellos mandan al usuario fuera de la app. Necesitan pantalla propia.

**Files:**
- Modify: `app/app/src/main/AndroidManifest.xml`
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/captura/CapturaScreen.kt`

**Interfaces:**
- Consumes: `ScreenCaptureService`, `FloatingBubbleService` (Task 3).
- Produces: `@Composable fun CapturaScreen(pedirPermisoAlEntrar: Boolean, onCerrar: () -> Unit)`

- [ ] **Step 1: Agregar permisos y servicios al manifest**

En `app/app/src/main/AndroidManifest.xml`, después de la línea de `INTERNET`:

```xml
    <!-- Captura con overlay: SYSTEM_ALERT_WINDOW lo concede el usuario desde
         Ajustes (no hay diálogo runtime) y de paso exime a la app del bloqueo de
         arranque de Activities desde background de Android 10+. -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Dentro de `<application>`, después del bloque `<provider>`:

```xml
        <!-- El Service combina mediaProjection (requerido para capturar) con
             specialUse (evita restricciones de fabricantes tipo MIUI). -->
        <service
            android:name=".captura.ScreenCaptureService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="mediaProjection|specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Captura de pantalla para OCR de texto japonés" />
        </service>

        <service
            android:name=".captura.FloatingBubbleService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Overlay flotante para captura rápida de texto" />
        </service>
```

Y en el `<activity android:name=".MainActivity">`, agregar el atributo
`android:launchMode="singleTop"` — sin él, cada captura apila una `MainActivity`
nueva en vez de entregarle el texto a la que ya está viva:

```xml
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
```

- [ ] **Step 2: Escribir la pantalla de captura**

Crear `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/captura/CapturaScreen.kt`:

```kotlin
package com.tatoh.dokushorenshu.ui.captura

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tatoh.dokushorenshu.captura.FloatingBubbleService
import com.tatoh.dokushorenshu.captura.ScreenCaptureService

/** MediaProjection con recorte por overlay necesita Android 10+. minSdk sigue en
 *  26 porque el lector anda perfecto sin esto: la feature se deshabilita, no se
 *  sube el piso de la app. */
private val SOPORTADO = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

private data class EstadoPermisos(val overlay: Boolean, val notificaciones: Boolean)

private fun leerPermisos(context: Context) = EstadoPermisos(
    overlay = Settings.canDrawOverlays(context),
    notificaciones = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapturaScreen(pedirPermisoAlEntrar: Boolean, onCerrar: () -> Unit) {
    val context = LocalContext.current
    var permisos by remember { mutableStateOf(leerPermisos(context)) }
    var bubbleActivo by remember { mutableStateOf(FloatingBubbleService.isRunning) }

    // El permiso de overlay se concede en Ajustes del sistema, no con un diálogo:
    // se lanza como Activity y se releen los permisos cuando el usuario vuelve.
    // Esto evita depender de lifecycle-runtime-compose solo para un ON_RESUME.
    val lanzadorOverlay = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permisos = leerPermisos(context) }

    val lanzadorNotificaciones = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permisos = leerPermisos(context) }

    val lanzadorProyeccion = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        val datos = resultado.data
        if (resultado.resultCode == Activity.RESULT_OK && datos != null) {
            // El bubble guarda las credenciales para poder disparar capturas sin
            // pasar por la Activity. Android 14+ las invalida después de cada
            // sesión y el Service las limpia solo.
            FloatingBubbleService.captureResultCode = resultado.resultCode
            FloatingBubbleService.captureResultData = datos
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START_CAPTURE
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultado.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, datos)
                },
            )
        }
    }

    fun pedirProyeccion() {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        lanzadorProyeccion.launch(manager.createScreenCaptureIntent())
    }

    // Llegada desde el tap del bubble sin credenciales vigentes: disparar el
    // diálogo directo, sin obligar a un tap más.
    LaunchedEffect(pedirPermisoAlEntrar) {
        if (pedirPermisoAlEntrar && SOPORTADO && permisos.overlay) pedirProyeccion()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Scan") },
            actions = { TextButton(onClick = onCerrar) { Text("Close") } },
        )
    }) { relleno ->
        Column(
            Modifier.fillMaxSize().padding(relleno).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!SOPORTADO) {
                Text("Screen capture needs Android 10 or newer.", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }

            Text(
                "Capture any part of the screen and turn it into a story you can read with furigana and lookups.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!permisos.overlay) {
                Text("Draw over other apps: not granted", style = MaterialTheme.typography.titleSmall)
                Button(
                    onClick = {
                        lanzadorOverlay.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant overlay permission") }
            }

            if (!permisos.notificaciones) {
                Text("Notifications: not granted", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The floating button runs as a foreground service and Android requires a visible notification for it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { lanzadorNotificaciones.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant notification permission") }
            }

            val listo = permisos.overlay && permisos.notificaciones

            Button(
                onClick = { pedirProyeccion() },
                enabled = listo,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Capture now") }

            OutlinedButton(
                onClick = {
                    val accion = if (bubbleActivo) FloatingBubbleService.ACTION_STOP_BUBBLE
                                 else FloatingBubbleService.ACTION_START_BUBBLE
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, FloatingBubbleService::class.java).apply { action = accion },
                    )
                    bubbleActivo = !bubbleActivo
                },
                enabled = listo,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (bubbleActivo) "Stop floating button" else "Start floating button") }

            Text(
                "On Android 14 and newer, Android asks for capture permission every time — that is an OS rule, not a bug.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 3: Verificar que compila**

```bash
cd app && ./gradlew assembleDebug
```

Esperado: BUILD SUCCESSFUL. La pantalla todavía no está ruteada — eso es la Task 5.

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/AndroidManifest.xml \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/captura/CapturaScreen.kt
git commit -m "feat(captura): permisos en el manifest y pantalla de captura"
```

---

### Task 5: Cablear el flujo completo

El texto reconocido llega a `MainActivity` por `Intent` y termina precargando
`ImportScreen`. Con esto el flujo anda punta a punta.

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaScreen.kt:76-83,93-100`

**Interfaces:**
- Consumes: `ScreenCaptureService.ACTION_TEXTO_OCR`/`.EXTRA_TEXTO_OCR`, `FloatingBubbleService.ACTION_PEDIR_PERMISO` (Task 3); `CapturaScreen` (Task 4); `ImportViewModel.setTexto`/`.setTitulo` (existente).
- Produces: rutas de navegación `"captura"` y `"captura?permiso=true"`.

- [ ] **Step 1: Agregar el parámetro `onScan` a `BibliotecaScreen`**

En `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaScreen.kt`,
en la firma (línea 76), agregar el parámetro después de `onImportar`:

```kotlin
    onImportar: () -> Unit,
    onScan: () -> Unit,
) {
```

Y en las `actions` del `TopAppBar` (línea 95), agregarlo primero:

```kotlin
            actions = {
                TextButton(onClick = onScan) { Text("Scan") }
                TextButton(onClick = onImportar) { Text("Import") }
                TextButton(onClick = onExport) { Text("Export") }
                TextButton(onClick = onAcerca) { Text("About") }
            },
```

- [ ] **Step 2: Recibir el Intent en `MainActivity`**

En `app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt`, agregar los
imports y el estado antes de `onCreate`:

```kotlin
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.tatoh.dokushorenshu.captura.FloatingBubbleService
import com.tatoh.dokushorenshu.captura.ScreenCaptureService
import com.tatoh.dokushorenshu.ui.captura.CapturaScreen
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
```

```kotlin
class MainActivity : ComponentActivity() {

    /** Texto que dejó ScreenCaptureService en el Intent, esperando a que la ruta
     *  "importar" lo consuma. Es estado de la Activity y no del NavHost porque
     *  puede llegar por onNewIntent con la app ya abierta (launchMode singleTop). */
    private val textoOcrPendiente = mutableStateOf<String?>(null)
```

Al final de `onCreate`, **antes** de `setContent`, leer el intent inicial:

```kotlin
        leerIntent(intent)
```

Y agregar los dos métodos al final de la clase:

```kotlin
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        leerIntent(intent)
    }

    private fun leerIntent(intent: Intent?) {
        if (intent?.action == ScreenCaptureService.ACTION_TEXTO_OCR) {
            textoOcrPendiente.value = intent.getStringExtra(ScreenCaptureService.EXTRA_TEXTO_OCR)
        }
    }
```

- [ ] **Step 3: Navegar y precargar**

Dentro de `setContent { TemaDokusho { ... } }`, justo después de
`val nav = rememberNavController()`:

```kotlin
                val textoOcr by textoOcrPendiente
                // Llegó una captura: saltar al import. El texto se consume dentro de
                // la ruta "importar" (no acá) para que sobreviva a la navegación.
                LaunchedEffect(textoOcr) {
                    if (textoOcr != null) nav.navigate("importar")
                }
                LaunchedEffect(Unit) {
                    if (intent?.action == FloatingBubbleService.ACTION_PEDIR_PERMISO) {
                        nav.navigate("captura?permiso=true")
                    }
                }
```

Agregar el import de `LaunchedEffect`:

```kotlin
import androidx.compose.runtime.LaunchedEffect
```

- [ ] **Step 4: Rutear la pantalla de captura**

Dentro del `NavHost`, después del bloque `composable("importar") { ... }`:

```kotlin
                    composable(
                        "captura?permiso={permiso}",
                        arguments = listOf(navArgument("permiso") {
                            type = NavType.BoolType
                            defaultValue = false
                        }),
                    ) { entrada ->
                        CapturaScreen(
                            pedirPermisoAlEntrar = entrada.arguments!!.getBoolean("permiso"),
                            onCerrar = { nav.popBackStack() },
                        )
                    }
```

Imports nuevos:

```kotlin
import androidx.navigation.NavType
import androidx.navigation.navArgument
```

Y en `composable("biblioteca")`, pasarle el callback nuevo a `BibliotecaScreen`,
después de `onImportar`:

```kotlin
                            onImportar = { nav.navigate("importar") },
                            onScan = { nav.navigate("captura?permiso=false") },
```

- [ ] **Step 5: Precargar `ImportScreen` con el texto reconocido**

Reemplazar el bloque `composable("importar") { ... }` entero:

```kotlin
                    composable("importar") {
                        val vm: ImportViewModel = viewModel(factory = viewModelFactory {
                            initializer { ImportViewModel(contenedor.importador) }
                        })
                        // El texto de una captura se vuelca acá y se consume (se pone en
                        // null) para que no reaparezca al rotar ni al volver desde el lector.
                        // Queda editable a propósito: el OCR de texto vertical puede
                        // equivocar el orden de las columnas.
                        LaunchedEffect(Unit) {
                            textoOcrPendiente.value?.let { texto ->
                                vm.setTexto(texto)
                                vm.setTitulo(tituloDeCaptura())
                                textoOcrPendiente.value = null
                            }
                        }
                        ImportScreen(
                            vm = vm,
                            onImportado = { nav.popBackStack() },
                            onCerrar = { nav.popBackStack() },
                        )
                    }
```

Y agregar la función al final del archivo, fuera de la clase:

```kotlin
/** Título por defecto de una captura. Con fecha y hora porque se generan muchas
 *  seguidas y el id de la historia sale del título (colisión → sufijo -2, -3…). */
private fun tituloDeCaptura(): String =
    "Scan " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
```

- [ ] **Step 6: Verificar que compila y que los tests pasan**

```bash
cd app && ./gradlew assembleDebug test
```

Esperado: BUILD SUCCESSFUL, todos los tests en verde.

- [ ] **Step 7: Smoke en dispositivo (Android 10+)**

```bash
cd app && ./gradlew installDebug
```

Verificar, en orden:

1. Biblioteca → **Scan** abre la pantalla nueva. Los cuatro botones del top bar
   entran sin desbordar en teléfono vertical; si desbordan, mover About a un menú
   overflow y anotarlo.
2. **Grant overlay permission** manda a Ajustes; al volver, el aviso desaparece.
3. **Grant notification permission** muestra el diálogo del sistema (Android 13+).
4. **Start floating button** → aparece el bubble; se arrastra y hace snap al borde.
5. Salir a otra app con japonés (un manga, Twitter). Tap en el bubble → diálogo de
   captura → overlay oscuro. Arrastrar para seleccionar un área con texto → **Capturar**.
6. Se abre Dokusho en **Import**, con el texto japonés reconocido y el título
   `Scan <fecha> <hora>`.
7. **Import** → se abre el lector con furigana. Tap en una palabra → `PalabraSheet`
   con definición y ejemplos.
8. Volver a Biblioteca → Export: la historia capturada aparece en la lista de
   "Dokusho — Stories".

Si el recorte sale corrido, el bug está en `escalarRecorte` o en las dimensiones
que se le pasan — anotar los valores que loguea `"Recorte escalado: ..."` antes de
tocar nada.

- [ ] **Step 8: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt \
        app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/biblioteca/BibliotecaScreen.kt
git commit -m "feat(captura): cablear captura -> OCR -> import -> lector"
```

---

### Task 6: Atribución, documentación y baja de Kanji-no-Ryoushi

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/acerca/AcercaScreen.kt:45`
- Modify: `app/README.md`
- Modify: `docs/ESTADO.md`
- Modify (otro repo): `/Users/tatoh/Repos/Personal/Kanji-no-Ryoushi/README.md`

**Interfaces:**
- Consumes: todo lo anterior.
- Produces: nada de código.

- [ ] **Step 1: Agregar la atribución de ML Kit**

En `AcercaScreen.kt`, después de la línea de Kuromoji (línea 45):

```kotlin
            Spacer(Modifier.height(12.dp))
            Text("Text recognition", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Text("• Google ML Kit Text Recognition v2 — Apache License 2.0")
```

Usar el mismo `Spacer`/estilo que los bloques de arriba (copiar el patrón exacto de
las líneas 38-45 del archivo, que ya traen el `Spacer` y el `color = primary`).

- [ ] **Step 2: Documentar la arquitectura en el README del módulo**

En `app/README.md`, en la sección `## Arquitectura`, agregar un bullet después del
de `ui/`:

```markdown
- `captura/` — overlay flotante (bubble) + captura con MediaProjection, portado de
  Kanji-no-Ryoushi. El Service hace el OCR (ML Kit japonés, modelo embebido) y le
  pasa a MainActivity solo el texto por Intent, que precarga el import. Android 10+;
  por debajo la pantalla Scan avisa y no ofrece nada.
```

- [ ] **Step 3: Actualizar `docs/ESTADO.md`**

En la tabla de "Dónde estamos", agregar una fila al final:

```markdown
| E    | app/ — captura OCR con overlay flotante (migración de Kanji-no-Ryoushi) | ✅ Completo (PR pendiente — actualizar con #N al abrir) |
```

Y en "Datos operativos", un bullet nuevo:

```markdown
- **Captura OCR (Plan E)**: los dos Services vienen de `Kanji-no-Ryoushi` (Flutter, main `79ad93d`), que queda obsoleto — su diccionario JitendEx, su historial y sus pantallas los cubre Dokusho mejor. OCR con `com.google.mlkit:text-recognition-japanese:16.0.1`, modelo embebido (offline, sin Play Services); el APK release pasó de 42.7 MB a **<completar con el número medido en la Task 2 Step 6>**. El Service hace el OCR y manda solo el texto por Intent (sin MethodChannel ni callbacks estáticos). Una captura = una historia importada. Límites conocidos: Android 14+ invalida el token de MediaProjection tras cada sesión y pide consentimiento en cada captura (regla del OS); ML Kit no garantiza el orden de las Line dentro de un bloque en texto vertical derecha-a-izquierda — por eso el texto queda editable en ImportScreen (arreglo: ordenar por boundingBox). Backlog no migrado del TODO viejo: Quick Settings Tile, zoom en el overlay, ajustes de contraste/brillo pre-OCR, historial de capturas.
```

Reemplazar el placeholder con el tamaño real del APK medido en la Task 2.

- [ ] **Step 4: Marcar Kanji-no-Ryoushi como obsoleto**

En `/Users/tatoh/Repos/Personal/Kanji-no-Ryoushi/README.md`, insertar al principio
del archivo, antes de cualquier otro contenido:

```markdown
> ## ⚠️ Repo obsoleto
>
> Este proyecto se migró a **[dokusho-renshuu](https://github.com/T4toh/dokusho-renshuu)**
> (Android nativo, Kotlin + Compose) en septiembre de 2026.
>
> El overlay flotante, la captura de pantalla y el OCR viven ahora en `app/captura/`
> de ese repo, integrados con su diccionario, su tokenizador, su progreso y su
> export a Anki. La capa Flutter no se migró: duplicaba lo que Dokusho ya resuelve,
> y el overlay sobre otras apps es Android-only por diseño del sistema, así que
> multiplataforma no aportaba nada.
>
> Se archiva como referencia. No recibe más cambios.
```

- [ ] **Step 5: Commitear la baja en el repo viejo, SIN pushear**

```bash
cd /Users/tatoh/Repos/Personal/Kanji-no-Ryoushi
git checkout -b chore/marcar-obsoleto
git add README.md
git commit -m "docs: marcar el repo como obsoleto, migrado a dokusho-renshuu"
git log --oneline -1
```

**PARAR ACÁ.** El push de esta rama y el archivado del repo en GitHub son acciones
sobre un remoto: requieren pedido explícito del usuario. No correr `git push` ni
`gh repo archive` sin que lo pida.

- [ ] **Step 6: Commit de la documentación en Dokusho**

```bash
cd /Users/tatoh/Repos/Personal/dokusho-renshuu
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/acerca/AcercaScreen.kt \
        app/README.md docs/ESTADO.md
git commit -m "docs: atribución de ML Kit, arquitectura de captura y estado del plan E"
```

---

## Self-Review

**Cobertura del spec:**

| Requisito del spec | Task |
|---|---|
| Portar los dos Services | 3 |
| OCR dentro del Service, entrega por Intent | 3 (steps 3-5) |
| Contrato de unión de bloques | 1 |
| Escalado del recorte | 1 |
| Dependencia ML Kit + medición de peso | 2 |
| Permisos (overlay, notificaciones, MediaProjection) | 4 |
| Aviso de Android < 10 | 4 (`SOPORTADO`) |
| Terminar en el pipeline de import | 5 |
| Atribución ML Kit | 6 |
| Baja de Kanji-no-Ryoushi | 6 |
| Backlog no migrado, documentado | 6 (ESTADO.md) |

**Consistencia de tipos:** `Recorte`/`escalarRecorte`/`BloqueOcr`/`unirBloques` se
definen en la Task 1 y se consumen con la misma firma en las Tasks 2 y 3.
`ACTION_TEXTO_OCR`/`EXTRA_TEXTO_OCR` se definen en la Task 3 y se leen en la Task 5.
`ACTION_PEDIR_PERMISO` se define en la Task 3 y se lee en la Task 5.
`CapturaScreen(pedirPermisoAlEntrar, onCerrar)` se define en la Task 4 y se rutea
con esa firma en la Task 5.

**Placeholder pendiente a propósito:** el tamaño del APK en `ESTADO.md` (Task 6
Step 3) se completa con la medición real de la Task 2 Step 6. Es un dato que solo
existe después de compilar, no una decisión sin tomar.
