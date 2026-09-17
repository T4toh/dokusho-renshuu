# Smoke de dispositivo — captura OCR (Plan E)

> **PARCIALMENTE EJECUTADA — 2026-09-16, ampliada el 2026-09-17.** Las dos corridas
> fueron en un POCO 2412DPC0AG, HyperOS V816OS3.0, **Android 16 (API 36)**, navegación
> por gestos. Ese dispositivo cubre las tres condiciones de riesgo del plan a la vez:
> ROM Xiaomi (los workarounds de MIUI), Android 14+ (consentimiento de MediaProjection
> en cada captura) y gestos (el margen de los botones del overlay).
>
> **Verificado en dispositivo:** pasos 4, 5, 6, 16 y 17 — burbuja, permisos, captura,
> OCR, **el recorte a la selección**, las dos salidas del overlay y el camino sin texto.
> **Sin verificar todavía:** pasos 18, 19 y 20 (captura en frío, segundo tap durante el
> OCR, revocar el permiso de overlay en caliente).
> **Obsoletos:** los pasos 7 a 15 describen el flujo viejo (una captura = una historia
> importada, pantalla `Import`). El Plan F lo reemplazó: una captura ahora crea un
> `Recorte` y abre la vista de nota. El equivalente vigente está en
> `docs/smoke-recortes.md`.
>
> **Bug encontrado y arreglado el 2026-09-17:** con la burbuja activa la app dejaba de
> responder a toques y al Back. Ver "Hallazgo" abajo.

## Resultados de la corrida del 2026-09-17 (conducida por adb)

Build debug de `main` 82a2e86. La corrida se manejó con `adb shell input tap/swipe/keyevent`
y `adb shell screencap`, no con el dedo — para los pasos de abajo da lo mismo (el sistema
inyecta MotionEvent/KeyEvent reales), pero queda anotado porque el hallazgo del final
merece una contraprueba con el dedo.

**Unit tests antes de la corrida:** `./gradlew testDebugUnitTest` → **299 tests, 0 failures,
0 errors**.

### Paso 6 — el recorte a la selección: **PASA** (era el hueco principal del plan)

Selección arrastrada de (77,562) a (1163,736) sobre el botón "Capture now" de la propia
pantalla Scan, o sea un texto conocido de antemano:

```
Bitmap creado: 1220x2712
Recorte escalado: Recorte(left=77, top=562, ancho=1086, alto=174)
OCR devolvió 11 chars
```

El rectángulo escalado coincide exactamente con el arrastre (1163-77 = 1086, 736-562 = 174)
y los 11 chars son exactamente `"Capture now"` — o sea el recorte mapeó a lo que el usuario
eligió, no a otra parte de la pantalla. Confirmación cruzada en disco: la nota quedó como
`files/recortes/1789656802773.json` con `"texto":"Capture now"`, y su `.jpg` pesa **10.7 KB**
contra los **~355 KB** de las dos notas anteriores, que sí eran de pantalla completa.

**Salvedad que queda abierta:** en este dispositivo el overlay y el bitmap miden lo mismo
(1220x2712), así que el escalado corrió con factor **1:1**. El camino con factor ≠ 1 —el
que producía el recorte corrido en la app vieja— sigue sin ejercitarse en hardware; lo
cubren los 8 tests de `TextoOcrTest` en JVM. Para ejercitarlo haría falta un dispositivo
donde el overlay no cubra las barras de sistema.

### Paso 16 — salir del overlay: **PASA (a) y (b)**

- **(a) Back:** `Back en el overlay: se trata como Cancel` → `stopOverlay()` → `Bubble
  visible=true`. El overlay se cierra y la burbuja vuelve. (Inyectado como `KEYCODE_BACK`;
  no se probó el gesto de borde.)
- **(b) Cancel:** se ve **entero y por encima** de la franja de gestos (captura de pantalla
  adjunta en la corrida) y el toque llega: `Botón CANCELAR presionado` → `stopOverlay()`.
  O sea `margenInferiorBotones()` da bien en este dispositivo — era el único número que no
  se había podido validar sin hardware.

En los dos casos la app **no** pasó al frente y la burbuja siguió respondiendo.

### Paso 17 — capturar un área sin texto: **PASA**

Selección (200,1500)→(900,2000) sobre fondo liso:

```
Recorte escalado: Recorte(left=200, top=1500, ancho=695, alto=497)
OCR devolvió 0 chars
OCR sin texto: no se abre la app
```

No se creó ninguna nota (`files/recortes/` quedó en los mismos 6 archivos = 3 notas).

### Tramo post-OCR: cubierto

La captura del paso 6 aterrizó en la vista de nota con el texto reconocido y la imagen
guardada, y la nota figura en la pestaña Notes de la biblioteca. Esto reemplaza a los
pasos 7-15, escritos para el flujo de `Import` que el Plan F retiró.

