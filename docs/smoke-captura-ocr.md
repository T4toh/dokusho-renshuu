# Smoke de dispositivo — captura OCR (Plan E)

> **NO EJECUTADA.** No hubo dispositivo ni emulador disponible durante la Task 5 ni la
> Task 6 (`adb devices` vacío en ambos entornos). Todo lo demás de la feature está
> verificado por compilación y 246 tests unitarios (0 failures), pero **nada probó
> todavía que la captura funcione en un teléfono real**. Copiado desde
> `task-5-report.md` (directorio `.superpowers/sdd/` git-ignoreado) para que sobreviva.
> Queda para el humano — ver enlace desde `docs/ESTADO.md`.

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
