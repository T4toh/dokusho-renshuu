# Una sesión de MediaProjection por vida de la burbuja — diseño

> Depende de: `docs/superpowers/specs/2026-09-16-captura-ocr-overlay-design.md` (Plan E).
> Ese plan dejó la captura funcionando; este cambia **cuántas veces hay que pedir permiso**.

## Problema

Hoy la app pide el consentimiento de MediaProjection en **cada** captura. Leyendo manga
eso es un diálogo del sistema cada treinta segundos, y es el reclamo más concreto que
salió de usar la feature en dispositivo.

La justificación que quedó escrita en el spec del Plan E —"Android 14+ invalida el token
después de cada sesión, no hay forma de evitarlo"— **es incorrecta**, y conviene decirlo
con todas las letras porque es la premisa que hay que desarmar:

- Lo de un solo uso es el **token**: el par `resultCode` + `resultData` que devuelve el
  diálogo sirve para **una** llamada a `getMediaProjection()`.
- El **`MediaProjection`** que sale de esa llamada puede vivir y parir **N**
  `VirtualDisplay`. Un grabador de pantalla mantiene una sesión y saca miles de frames.

Lo que fuerza el diálogo por captura no es Android: es nuestro código. `terminarServicio()`
llama a `cleanup()`, que hace `mediaProjection?.stop()`, y después `stopSelf()`. O sea
**una vida de Service = una captura**, por construcción.

Contraprueba ya registrada en dispositivo (`docs/smoke-captura-ocr.md`, corrida del
2026-09-17): con el fix del PR #21, cancelar el overlay conserva el token y la captura
siguiente obtiene su `MediaProjection` sin volver a consentir. El token sobrevive mientras
nadie lo use; lo que hay que dejar de tirar es la sesión.

## Decisión

Una sesión de `MediaProjection` por **vida de la burbuja**: se consiente una vez —en el
primer tap que la necesite— y esa sesión sirve todas las capturas hasta que la burbuja se
apague.

Y para sostenerla, **un solo Service**. `FloatingBubbleService` desaparece; el Service de
captura pasa a hospedar también la burbuja.

### Por qué un solo Service y no dos

Con dos Services de vida larga habría **dos notificaciones persistentes** (hoy conviven
sólo los segundos que dura una captura). Fusionarlos además:

- borra las credenciales estáticas `captureResultCode` / `captureResultData`, que son
  estado global compartido entre dos Services — exactamente la clase de acoplamiento que
  produjo el bug del PR #21 (un `cleanup()` de un Service pisando el permiso del otro);
- borra el `startForegroundService()` de un Service a otro y, con él, las restricciones de
  Android 14 para arrancar un FGS de tipo `mediaProjection` desde background: pasa a ser
  una llamada de instancia dentro de un Service ya vivo;
- deja un solo ciclo de vida que razonar en vez de dos que hay que mantener sincronizados.

### Lo que NO se mantiene vivo

Sólo el `MediaProjection`. El `VirtualDisplay` y el `ImageReader` se siguen creando y
liberando **por captura**, como hoy. Mantenerlos vivos costaría frames continuos (CPU y
batería) y dos buffers de pantalla completa retenidos, sin ninguna ganancia: el token ya
está consumido.

Efecto lateral bienvenido: como se crean frescos con las métricas del momento, **rotar el
teléfono entre capturas deja de ser un problema**. Hoy el `VirtualDisplay` se crea con un
tamaño fijo y nadie lo recrea.

## Arquitectura

`CapturaService` (renombre de `ScreenCaptureService`: con la burbuja adentro, el nombre
viejo mentiría). Cuatro acciones:

| Acción | Quién la manda | Qué hace |
| --- | --- | --- |
| `INICIAR` | `Start floating button` en Scan | `startForeground(specialUse)` + muestra la burbuja. Sin credenciales todavía. |
| `ABRIR_SESION` | la Activity, con el resultado del consentimiento | re-llama `startForeground(mediaProjection\|specialUse)`, crea el `MediaProjection`, registra su callback, y sigue derecho al overlay (el usuario venía de tocar la burbuja) |
| `CAPTURAR` | el tap de la burbuja (interno, sin Intent) | con sesión → overlay; sin sesión → abre MainActivity a pedir consentimiento |
| `DETENER` | `Stop` de la notificación / `Stop floating button` | libera la sesión, quita la burbuja, `stopSelf()` |

Una sola notificación persistente: la actual de la burbuja ("Quick capture active", con su
acción `Stop`). La segunda notificación, la de captura, desaparece.

### El camino sin burbuja: `Capture now`

La pantalla Scan permite capturar sin encender la burbuja, y ese camino **no tiene sesión
que sostener**: nadie va a tocar una burbuja después. Se resuelve con las mismas acciones,
sin caso especial nuevo:

- `Capture now` pide consentimiento y manda `ABRIR_SESION` igual que siempre. El Service
  arranca sin haber recibido `INICIAR`, así que no hay burbuja: `burbuja == null`.
- Al terminar esa captura, como no hay burbuja, el Service **cierra la sesión y se apaga**
  — exactamente el comportamiento de hoy.

O sea la regla es una sola y se lee del estado: **la sesión vive mientras viva la burbuja**.
Sin burbuja, una captura = una sesión, como hasta ahora.

### Qué pasa al terminar una captura

`terminarServicio()` pasa a llamarse `terminarCaptura()` y cambia de alcance: libera el
`VirtualDisplay` y el `ImageReader`, pone `isCapturing = false` y vuelve a mostrar la
burbuja. **No toca la sesión ni apaga el Service** — salvo en el camino sin burbuja de
arriba, que es el único lugar donde sigue haciendo `stopSelf()`.

### Estado