### Hallazgo: con la burbuja activa la app no responde a toques ni al Back

Reproducido dos veces, y con contraprueba negativa:

| Estado | Back en la pantalla Scan | Toque en `Close` / `Capture now` |
| ------ | ------------------------ | -------------------------------- |
| Sin burbuja | sale a la biblioteca | responden |
| Con burbuja activa | no hace nada | no hacen nada |

La diferencia la muestra `dumpsys window`: apenas arranca la burbuja, el foco de entrada
se lo lleva su ventana, no la Activity.

```
# con burbuja
mCurrentFocus=Window{8eb3f0d u0 com.tatoh.dokushorenshu}          <- ventana type=2038 del bubble
# sin burbuja
mCurrentFocus=Window{48999e9 u0 com.tatoh.dokushorenshu/...MainActivity}
```

La ventana de la burbuja mide 182x182 en (20,200), así que **no** está tapando los
controles que dejan de responder: `mAttrs={(20,200)(182x182) ... ty=APPLICATION_OVERLAY}`.

**Causa:** `FloatingBubbleService.showBubble()` arma la ventana **sin** `FLAG_NOT_FOCUSABLE`,
a propósito y con el comentario puesto — *"Remover FLAG_NOT_FOCUSABLE para que MIUI
reconozca la ventana"*. Es un workaround heredado de `Kanji-no-Ryoushi`. Una ventana
`TYPE_APPLICATION_OVERLAY` focusable se queda con el foco de teclas mientras existe, que es
la misma clase de problema que el fix del paso 16 arregló para el overlay de captura —
sólo que acá sigue vivo, y encima en HyperOS se lleva puestos también los toques a la
Activity (probablemente la protección anti-tapjacking de la ROM, que descarta eventos
hacia una ventana obscurecida por otra de la misma app).

**Arreglado el mismo día:** se le devolvió `FLAG_NOT_FOCUSABLE` a la ventana de la burbuja
(`flags` pasa de `0x01040020` a `0x01040028`). La burbuja sólo maneja toques, no teclas, así
que no necesita el foco.

El workaround de MIUI **ya no hace falta** en esta ROM: con el flag puesto la burbuja se
sigue viendo y funcionando igual. Reverificado en el mismo POCO, todo sobre el build parcheado:

| Chequeo | Resultado |
| ------- | --------- |
| `mCurrentFocus` con la burbuja activa | `.../MainActivity` (antes: la ventana de la burbuja) |
| Back con la burbuja activa | sale de Scan a la biblioteca |
| Toques a la app con la burbuja activa | responden (cambio de pestaña Stories/Notes) |
| Burbuja visible en HyperOS | sí |
| Arrastre + snap al borde | `ACTION_UP recibido, moved=true`, snap OK |
| Tap corto en la burbuja | `Click detectado! Ejecutando onBubbleClicked()` |
| Captura completa | `Recorte escalado: Recorte(left=77, top=562, ancho=1077, alto=172)`, `OCR devolvió 11 chars`, nota nueva en disco |
| Back en el overlay de captura (paso 16a) | `Back en el overlay: se trata como Cancel` |
| Unit tests | 299 tests, 0 failures |

Lo que queda sin poder verificar acá: si el workaround todavía hace falta en **MIUI viejo**
(la ROM para la que se escribió, de 2025). No hay dispositivo. Si aparece un reporte de que
la burbuja no se ve en MIUI antiguo, este flag es el primer sospechoso.

## Resultados de la corrida del 2026-09-16


Evidencia de `adb logcat` (PID 18859 de la app; ojo que SurfaceFlinger de HyperOS usa
el **mismo tag `ScreenCapture`** con otro PID — filtrar por `--pid`).

**Funcionó:**

- Burbuja añadida al `WindowManager` (`type=2038`, o sea `TYPE_APPLICATION_OVERLAY`),
  arrastre y snap al borde (`ACTION_UP recibido, moved=true`).
- Tap corto detectado y distinguido del arrastre (`Click detectado!`).
- Camino sin credenciales: `captureResultCode = 0` → abre MainActivity a pedir permiso.
- `startForeground() llamado con tipos múltiples` — el combo
  `mediaProjection|specialUse` de Android 14+ lo acepta HyperOS.
- Overlay añadido, botón `Capture` responde.
- `VirtualDisplay` creado a 1220x2712, densidad 520.
- **`OCR devolvió 120 chars`** — ML Kit japonés reconoció texto real on-device.
- Overlay removido y `MediaProjection.Callback.onStop()`; sin crash, sin overlay colgado.

**NO se verificó (y el paso quedó pendiente, no aprobado):**

