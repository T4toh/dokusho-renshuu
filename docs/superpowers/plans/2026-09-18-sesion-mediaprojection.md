# Una sesión de MediaProjection por vida de la burbuja — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que la app pida el consentimiento de MediaProjection una vez por vida de la burbuja en vez de una vez por captura.

**Architecture:** Los dos Services de `captura/` se funden en uno (`CapturaService`) que hospeda la burbuja, la sesión de `MediaProjection` y el overlay de selección. La sesión se abre una vez y sobrevive a las capturas; el `VirtualDisplay` y el `ImageReader` se siguen creando y liberando por captura. La burbuja deja de ser un Service y pasa a ser una clase que el Service instancia.

**Tech Stack:** Kotlin, Android SDK 36 (minSdk 26, captura gateada a API 29+), Compose, ML Kit `text-recognition-japanese`, JUnit 4 en JVM plano (sin Robolectric).

**Spec:** `docs/superpowers/specs/2026-09-18-sesion-mediaprojection-design.md`

## Global Constraints

- **Los tests corren en JVM plano, sin Robolectric.** Nada que toque `Bitmap`, `Service`, `WindowManager` o `MediaProjection` se puede testear acá: la lógica testeable se extrae a funciones puras (mismo criterio que `escalarRecorte` en `dominio/ocr/TextoOcr.kt`).
- **Toda la UI de la app está en inglés.** Los avisos al usuario también: `Screen capture failed. Please try again`, `No text found in the selected area`, `Quick capture active`.
- **El código y los comentarios se escriben en español**, como todo el repo.
- **`FLAG_NOT_FOCUSABLE` en la ventana de la burbuja no se toca ni se "limpia".** Sin él, en HyperOS la app deja de responder a toques y al Back (PR #20). El comentario que explica por qué viaja con el código.
- **Compilar y correr los tests es `cd app && ./gradlew testDebugUnitTest`**; instalar en el dispositivo es `./gradlew installDebug`. La línea base al empezar este plan es **304 tests, 0 failures**.
- **Ningún commit de este plan deja la app sin compilar ni la captura sin funcionar.** Cada tarea termina con la app instalable y la captura andando, aunque la feature todavía no esté completa.

---

### Task 1: La lógica pura de la sesión

Las dos ramas que el Service tiene que decidir —qué hacer con un tap, y si apagarse después de capturar— salen del Service para poder testearlas. Es lo único testeable en JVM de todo el plan.

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/captura/SesionCaptura.kt`
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/captura/SesionCapturaTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `sealed interface AccionTap` con los objetos `MostrarOverlay` y `PedirConsentimiento`; `fun decidirTap(haySesion: Boolean): AccionTap`; `fun apagarTrasCaptura(hayBurbuja: Boolean): Boolean`. Las tareas 4 y 5 los llaman desde `CapturaService`.

- [ ] **Step 1: Write the failing test**

Crear `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/captura/SesionCapturaTest.kt`:

```kotlin
package com.tatoh.dokushorenshu.dominio.captura

import org.junit.Assert.assertEquals
import org.junit.Test

class SesionCapturaTest {

    @Test
    fun `con sesion abierta el tap va derecho al overlay`() {
        assertEquals(AccionTap.MostrarOverlay, decidirTap(haySesion = true))
    }

    @Test
    fun `sin sesion el tap tiene que pedir consentimiento`() {
        assertEquals(AccionTap.PedirConsentimiento, decidirTap(haySesion = false))
    }

    @Test
    fun `sin burbuja una captura es una sesion y el Service se apaga`() {
        // El camino de "Capture now" desde la pantalla Scan: nadie va a tocar una
        // burbuja después, así que no hay sesión que sostener.
        assertEquals(true, apagarTrasCaptura(hayBurbuja = false))
    }

    @Test
    fun `con burbuja el Service sigue vivo despues de capturar`() {
        // Es el corazón del plan: la sesión sobrevive a la captura.
        assertEquals(false, apagarTrasCaptura(hayBurbuja = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && ./gradlew testDebugUnitTest --tests "*SesionCapturaTest*"`
Expected: FAIL al compilar — `Unresolved reference: AccionTap` y `Unresolved reference: decidirTap`.

- [ ] **Step 3: Write minimal implementation**

Crear `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/captura/SesionCaptura.kt`:

```kotlin
package com.tatoh.dokushorenshu.dominio.captura

/** Qué hacer cuando el usuario toca la burbuja. */
sealed interface AccionTap {
    /** Hay sesión de MediaProjection viva: se dibuja el overlay de selección y listo. */
    data object MostrarOverlay : AccionTap

    /** No hay sesión: hay que abrir la app para que el sistema pida el consentimiento.
     *  Es el camino de siempre, que con este plan pasa a ser el de recuperación. */
    data object PedirConsentimiento : AccionTap
}

/** La sesión es la única fuente de verdad: si está viva no se molesta al usuario. */
fun decidirTap(haySesion: Boolean): AccionTap =
    if (haySesion) AccionTap.MostrarOverlay else AccionTap.PedirConsentimiento

/** Si no hay burbuja, la captura vino de `Capture now` en la pantalla Scan: nadie va a
 *  tocar una burbuja después, así que no hay sesión que sostener y el Service se apaga
 *  al terminar — el comportamiento de siempre. Con burbuja, el Service sigue vivo con su
 *  sesión, que es el punto de todo el plan. */
fun apagarTrasCaptura(hayBurbuja: Boolean): Boolean = !hayBurbuja
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && ./gradlew testDebugUnitTest --tests "*SesionCapturaTest*"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/captura/SesionCaptura.kt app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/captura/SesionCapturaTest.kt
git commit -m "feat(captura): extraer la decisión del tap y el apagado tras capturar"
```

---

### Task 2: La burbuja deja de ser un Service

Extracción pura: el mismo comportamiento, movido a una clase. `FloatingBubbleService` sigue existiendo y delega en ella, así la app anda igual al terminar esta tarea.

**Files:**
- Create: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/BurbujaFlotante.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/FloatingBubbleService.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `class BurbujaFlotante(context: Context, windowManager: WindowManager, onTap: () -> Unit)` con `fun mostrar()`, `fun ocultar()` y `fun visible(visible: Boolean)`. La tarea 4 la instancia desde `CapturaService` y usa `burbuja != null` para saber si hay burbuja — no hace falta exponer el estado.

- [ ] **Step 1: Crear la clase con el código de la ventana**

Crear `BurbujaFlotante.kt` moviendo **tal cual** desde `FloatingBubbleService.kt`: el cuerpo de `showBubble()` (líneas 140-308), `snapToEdge()` (309-323) y el `OnTouchListener` completo con su detección de tap vs. arrastre. Cambios mínimos y sólo estos:

- `showBubble()` pasa a ser `fun mostrar()`.
- Donde el listener llamaba `onBubbleClicked()`, ahora llama `onTap()` (el parámetro del constructor).
- `stopBubble()` se parte: la parte de la ventana (`windowManager?.removeView(bubbleView)` + `bubbleView = null`) pasa a `fun ocultar()`; el `stopForeground`/`stopSelf` **se queda en el Service**.
- `setBubbleVisible(visible)` pasa a `fun visible(visible: Boolean)` sobre el campo de instancia, sin `instance` estático.

El `FLAG_NOT_FOCUSABLE` y su comentario viajan sin tocarse.

- [ ] **Step 2: El Service delega**

En `FloatingBubbleService.kt`:

```kotlin
private var burbuja: BurbujaFlotante? = null
```

- `ACTION_START_BUBBLE` crea la burbuja y la muestra:

```kotlin
burbuja = BurbujaFlotante(this, windowManager!!, ::onBubbleClicked).also { it.mostrar() }
```

- `stopBubble()` llama `burbuja?.ocultar()` antes de su `stopForeground`/`stopSelf`.
- El estático `setBubbleVisible(visible)` se mantiene por ahora (lo usa `ScreenCaptureService`) pero adentro hace `instance?.burbuja?.visible(visible)`. Se borra en la tarea 4.

- [ ] **Step 3: Compilar y correr los tests**

Run: `cd app && ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 308 tests, 0 failures (304 de base + 4 de la tarea 1).

- [ ] **Step 4: Verificar en el dispositivo que la burbuja quedó igual**

```bash
cd app && ./gradlew installDebug
```

En el teléfono: Biblioteca → pestaña `Notes` → FAB `Scan` → `Start floating button`. Verificar los cuatro comportamientos del paso 4 del smoke: la burbuja aparece, se arrastra, hace snap al borde al soltar, y un tap corto (no arrastre) dispara la captura. Con `adb logcat -s FloatingBubble:D` tienen que aparecer `ACTION_UP recibido, moved=false` y `Click detectado!`.

**OJO con adb:** `input tap` sobre la ventana de la burbuja se pierde seguido (el evento cae en la Activity). Usar `input swipe <x> <y> <x> <y> 120`, que es un tap con duración. Para la UI de Compose es al revés.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/
git commit -m "refactor(captura): la burbuja pasa a ser una clase, el Service delega"
```

---

### Task 3: Renombrar `ScreenCaptureService` a `CapturaService`

Renombre mecánico y solo. Sin cambios de comportamiento, para que el diff de las tareas siguientes se lea.

**Files:**
- Rename: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/ScreenCaptureService.kt` → `CapturaService.kt`
- Modify: `app/app/src/main/AndroidManifest.xml:47`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt:24,240,241,242,247,248`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/captura/CapturaScreen.kt:38,61,62,63,64`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/FloatingBubbleService.kt` (el `Intent(this, ScreenCaptureService::class.java)` de `onBubbleClicked`)
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/ocr/OcrJapones.kt:18` (el KDoc nombra al llamador)

- [ ] **Step 1: Renombrar el archivo y la clase**

```bash
cd app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura
git mv ScreenCaptureService.kt CapturaService.kt
```

Adentro: `class ScreenCaptureService : Service()` → `class CapturaService : Service()`. El tag de log `"ScreenCapture"` **se mantiene**: el smoke y el ESTADO documentan filtrar por ese tag, y cambiarlo invalidaría las instrucciones escritas.

- [ ] **Step 2: Actualizar el manifest**

En `AndroidManifest.xml`, línea 47: `android:name=".captura.ScreenCaptureService"` → `android:name=".captura.CapturaService"`. El resto del bloque (`exported`, `foregroundServiceType="mediaProjection|specialUse"`) no se toca.

- [ ] **Step 3: Actualizar las referencias**

```bash
cd /Users/tatoh/Repos/Personal/dokusho-renshuu/app
grep -rln "ScreenCaptureService" app/src/main/kotlin | xargs sed -i '' 's/ScreenCaptureService/CapturaService/g'
```

- [ ] **Step 4: Compilar y correr los tests**

Run: `cd app && ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 308 tests, 0 failures.

- [ ] **Step 5: Verificar que el manifest quedó bien**

```bash
cd app && ./gradlew installDebug
```

Encender la burbuja y hacer **una captura completa**. Si el manifest quedó mal, el Service no arranca y el tap no hace nada: es el único modo de falla de esta tarea y no lo agarra el compilador.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(captura): ScreenCaptureService pasa a llamarse CapturaService"
```

---

### Task 4: `CapturaService` absorbe la burbuja

Se borra `FloatingBubbleService`. Una sola notificación, un solo ciclo de vida, sin credenciales estáticas. **El comportamiento del permiso todavía no cambia**: se sigue consintiendo por captura. Eso es la tarea 5.

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/CapturaService.kt`
- Delete: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/FloatingBubbleService.kt`
- Modify: `app/app/src/main/AndroidManifest.xml` (borrar el bloque `<service>` de las líneas 56-61)
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/captura/CapturaScreen.kt:37,57,58,83,162,174,177`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/MainActivity.kt:23,243`

**Interfaces:**
- Consumes: `BurbujaFlotante` (tarea 2).
- Produces: en `CapturaService.Companion` — `ACTION_INICIAR`, `ACTION_CAPTURAR`, `ACTION_DETENER`, `ACTION_ABRIR_SESION`, `ACTION_PEDIR_PERMISO`, `EXTRA_RESULT_CODE`, `EXTRA_RESULT_DATA`, `ACTION_TEXTO_OCR`, `EXTRA_TEXTO_OCR`, `EXTRA_RUTA_IMAGEN`, y `val isRunning: Boolean`. La tarea 5 agrega el manejo de sesión sobre estas mismas acciones.

- [ ] **Step 1: Mover las constantes y el estado de la burbuja al Service**

En el `companion object` de `CapturaService`, reemplazar `ACTION_START_CAPTURE` por las cuatro acciones nuevas y traer `ACTION_PEDIR_PERMISO` desde `FloatingBubbleService`:

```kotlin
companion object {
    const val NOTIFICATION_ID = 1002
    const val CHANNEL_ID = "captura_channel"

    /** Encender la burbuja (desde la pantalla Scan). No trae credenciales. */
    const val ACTION_INICIAR = "com.tatoh.dokushorenshu.captura.INICIAR"
    /** Abrir la sesión de MediaProjection con el resultado del consentimiento. */
    const val ACTION_ABRIR_SESION = "com.tatoh.dokushorenshu.captura.ABRIR_SESION"
    /** Apagar todo: sesión, burbuja y Service. */
    const val ACTION_DETENER = "com.tatoh.dokushorenshu.captura.DETENER"

    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_RESULT_DATA = "result_data"

    /** Lo que el Service le manda a MainActivity cuando el OCR terminó. */
    const val ACTION_TEXTO_OCR = "com.tatoh.dokushorenshu.captura.TEXTO_OCR"
    const val EXTRA_TEXTO_OCR = "texto_ocr"
    const val EXTRA_RUTA_IMAGEN = "ruta_imagen"
    /** Lo que el Service le manda a MainActivity cuando hace falta consentimiento. */
    const val ACTION_PEDIR_PERMISO = "com.tatoh.dokushorenshu.captura.PEDIR_PERMISO"

    /** Lo lee la pantalla Scan para el rótulo del botón. */
    @Volatile var isRunning: Boolean = false
        private set
}
```

Los estáticos `captureResultCode` y `captureResultData` **no se migran: se borran**. Eran estado global compartido entre dos Services y es justo lo que produjo el bug del PR #21.

- [ ] **Step 2: El Service hospeda la burbuja**

Campos nuevos en `CapturaService`:

```kotlin
private var burbuja: BurbujaFlotante? = null
```

`onStartCommand` pasa a tener cuatro ramas. En esta tarea `ACTION_ABRIR_SESION` todavía se comporta como el viejo `START_CAPTURE` (crea la proyección al capturar y la tira al terminar):

```kotlin
when (intent?.action) {
    ACTION_INICIAR -> {
        if (burbuja == null) {
            arrancarEnForeground(conProyeccion = false)
            burbuja = BurbujaFlotante(this, windowManager!!, ::onTapBurbuja).also { it.mostrar() }
            isRunning = true
        }
    }
    ACTION_ABRIR_SESION -> {
        if (isCapturing) {
            android.util.Log.w("ScreenCapture", "Ya hay una captura en curso, ignorando")
            return START_NOT_STICKY
        }
        isCapturing = true
        resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        arrancarEnForeground(conProyeccion = true)
        showOverlay()
    }
    ACTION_DETENER -> detenerTodo()
}
return START_NOT_STICKY
```

`arrancarEnForeground(conProyeccion: Boolean)` reemplaza a los dos `startForeground` que había, y es la pieza que la tarea 5 va a reusar para promover el tipo:

```kotlin
/** El tipo de foreground no es cosmético: getMediaProjection() EXIGE que ya esté
 *  corriendo un FGS de tipo mediaProjection. Se arranca sin él (la burbuja sola no
 *  proyecta nada) y se promueve antes de crear la sesión. */
private fun arrancarEnForeground(conProyeccion: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val tipos = if (conProyeccion) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        startForeground(NOTIFICATION_ID, createNotification(), tipos)
    } else {
        startForeground(NOTIFICATION_ID, createNotification())
    }
}
```

`onTapBurbuja()` es el viejo `onBubbleClicked()` de `FloatingBubbleService`, con el envío de credenciales borrado (ya no existen) — en esta tarea siempre pide consentimiento:

```kotlin
private fun onTapBurbuja() {
    startActivity(Intent(this, MainActivity::class.java).apply {
        action = ACTION_PEDIR_PERMISO
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP
    })
}
```

`detenerTodo()`:

```kotlin
private fun detenerTodo() {
    cleanup()
    burbuja?.ocultar()
    burbuja = null
    isRunning = false
    isCapturing = false
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
}
```

Donde el código llamaba `FloatingBubbleService.setBubbleVisible(false/true)` (en `showOverlay()` y `liberarVentanaOverlay()`), ahora va `burbuja?.visible(false)` y `burbuja?.visible(true)`.

- [ ] **Step 3: Una sola notificación**

`createNotification()` de `CapturaService` pasa a ser la de la burbuja, con su acción `Stop` apuntando a `ACTION_DETENER`:

```kotlin
private fun createNotification(): Notification {
    val detener = PendingIntent.getService(
        this, 0,
        Intent(this, CapturaService::class.java).apply { action = ACTION_DETENER },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Quick capture active")
        .setContentText("Tap the floating button to capture")
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", detener)
        .build()
}
```

`createNotificationChannel()` se queda con un solo canal (`CHANNEL_ID = "captura_channel"`, nombre visible "Quick capture").

- [ ] **Step 4: Borrar `FloatingBubbleService` y su bloque del manifest**

```bash
git rm app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/FloatingBubbleService.kt
```

En `AndroidManifest.xml`, borrar el `<service android:name=".captura.FloatingBubbleService" …>` entero (líneas 56-61). Los `<uses-permission>` **no se tocan**: los dos tipos de foreground siguen haciendo falta.

- [ ] **Step 5: Apuntar la UI al Service nuevo**

En `ui/captura/CapturaScreen.kt`:

- `iniciarCaptura(context, resultCode, datos)` deja de escribir los estáticos y manda `ACTION_ABRIR_SESION`:

```kotlin
internal fun iniciarCaptura(context: Context, resultCode: Int, datos: Intent) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, CapturaService::class.java).apply {
            action = CapturaService.ACTION_ABRIR_SESION
            putExtra(CapturaService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CapturaService.EXTRA_RESULT_DATA, datos)
        },
    )
}
```

- `bubbleActivo` lee `CapturaService.isRunning`.
- El botón `Start/Stop floating button` manda `ACTION_INICIAR` (con `startForegroundService`) y `ACTION_DETENER` (con `startService`, por el mismo motivo que el comentario que ya está ahí: si el Service está muerto, `startForegroundService` sin `startForeground()` después lo hace matar a los ~5 s).

En `MainActivity.kt`, `leerIntent()` pasa a comparar contra `CapturaService.ACTION_PEDIR_PERMISO` y se borra el import de `FloatingBubbleService`.

- [ ] **Step 6: Compilar y correr los tests**

Run: `cd app && ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 308 tests, 0 failures.

- [ ] **Step 7: Verificar en dispositivo**

```bash
cd app && ./gradlew installDebug
```

1. `Start floating button` → aparece la burbuja y **una sola** notificación persistente.
2. Tap en la burbuja → abre la app, salta el diálogo, se acepta → aparece el overlay → arrastrar → `Capture` → la nota se crea y se abre. (Sigue pidiendo permiso por captura: eso es lo esperado en esta tarea.)
3. `Stop` en la notificación → desaparecen la burbuja y la notificación.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor(captura): un solo Service para la burbuja, la sesión y el overlay"
```

---

### Task 5: La sesión sobrevive a la captura

El cambio de comportamiento, aislado en su propio commit y encima de una base ya verificada.

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/CapturaService.kt`

**Interfaces:**
- Consumes: `decidirTap(haySesion)`, `apagarTrasCaptura(hayBurbuja)`, `AccionTap` (tarea 1); `BurbujaFlotante` (tarea 2).
- Produces: nada nuevo hacia afuera.

- [ ] **Step 1: La sesión se crea una vez, al abrirla**

`ACTION_ABRIR_SESION` deja de sólo guardar las credenciales: promueve el foreground, **crea el `MediaProjection` en el acto** y recién después dibuja el overlay.

```kotlin
ACTION_ABRIR_SESION -> {
    if (isCapturing) {
        android.util.Log.w("ScreenCapture", "Ya hay una captura en curso, ignorando")
        return START_NOT_STICKY
    }
    // La promoción del tipo de foreground puede ser rechazada por el sistema
    // (ForegroundServiceStartNotAllowedException y parientes). Sin este runCatching
    // la excepción sale del onStartCommand y se lleva puesto el proceso, con la
    // burbuja adentro; atrapada, cae en el mismo camino que un token inválido.
    val promovido = runCatching { arrancarEnForeground(conProyeccion = true) }
    if (promovido.isFailure) {
        android.util.Log.e("ScreenCapture", "El sistema rechazó el foreground de proyección", promovido.exceptionOrNull())
        avisar("Screen capture failed. Please try again")
        return START_NOT_STICKY
    }
    val codigo = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
    val datos: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
    if (!abrirSesion(codigo, datos)) return START_NOT_STICKY
    isCapturing = true
    showOverlay()
}
```

```kotlin
/** Consume el token y deja la sesión viva para las capturas que vengan. El token sirve
 *  para UNA llamada a getMediaProjection(); el MediaProjection que sale de ahí sirve
 *  para muchas. Devuelve false si el token no servía. */
private fun abrirSesion(codigo: Int, datos: Intent?): Boolean {
    if (codigo == 0 || datos == null) return false
    val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    return try {
        sesion = manager.getMediaProjection(codigo, datos).also { proyeccion ->
            proyeccion.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    // El usuario frenó la proyección desde el panel del sistema, o el
                    // sistema la cortó. La burbuja NO se apaga: el próximo tap pide
                    // consentimiento de nuevo, que es el camino de recuperación.
                    android.util.Log.d("ScreenCapture", "La sesión se cerró desde afuera")
                    sesion = null
                }
            }, Handler(Looper.getMainLooper()))
        }
        true
    } catch (e: SecurityException) {
        android.util.Log.e("ScreenCapture", "Token inválido al abrir la sesión", e)
        sesion = null
        avisar("Screen capture failed. Please try again")
        false
    }
}
```

El campo `sesion` reemplaza a `mediaProjection`, y los campos `resultCode`/`resultData` se borran: el token ya no se guarda.

- [ ] **Step 2: La captura usa la sesión en vez de crearla**

En `captureScreen()`, el bloque que hacía `mediaProjection?.stop()`, `getMediaProjection(...)` y `registerCallback(...)` se reemplaza por la sesión que ya está abierta. El `VirtualDisplay` y el `ImageReader` se siguen creando acá, con las métricas del momento — que es lo que además arregla la rotación:

```kotlin
val proyeccion = sesion
if (proyeccion == null) {
    // La sesión murió entre el tap y el disparo (el usuario la frenó desde el panel).
    android.util.Log.w("ScreenCapture", "Sin sesión al capturar: se pide consentimiento")
    avisar("Screen capture failed. Please try again")
    stopOverlay()
    return@postDelayed
}
imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
virtualDisplay = proyeccion.createVirtualDisplay(
    "ScreenCapture", width, height, density,
    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
    imageReader?.surface, null, null,
)
```

- [ ] **Step 3: Terminar una captura ya no termina el Service**

`terminarServicio()` pasa a llamarse `terminarCaptura()` y cambia de alcance:

```kotlin
/** Cierra la captura, no la sesión: suelta el VirtualDisplay y el ImageReader, que se
 *  crean de nuevo en la próxima, y vuelve a mostrar la burbuja. La sesión sigue viva —
 *  ese es el punto del plan. Sin burbuja (camino de `Capture now`) no hay nada que
 *  sostener y el Service se apaga, como siempre. */
private fun terminarCaptura() {
    virtualDisplay?.release()
    imageReader?.close()
    virtualDisplay = null
    imageReader = null
    isCapturing = false
    burbuja?.visible(true)
    if (apagarTrasCaptura(hayBurbuja = burbuja != null)) detenerTodo()
}
```

`cleanup()` queda sólo para el apagado de verdad, y ahí sí cierra la sesión:

```kotlin
private fun cleanup() {
    virtualDisplay?.release()
    imageReader?.close()
    sesion?.stop()
    virtualDisplay = null
    imageReader = null
    sesion = null
}
```

Reemplazar las llamadas a `terminarServicio()` por `terminarCaptura()` en `stopOverlay()` y en el camino post-OCR (`cerrarConDemora()`).

`onDestroy()` **se queda llamando a `cleanup()`** y no a `terminarCaptura()`: es el tercero de los tres lugares donde la sesión tiene que morir (los otros dos son `detenerTodo()` y el `Callback.onStop()`). Si el sistema mata el Service, esto es lo único que corre, y tiene que soltar la proyección — el indicador de "grabando pantalla" cuelga si no.

- [ ] **Step 4: El tap decide con la lógica de la tarea 1**

```kotlin
private fun onTapBurbuja() {
    when (decidirTap(haySesion = sesion != null)) {
        AccionTap.MostrarOverlay -> {
            if (isCapturing) {
                android.util.Log.w("ScreenCapture", "Ya hay una captura en curso, ignorando")
                return
            }
            isCapturing = true
            showOverlay()
        }
        AccionTap.PedirConsentimiento -> startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_PEDIR_PERMISO
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
    }
}
```

- [ ] **Step 5: Compilar y correr los tests**

Run: `cd app && ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 308 tests, 0 failures.

- [ ] **Step 6: Verificar EL objetivo del plan en dispositivo**

```bash
cd app && ./gradlew installDebug
```

Encender la burbuja, consentir una vez, y **capturar cinco veces seguidas**. El diálogo del sistema tiene que aparecer **una sola vez**. En el log, a partir de la segunda captura no puede aparecer `NO hay credenciales` ni un `getMediaProjection` nuevo.

Si esto no pasa, el resto del plan no importa: parar acá y diagnosticar antes de seguir.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(captura): una sesión de MediaProjection por vida de la burbuja"
```

---

### Task 5b: El VirtualDisplay vive con la sesión, no con la captura

La prueba de aceptación de la tarea 5 falló en el dispositivo con `SecurityException: ...
Don't take multiple captures by invoking MediaProjection#createVirtualDisplay multiple times
on the same instance`, y el proceso murió. Un `MediaProjection` da UN `createVirtualDisplay`;
lo que da N frames es el `VirtualDisplay`. Esta tarea mueve el `VirtualDisplay` y el
`ImageReader` de la captura a la sesión. Ver la sección "Corrección del 2026-09-18" del spec.

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/CapturaService.kt`

**Interfaces:**
- Consumes: lo mismo que la tarea 5 (`decidirTap`, `apagarTrasCaptura`, `BurbujaFlotante`).
- Produces: nada nuevo hacia afuera.

- [ ] **Step 1: La sesión arma el espejo completo**

`abrirSesion()` pasa a crear, además del `MediaProjection`, el `ImageReader` y el
`VirtualDisplay`, una sola vez, con las métricas del momento. **Con la `SecurityException`
atrapada**: sin atrapar, mató el proceso con la burbuja adentro.

```kotlin
/** Arma el espejo de pantalla completo: proyección + ImageReader + VirtualDisplay, todo
 *  de una y para toda la sesión. Un MediaProjection admite UN solo createVirtualDisplay
 *  —Android tira SecurityException en el segundo— pero ese VirtualDisplay entrega frames
 *  indefinidamente. Es el modelo de un grabador de pantalla, y es la única forma de
 *  capturar varias veces con un solo consentimiento. */
private fun armarEspejo(proyeccion: MediaProjection): Boolean {
    val metrics = DisplayMetrics()
    windowManager?.defaultDisplay?.getRealMetrics(metrics)
    anchoEspejo = metrics.widthPixels
    altoEspejo = metrics.heightPixels
    densidadEspejo = metrics.densityDpi
    return try {
        imageReader = ImageReader.newInstance(anchoEspejo, altoEspejo, PixelFormat.RGBA_8888, 2)
        virtualDisplay = proyeccion.createVirtualDisplay(
            "ScreenCapture", anchoEspejo, altoEspejo, densidadEspejo,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null,
        )
        android.util.Log.d("ScreenCapture", "Espejo armado: ${anchoEspejo}x$altoEspejo")
        true
    } catch (e: SecurityException) {
        android.util.Log.e("ScreenCapture", "No se pudo armar el espejo", e)
        liberarEspejo()
        avisar("Screen capture failed. Please try again")
        false
    }
}
```

Campos nuevos: `private var anchoEspejo = 0`, `altoEspejo = 0`, `densidadEspejo = 0`.

`abrirSesion()` llama a `armarEspejo(proyeccion)` justo después de registrar el callback, y
si devuelve false cierra la sesión (`proyeccion.stop()`, `sesion = null`) y devuelve false.

- [ ] **Step 2: La captura sólo saca un frame**

En `captureScreen()`, todo el bloque que creaba `ImageReader` y `VirtualDisplay` desaparece.
Queda: ocultar overlay → esperar el delay → **verificar que el espejo sigue vivo y que las
métricas no cambiaron** → `processCapture()`.

```kotlin
val proyeccion = sesion
if (proyeccion == null || virtualDisplay == null) {
    android.util.Log.w("ScreenCapture", "Sin espejo al capturar: se pide consentimiento")
    avisar("Screen capture failed. Please try again")
    stopOverlay()
    return@postDelayed
}
ajustarEspejoSiRotó()
processCapture()
```

- [ ] **Step 3: La rotación redimensiona el espejo**

El `VirtualDisplay` se creó con un tamaño fijo. Si el usuario rotó, el frame llega con la
geometría vieja y el recorte sale corrido — el bug histórico de esta feature.

```kotlin
/** Un VirtualDisplay conserva el tamaño con el que se creó. Al rotar, la pantalla real
 *  cambia de geometría y los frames seguirían llegando con la vieja: el recorte saldría
 *  corrido, que es exactamente el bug que esta feature arrastró desde el principio. Se
 *  redimensiona el display y se reemplaza el ImageReader, que también tiene tamaño fijo. */
private fun ajustarEspejoSiRotó() {
    val metrics = DisplayMetrics()
    windowManager?.defaultDisplay?.getRealMetrics(metrics)
    if (metrics.widthPixels == anchoEspejo && metrics.heightPixels == altoEspejo) return
    android.util.Log.d(
        "ScreenCapture",
        "La pantalla rotó: ${anchoEspejo}x$altoEspejo -> ${metrics.widthPixels}x${metrics.heightPixels}",
    )
    anchoEspejo = metrics.widthPixels
    altoEspejo = metrics.heightPixels
    densidadEspejo = metrics.densityDpi
    val anterior = imageReader
    imageReader = ImageReader.newInstance(anchoEspejo, altoEspejo, PixelFormat.RGBA_8888, 2)
    virtualDisplay?.resize(anchoEspejo, altoEspejo, densidadEspejo)
    virtualDisplay?.surface = imageReader?.surface
    anterior?.close()
}
```

- [ ] **Step 4: Terminar una captura no desarma el espejo**

`terminarCaptura()` deja de liberar `virtualDisplay`/`imageReader` —ahora son de la sesión—
y se queda con `isCapturing = false`, la burbuja visible y el apagado del Service cuando no
hay burbuja. El espejo se libera en un solo lugar nuevo:

```kotlin
/** Suelta el espejo. Va aparte de cleanup() porque el Callback.onStop() de la proyección
 *  llega cuando la sesión YA murió por fuera: ahí hay que soltar display y reader sin
 *  volver a llamar stop() sobre una proyección muerta. */
private fun liberarEspejo() {
    virtualDisplay?.release()
    imageReader?.close()
    virtualDisplay = null
    imageReader = null
}
```

`cleanup()` llama a `liberarEspejo()` y después `sesion?.stop()`, `sesion = null`. El
`Callback.onStop()` llama a `liberarEspejo()` (no a `cleanup()`) además de su chequeo de
identidad y el `sesion = null`.

**Cuidado con el frame viejo:** el `ImageReader` puede tener un frame de antes de que se
dibujara el overlay. `processCapture()` ya usa `acquireLatestImage()`, que devuelve el más
nuevo y descarta los anteriores; verificá que la `Image` se cierre siempre (el `finally`
que ya existe), porque con un reader de 2 buffers una `Image` sin cerrar tranca el productor
a partir de la captura siguiente.

- [ ] **Step 5: Compilar y correr los tests**

Run: `cd app && ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 308 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "fix(captura): el VirtualDisplay vive con la sesión, no con la captura"
```

La verificación en dispositivo —cinco capturas seguidas con un solo consentimiento, más una
rotación entre capturas— la corre el controlador.

---

### Task 5c: Cerrar la burbuja sin entrar a la app

Hoy la única forma de apagar la burbuja es el `Stop` de la notificación o entrar a la
pantalla Scan. Pedido de uso: **long-press sobre la burbuja la convierte en una ✕, y tocar
la ✕ cierra todo**.

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/BurbujaFlotante.kt`
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/captura/CapturaService.kt`

**Interfaces:**
- Consumes: nada nuevo.
- Produces: el constructor de `BurbujaFlotante` suma un callback: `class BurbujaFlotante(context: Context, windowManager: WindowManager, onTap: () -> Unit, onCerrar: () -> Unit)`.

**Comportamiento exacto, que es lo que hay que respetar:**

- Long-press (umbral: `ViewConfiguration.getLongPressTimeout()`, el del sistema, **no** un número inventado) → la burbuja pasa a modo ✕: mismo tamaño, misma posición, misma ventana. No se crean ventanas nuevas ni menús.
- Tap sobre la ✕ → `onCerrar()`. El Service lo cablea a `detenerTodo()`, que ya apaga sesión, burbuja, notificación y Service.
- Tap en cualquier otro lado, o **3 segundos** sin tocarla → vuelve a la burbuja normal. Un long-press accidental no puede dejar una ✕ armada esperando.
- Arrastrar sigue arrastrando y **cancela** el modo ✕. La distinción arrastre/tap que ya existe (`DRAG_THRESHOLD`, `moved`) no se toca.
- Sin confirmación: apagar la burbuja se deshace en dos taps desde la pantalla Scan, y un diálogo sobre una ventana flotante en HyperOS es justo lo que dio problemas de foco antes.

- [ ] **Step 1: El modo ✕ en `BurbujaFlotante`**

Estado nuevo en la clase:

```kotlin
private var modoCerrar = false
private val handler = Handler(Looper.getMainLooper())
/** Vuelve sola a la burbuja normal: un long-press accidental no puede dejar una ✕
 *  armada esperando el próximo toque. */
private val revertir = Runnable { salirModoCerrar() }
```

La vista ya es un `ImageView` con el ícono de la app y fondo circular indigo
(`BurbujaFlotante.kt:66-90`). El modo ✕ cambia sólo lo visual, sin tocar layout ni ventana:

```kotlin
private fun entrarModoCerrar() {
    if (modoCerrar) return
    modoCerrar = true
    bubbleView?.let { vista ->
        (vista as ImageView).setImageDrawable(
            ContextCompat.getDrawable(context, android.R.drawable.ic_menu_close_clear_cancel)
        )
        (vista.background as GradientDrawable).setColor(Color.parseColor("#E57373")) // rojo suave
    }
    handler.postDelayed(revertir, 3000)
    android.util.Log.d("FloatingBubble", "Modo cerrar activado")
}

private fun salirModoCerrar() {
    if (!modoCerrar) return
    modoCerrar = false
    handler.removeCallbacks(revertir)
    bubbleView?.let { vista ->
        (vista as ImageView).setImageDrawable(
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        )
        (vista.background as GradientDrawable).setColor(Color.parseColor("#9FA8DA"))
    }
}
```

- [ ] **Step 2: Detectar el long-press en el listener que ya existe**

En `ACTION_DOWN` se programa el long-press; en `ACTION_MOVE` que supera `DRAG_THRESHOLD` se cancela (y se sale del modo ✕ si estaba); en `ACTION_UP` se cancela siempre.

```kotlin
// en ACTION_DOWN, junto a downTime / moved:
handler.postDelayed(detectarLongPress, ViewConfiguration.getLongPressTimeout().toLong())
```

```kotlin
private val detectarLongPress = Runnable { entrarModoCerrar() }
```

En `ACTION_UP`, la decisión pasa a ser de tres ramas en vez de dos:

```kotlin
handler.removeCallbacks(detectarLongPress)
when {
    moved -> { /* fue arrastre: el snap ya corrió, nada más */ }
    modoCerrar -> {
        android.util.Log.d("FloatingBubble", "Tap en la ✕: se cierra todo")
        salirModoCerrar()
        onCerrar()
    }
    else -> {
        android.util.Log.d("FloatingBubble", "Click detectado! Ejecutando onTap()")
        onTap()
    }
}
```

En `ACTION_MOVE`, cuando `moved` pasa a true: `handler.removeCallbacks(detectarLongPress)` y `salirModoCerrar()`.

En `ACTION_OUTSIDE` (el toque cayó fuera de la burbuja): `salirModoCerrar()`. Eso cubre el "tap en cualquier otro lado".

`ocultar()` tiene que hacer `handler.removeCallbacks(...)` de los dos runnables: si la ventana se va con un callback pendiente, el runnable toca una vista ya desmontada.

- [ ] **Step 3: El Service cablea `onCerrar`**

En `CapturaService`, donde hoy se construye la burbuja:

```kotlin
val nuevaBurbuja = BurbujaFlotante(this, windowManager!!, ::onTapBurbuja, ::detenerTodo)
```

- [ ] **Step 4: Compilar y correr los tests**

Run: `cd app && ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 308 tests, 0 failures. (Esto es UI de ventana flotante: no es testeable en JVM plano, igual que el resto de la clase.)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(captura): cerrar la burbuja con long-press sin entrar a la app"
```

La verificación en dispositivo —long-press, ✕, tap que cierra, el timeout de 3 s, y que el
arrastre siga funcionando— la corre el controlador.

---

### Task 6: Smoke de dispositivo y documentación

**Files:**
- Modify: `docs/smoke-captura-ocr.md`
- Modify: `docs/ESTADO.md`

- [ ] **Step 1: Re-correr los pasos que tocan el ciclo de vida del Service**

Del `docs/smoke-captura-ocr.md`: pasos **4, 5, 6, 16, 17, 18, 19 y 20**. Anotar el resultado de cada uno con la evidencia de logcat, como las corridas anteriores. Recordatorios de método que ya costaron tiempo:

- Filtrar por `--pid`: SurfaceFlinger de HyperOS usa el mismo tag `ScreenCapture`.
- `input swipe x y x y <ms>` para la burbuja; `input tap` para la UI de Compose.
- Un swipe que arranque en x < ~80 lo come el gesto de Back del sistema.
- La ventana del paso 19 depende del texto: ~150 ms con 40 chars, ~360 ms con 194. Capturar un área densa y encadenar el segundo tap ~420 ms después del botón `Capture`, todo en el mismo `adb shell`.

- [ ] **Step 2: Correr los pasos nuevos**

Agregarlos al documento y correrlos:

1. **Cinco capturas seguidas con un solo consentimiento.**
2. Frenar la proyección desde el panel del sistema → el siguiente tap pide permiso de nuevo y la burbuja sigue.
3. Apagar y encender la burbuja → la sesión se cierra y se vuelve a pedir.
4. **Burbuja activa media hora sin capturar** → ¿sigue la burbuja? ¿captura sin re-pedir? Es el riesgo #1 del spec.
5. Rotar el teléfono entre dos capturas → el recorte no sale corrido.
6. Una sola notificación persistente, y su `Stop` apaga todo.
7. `Capture now` sin burbuja → captura una vez y el Service se apaga solo, sin notificación colgada.

- [ ] **Step 3: Actualizar ESTADO**

Fila nueva en la tabla de planes y, en el bullet de **Captura OCR (Plan E)**, corregir la afirmación vieja ("Android 14+ invalida el token … y pide consentimiento en cada captura (regla del OS, no bug)") por lo que ahora está verificado: el token es de un solo uso, la sesión no, y el consentimiento es uno por vida de la burbuja.

- [ ] **Step 4: Commit y PR**

```bash
git add -A
git commit -m "docs: smoke de la sesión de MediaProjection y estado"
```

Abrir el PR con: qué cambió, la evidencia de las cinco capturas con un solo consentimiento, y el resultado del paso 4 (media hora) — que es el que dice si la feature es viable en HyperOS.