```kotlin
private var sesion: MediaProjection? = null   // instancia, NO estático
private var burbuja: BurbujaFlotante? = null
private var isCapturing = false               // sin cambios: una captura a la vez
```

`sesion != null` es la única fuente de verdad sobre si hace falta consentir. No hay copia
estática de las credenciales: el token se consume al abrirla y no se guarda.

### Ciclo de la sesión

Se abre una vez por vida de burbuja. Muere en exactamente tres lugares:

1. `DETENER` (el usuario apaga la burbuja o toca `Stop`),
2. `onDestroy` del Service,
3. `MediaProjection.Callback.onStop()` — el usuario frenó la proyección desde el panel del
   sistema, o el sistema la cortó.

En el caso 3 la sesión pasa a `null` y **la burbuja sigue en pantalla**: el siguiente tap
la ve vacía y pide consentimiento de nuevo. O sea el camino de hoy no se tira, se convierte
en el fallback — y es un camino ya probado en dispositivo.

### La burbuja deja de ser un Service

Sale `captura/BurbujaFlotante.kt`: una clase con `mostrar()`, `ocultar()`,
`visible(Boolean)` y un callback `onTap`, que adentro tiene el drag, el snap al borde y el
`FLAG_NOT_FOCUSABLE` **con su comentario de por qué está** (el fix del PR #20: sin ese flag
la ventana se queda el foco de entrada y en HyperOS la app deja de responder a toques y al
Back). El Service la instancia y la guarda como campo.

Se van los estáticos `instance` y `setBubbleVisible()`: ocultar la burbuja mientras se
dibuja el overlay pasa a ser una llamada de instancia.

## Errores y caminos de falla

| Qué falla | Qué tiene que pasar |
| --- | --- |
| Token inválido al abrir la sesión (`SecurityException`) | aviso `Screen capture failed. Please try again`, `sesion = null`, la burbuja sigue viva. El `catch` ya existe, sólo cambia de lugar |
| El sistema rechaza promover el tipo de foreground a `mediaProjection` | mismo camino que el token inválido |
| Permiso de overlay revocado en caliente | ya cubierto y verificado (paso 20 del smoke): la burbuja desaparece sola, sin crash |
| El sistema mata el Service | `START_NOT_STICKY`, no revive: la burbuja y su notificación desaparecen. Aceptado, ver riesgos |
| Segundo tap mientras corre el OCR | el guard `isCapturing` queda igual (verificado: `Ya hay una captura en curso, ignorando`) |

## Verificación

**Lo que se puede testear en JVM es poco, y conviene decirlo sin adornos:** los Services son
Android puro y hoy están cubiertos por compilación más smoke de dispositivo. Eso no cambia.

Lo único que vale extraer como lógica pura es la decisión del tap y las transiciones de la
sesión: `decidir(haySesion: Boolean): Accion` → `MostrarOverlay` | `PedirConsentimiento`, y
abrir / `onStop` / detener. Cuatro tests chicos, en la línea de `escalarRecorte` y
`TextoOcrTest`: lo testeable se separa de lo que toca el framework.

### Smoke — pasos que hay que re-correr

Del `docs/smoke-captura-ocr.md`: **4, 5, 6, 16, 17, 18, 19 y 20**. Todos tocan el ciclo de
vida del Service, que es justamente lo que cambia.

### Smoke — pasos nuevos

1. **N capturas seguidas con un solo consentimiento.** El objetivo del plan: si esto no
   pasa, no hay feature. Capturar cinco veces sin que aparezca el diálogo.
2. Frenar la proyección desde el panel del sistema → el siguiente tap pide permiso de nuevo
   y la burbuja sigue en pantalla.
3. Apagar y encender la burbuja → la sesión se cierra y se vuelve a pedir.
4. **Burbuja activa media hora sin capturar** → ¿sobrevive el Service en HyperOS? Es el
   riesgo principal, y se mide mirando si la burbuja sigue ahí y si captura sin re-pedir.
5. Rotar el teléfono entre dos capturas → el recorte no sale corrido. Hoy no está cubierto;
   el `VirtualDisplay` fresco debería arreglarlo, pero hay que verlo.
6. Una sola notificación persistente, y su `Stop` apaga todo (sesión, burbuja y Service).
7. **`Capture now` sin burbuja encendida**: captura una vez, y al terminar el Service se
   apaga solo — sin notificación colgada ni indicador de grabación permanente.

## Riesgos

1. **HyperOS mata el foreground service de larga vida.** Antes el Service vivía segundos;
   ahora vive horas. Es el riesgo que puede tumbar la feature entera. Mitigación: el
   fallback ya existe y ya está probado — sin sesión, el tap pide consentimiento de nuevo,
   o sea se degrada exactamente al comportamiento de hoy. Se mide en el paso 4 del smoke.
2. **Que Android invalide la sesión en algún evento que no previmos** (rotar, cambiar de
   tarea, split screen). Sin dato previo. El `Callback.onStop()` lo detecta y degrada al
   fallback, así que el peor caso conocido es volver al comportamiento actual.
3. **El rename del Service toca el manifest.** Si queda mal, la burbuja no arranca — lo
   agarra el primer paso del smoke.
4. **El indicador de "grabando pantalla" queda prendido** mientras la burbuja esté activa.
   Decisión tomada a propósito: es el precio de no ver un diálogo por captura.

## Fuera de alcance

- Mantener el `VirtualDisplay` vivo entre capturas (no hace falta y cuesta CPU y memoria).
- Revivir el Service si el sistema lo mata (`START_STICKY`): con la sesión muerta, revivir
  no sirve de nada.
- El resto del backlog de OCR: Quick Settings Tile, zoom en el overlay, contraste/brillo
  pre-OCR, títulos editables en las notas, y la cascada de `palabras_tocadas` al borrar.