- **El recorte a la selección.** El log dice `Recorte escalado: null` y
  `Bitmap creado: 1220x2712`: se OCReó la pantalla entera. O no se arrastró, o el
  arrastre fue menor al umbral de 10 px. Toda la lógica de `escalarRecorte` —el bug
  histórico de la app vieja, con 8 tests unitarios— **sigue sin correr en hardware**.
- **Paso 16 (Back y Cancel en el overlay).** Sin rastro en el log. Es el fix del
  Critical del review final: si `Cancel` queda bajo la barra de gestos, el usuario
  queda encerrado.
- **El tramo post-OCR.** `files/importadas/` vacío y `palabras_tocadas` en 0 después
  de la corrida: el texto reconocido nunca llegó a ser historia, así que no se probó
  ni `ImportScreen` precargada, ni la furigana, ni `PalabraSheet` sobre texto capturado.

**Defecto de logging detectado:** `Recorte escalado: null` no distingue "el usuario no
arrastró" de "la selección era inusable". Con esa línea sola no se puede saber cuál de
las dos pasó — conviene loguear el `Recorte` de entrada y el tamaño del overlay.

Requiere Android 10+ (la captura se deshabilita sola en 9 o menos).

```
cd app && ./gradlew installDebug
```

1. **Biblioteca → botón `Scan`.** Esperado: el top bar muestra `Scan Import Export About`
   y abre la pantalla "Scan". *Mirar si los cuatro labels entran en teléfono vertical* — si
   se cortan, se superponen o desaparecen, mover `About` a un menú overflow (no se cambió
   nada preventivamente).
2. **`Grant overlay permission`.** Esperado: se abre Ajustes del sistema en "Mostrar sobre
   otras apps"; al conceder y volver con Back, el aviso de permiso desaparece de la pantalla
   sin necesidad de reabrirla.
3. **`Grant notification permission`** (solo Android 13+). Esperado: diálogo del sistema; al
   aceptar, el aviso desaparece.
4. **`Start floating button`.** Esperado: aparece la burbuja flotante sobre la app; se
   arrastra con el dedo y al soltar hace snap al borde más cercano. Notificación persistente
   del servicio en la barra.
5. **Salir a otra app con japonés** (manga, Twitter, un navegador). Tap en la burbuja.
   Esperado: aparece el diálogo/overlay de captura y la pantalla se oscurece.
   - *Primera vez / Android 14+ después de una sesión:* en vez del overlay se abre Dokusho
     en la pantalla "Scan" y salta solo el diálogo de MediaProjection ("Start recording?").
     Al aceptar, volver a la otra app y tocar la burbuja de nuevo.
6. **Arrastrar para seleccionar un área con texto → `Capture`.** Esperado: el recuadro sigue
   al dedo y el recorte coincide con lo que se ve. Si el recorte sale corrido, anotar los
   valores del log `"Recorte escalado: ..."` (`adb logcat -s ScreenCapture`) **antes** de
   tocar nada: el bug estaría en `escalarRecorte` o en las dimensiones que se le pasan.
7. **Esperado: Dokusho pasa al frente en la pantalla `Import`**, con el texto japonés
   reconocido ya cargado en el campo de texto y el título `Scan <fecha> <hora>` (fecha y
   hora reales). Si el campo aparece vacío, el texto no llegó por el Intent; si aparece la
   biblioteca en vez del import, falló la navegación.
8. **Rotar el teléfono acá.** Esperado: el texto y el título **siguen igual**; no se
   duplica, no se vacía, y el título **no** cambia de minuto. Este es el punto que más se
   puede romper.
9. **Editar el texto a mano** (el OCR vertical puede desordenar columnas) y tocar `Import`.
   Esperado: **vuelve a la Biblioteca** (no abre el lector — es el comportamiento del código
   tal cual quedó, no lo que decía el spec original) y la historia nueva aparece en la lista
   con el título `Scan <fecha> <hora>`.
10. **Abrir esa historia desde la lista.** Esperado: el lector con furigana sobre el texto
    capturado. Tap en una palabra → se abre `PalabraSheet` con definición y ejemplos.
11. **Back a Biblioteca → `Export`.** Esperado: la historia capturada figura en "Dokusho —
    Stories".
12. **Segunda captura seguida:** volver a la otra app, capturar otro texto. Esperado: se
    abre **una sola** pantalla de Import, con el texto **nuevo** y un título con la hora
    nueva; el Back desde ahí lleva a la Biblioteca (no a un Import anterior apilado).
    Importar y verificar que las dos historias conviven en la lista (la segunda con sufijo
    si el título colisionó).
13. **Back desde el Import sin importar** y luego rotar en la Biblioteca. Esperado: el
    texto capturado **no** reaparece ni se vuelve a abrir el Import.
14. **Rotar en la pantalla Scan habiendo llegado por la burbuja** (o sea con
    `permiso=true`: tap en la burbuja sin credenciales → Dokusho abre Scan y salta el
    diálogo → **cancelar** el diálogo y rotar el teléfono). Esperado: el diálogo "Start
    recording?" **no** vuelve a aparecer solo (valida el fix de `rememberSaveable` en
    `CapturaScreen.kt`). Contraprueba: salir de la pantalla Scan, volver a la otra app y
    tocar la burbuja otra vez → el diálogo **sí** tiene que aparecer (instancia nueva de la
    pantalla). Mismo chequeo en el camino de `popBackStack`: capturar, importar desde el
    Import, y al volver — si el stack tenía la pantalla Scan abajo — el diálogo no debe
    saltar.
15. **Muerte de proceso con el Import abierto:** con el texto capturado en pantalla,
    `adb shell am kill com.tatoh.dokushorenshu` (o Developer Options → "Don't keep
    activities") y volver a la app desde recientes. Esperado: el Import vuelve con el texto
    capturado **reinyectado** y un título con timestamp **nuevo**; se pierden las ediciones
    manuales. Esto es un agujero aceptado, no un bug: el sistema reentrega el Intent
    original y la marca de consumido es in-process (`ImportViewModel` no tiene
    `SavedStateHandle`, el borrador ya estaba perdido). **No reportarlo.** Lo que sí sería
    un bug es que el texto aparezca **duplicado** o que la app abra la biblioteca vacía.

## Caminos de falla (agregados en la ola de fixes del review final)

Los pasos 1-15 recorren el camino feliz. Estos cinco son los que el review marcó como
"silenciosos": cada uno tiene que producir algo **visible**, y si no lo produce, es bug.

16. **Salir del overlay de selección — en un teléfono con navegación por gestos.** Tocar la
    burbuja, esperar el overlay oscuro, y probar las dos salidas por separado:
    - **(a) Botón/gesto Atrás.** Esperado: el overlay se cierra y la burbuja vuelve, igual
      que con Cancel. Si Atrás no hace **nada**, es la regresión que arregló este fix (la
      ventana se queda con el foco de teclas y antes nadie manejaba `KEYCODE_BACK`, o sea
      el botón atrás quedaba muerto en todo el sistema mientras el overlay estuviera
      arriba).
    - **(b) Botón `Cancel`.** Esperado: se ve **entero y por encima** de la franja de
      gestos / barra de navegación, y se puede tocar. Si queda tapado o el toque se lo
      lleva el sistema, el margen inferior calculado (`margenInferiorBotones()`) se está
      quedando corto en este dispositivo — anotar modelo y versión de Android: es el único
      número que no se pudo validar sin hardware.
    - En ambos casos, después del cierre la app **no** debe abrirse y la burbuja tiene que
      responder a un tap nuevo.
17. **Capturar un área SIN texto** (un fondo liso, una foto). Esperado: un aviso corto
    `No text found in the selected area` y **nada más**: la app **no** pasa al frente y
    **no** se abre la pantalla Import. Contraprueba importante: capturar primero un texto,
    quedarse en el Import editándolo a mano, volver a la otra app, capturar un área vacía →
    el texto editado tiene que **seguir intacto** (antes se entregaba el string vacío y
    pisaba la edición).
18. **Captura en frío / teléfono lento:** reiniciar el teléfono, y **en cuanto** se pueda
    arrancar la burbuja y capturar (sin esperar a que el sistema se asiente). Esperado: o
    bien la captura sale normal, o bien aparece el aviso `Screen capture failed. Please try
    again`. Lo que **no** puede pasar es que el overlay desaparezca en silencio sin aviso
    ni Import. Si el aviso sale seguido, anotar cuántas veces de cuántas: es la evidencia
    que justificaría cambiar el `postDelayed` fijo de 200 ms por un
    `setOnImageAvailableListener` (hoy no se toca: es código portado y probado en
    dispositivo, y cambiar timings a ciegas es peor).
19. **Segundo tap mientras el OCR corre:** capturar un texto largo y, apenas vuelve la
    burbuja, tocarla otra vez enseguida. Esperado: aparece el aviso `Recognizing text...` y
    el segundo tap **no hace nada** — es a propósito, una captura en curso descarta las
    demás. Lo que sería bug: que se abran dos overlays, que la app abra dos Imports, o que
    la burbuja quede muerta después (tras terminar la primera captura tiene que volver a
    responder).
20. **Revocar el permiso de overlay con la burbuja corriendo:** con la burbuja activa, ir a
    Ajustes → Apps → Dokusho → "Mostrar sobre otras apps" y **quitarlo**; volver y tocar la
    burbuja (si sigue en pantalla) o relanzarla. Esperado: **ningún crash**. Lo aceptable
    es un aviso `Could not show the capture overlay`, o que la burbuja desaparezca sola, o
    que la app abra la pantalla Scan pidiendo el permiso de nuevo. Lo que sería bug es un
    "Dokusho se detuvo" / cierre del proceso.
