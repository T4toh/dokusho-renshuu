# Smoke de dispositivo — recortes/notas (Plan recortes-notas)

> **EJECUTADA — 2026-09-17, en dos tandas.** POCO 2412DPC0AG, HyperOS,
> **Android 16 (API 36)**, navegación por gestos — el mismo equipo del smoke de
> `docs/smoke-captura-ocr.md`. Requiere permiso de overlay y de notificaciones
> ya concedidos (si no, repetir los pasos 2-3 de ese documento antes de empezar
> acá).
>
> Primera tanda (a mano): captura → nota, furigana y diccionario sobre el texto
> capturado, la pestaña Notes sin la doble app bar, el FAB `Scan` alcanzable; se
> encontró y arregló en el acto el grid de Stories pegado a las pestañas.
> Segunda tanda (por adb): **pasos 15, 16, 17 y 18**, que eran los que quedaban.
> Resultados abajo.
>
> Nada de esto es testeable sin hardware — es la única verificación que la
> feature va a tener. Cada paso dice qué esperar para que una falla sea
> reconocible.

## Resultados de los pasos 15-18 (2026-09-17, por adb)

Build debug de `main` `da92143`. Los cuatro **PASAN**.

### Paso 15 — el borrado se lleva los dos archivos: **PASA**

Long-press en la nota más nueva → diálogo `Delete note?` / *"This also deletes its
image."* → `Delete`. En disco desaparecieron **los dos** archivos del par
(`1789668938245.json` y `1789668938245.jpg`): `files/recortes/` pasó de 16 a 14
entradas. Que falte uno solo era el modo de falla que el paso vigila.

### Paso 16 — espacio en disco: **PASA**

Con 8 notas: `du -sh files/recortes/` → **1.8M**, y el `.jpg` más pesado 388 KB
(una captura de pantalla completa). El umbral de alarma del paso son decenas de
MB, que es lo que daría guardar PNG; 388 KB por pantalla completa confirma JPEG
calidad 90.

### Paso 17 — los mazos no se mezclan: **PASA**

Se tocó `私` en la historia ごん狐 y ya había `こと` tocada en una nota. Exportados
por separado y abiertos con sqlite desde el `.apkg`:

| Mazo | Notas | Deck names en el `.apkg` |
| ---- | ----- | ------------------------ |
| `dokusho-words.apkg` | `私` | `Dokusho — Words`, `Dokusho — Kanji` |
| `dokusho-scans.apkg` | `こと` | `Dokusho — Scans` |

O sea `Scans` **no** trae vocabulario de historias ni `Words` vocabulario de notas,
y los GUID son disjuntos (`c.z6J^kk0L` vs `A9i;vtpgP_`). **Sin ejercitar:** el cruce
—la misma palabra tocada de los dos lados, que debe salir en ambos mazos con GUID
propio— no se pudo montar porque no apareció un término compartido a mano.

### Paso 18 — el Import manual sigue creando historias: **PASA**

Import desde el top bar con título `SmokeImport`: creó
`files/importadas/SmokeImport.json`, **no** una nota — `files/recortes/` quedó igual
y `SmokeImport` no aparece en la pestaña Notes.

### Limpieza posterior

Los artefactos de esta corrida se borraron del dispositivo: la historia
`SmokeImport`, las cuatro notas generadas por las capturas de prueba y la palabra
`私` de `palabras_tocadas` (editando `databases/progreso.db` con la app parada).
Verificado después: `0 words · 0 tagged kanji · 10 stories · 1 scan words`.

```
cd app && ./gradlew installDebug
```

1. **Biblioteca.** Esperado: dos pestañas, `Stories` y `Notes`. El top bar
   tiene tres acciones (`Import` `Export` `About`) — con `Scan` mudado a
   adentro de Notes, entran holgadas en vertical (el smoke anterior tenía
   cuatro ahí y ya pedía vigilar esto).
2. **Pestaña Notes vacía** (antes de la primera captura). Esperado: mensaje
   explicativo y un FAB `Scan` abajo a la derecha, por encima de la barra de
   gestos (no tapado por ella).
2b. **Layout de la pestaña Notes.** Esperado: el contenido de Notes arranca
   pegado a las pestañas — **no** hay una franja en blanco del alto de la
   status bar entre la fila `Stories`/`Notes` y lo de abajo, y **no** hay un
   segundo título `Notes` debajo de la pestaña. Con notas en la lista, la
   primera card queda a un margen normal de las pestañas y la última no queda
   tapada por el FAB al scrollear hasta el fondo. Es el chequeo del doble
   Scaffold: hay que buscarlo a propósito, de reojo no se nota.
3. **Capturar texto japonés desde otra app** (burbuja → arrastrar selección →
   `Capture`). Esperado: se abre directamente la pantalla **Note**, nunca
   `Import`.
4. La nota muestra el texto con furigana. Tap en una palabra → `PalabraSheet`
   con definición.
5. La miniatura de la imagen aparece **colapsada** por defecto. Tap para
   expandirla → la captura original.
6. **Rotar con la imagen expandida.** Esperado: sigue expandida; no se
   colapsa sola ni se pierde.
7. `Edit` → corregir el texto a mano → `Save`. Esperado: la furigana se
   regenera sobre el texto nuevo.
8. **Rotar mientras se está editando** (texto ya modificado, antes de tocar
   `Save`). Esperado: el borrador editado sigue ahí después de rotar — no
   vuelve al texto original ni la pantalla sale del modo edición.
9. Volver a Notes. Esperado: la nota aparece en la lista con sus primeros
   caracteres y la fecha relativa.
10. **Segunda captura en seguida** (volver a la otra app, capturar otro
    texto sin haber cerrado nada de la nota anterior). **Lo primero, antes de
    tocar NADA:** la pantalla Note que se abre sola tiene que mostrar el texto
    **NUEVO**, no el de la nota anterior. Es el paso que importa: el navigate
    va a `recorte/{id}` con `launchSingleTop = true` **desde** un
    `recorte/{otroId}` — misma ruta, argumento distinto, que es la trampa
    clásica de Navigation Compose (la entrada se reusa y el ViewModel viejo
    sobrevive). Si muestra el texto viejo, es bug y hay que reportarlo; abrir
    la nota desde la lista después lo taparía, porque eso crea una entrada
    nueva.
10b. Recién ahora, volver a Notes. Esperado: dos notas distintas, la más nueva
    arriba; abrir cada una y confirmar que ninguna muestra la miniatura de
    imagen de la otra.
11. `Remove image` → confirmar en el diálogo. Esperado: la miniatura
    desaparece, el texto queda igual.
12. **App bar de Note en modo lectura** (`Edit` `Remove image` `Close`) en
    vertical. Esperado: los tres entran sin cortarse ni superponerse.
    *Conocido, no reportar:* en modo edición `Close` desaparece (queda solo
    `Save`/`Cancel`) y el botón/gesto Atrás del sistema sale de la pantalla
    sin avisar de cambios sin guardar.
13. Confirmar ese camino conocido a propósito: entrar a `Edit`, cambiar el
    texto, y salir con el back del sistema (no con `Cancel`). Esperado: sale
    sin preguntar nada y el cambio se pierde — es el comportamiento aceptado
    del paso 12, no un bug.
13b. **Cerrar y reabrir la nota** (`Close` → volver a abrirla desde la lista)
    después del `Edit` del paso 7 y del `Remove image` del paso 11. Esperado:
    el texto editado sigue editado y la imagen sigue sin estar. El único bug
    real que apareció en esta rama fue exactamente un persistir-y-reabrir
    (`tieneImagen` derivado del disco), así que mirar la pantalla sin salir de
    ella no alcanza como verificación.
14. **Long-press en la lista de Notes → borrar** una nota con imagen.
    Esperado: desaparece de la lista.
15. Verificar el borrado en disco:
    `adb shell run-as com.tatoh.dokushorenshu ls files/recortes/`. Esperado:
    ni el `<id>.json` **ni** el `<id>.jpg` de esa nota siguen ahí — los dos
    archivos, no alcanza con que falte uno.
16. **Espacio en disco:** tras ~10 capturas variadas,
    `adb shell run-as com.tatoh.dokushorenshu du -sh files/recortes/`.
    Esperado: del orden de unos pocos MB. Bastante más (decenas de MB) indica
    que se está guardando PNG en vez de JPEG calidad 90.
17. `Export`. **Son cuatro botones distintos, cada uno escribe su propio
    `.apkg`** — no hay un export único que genere todos. Exportar `Scans` y
    después, por separado, `Words`. Abrir `Dokusho — Scans` (AnkiDroid o el
    `.apkg`) y confirmar que solo trae vocabulario tocado en **notas**, nunca
    el tocado en historias; abrir `Dokusho — Words` y confirmar lo inverso.
    Comparar los dos entre sí: un término tocado en los dos lados aparece en
    ambos mazos, y eso está bien (GUID propio `scan:<termino>`), lo que no
    puede pasar es que `Scans` traiga vocabulario de historias.
18. **Import manual** (`Import` en el top bar de Biblioteca) sigue
    funcionando igual que antes. Esperado: crea una **historia** en la
    pestaña Stories, nunca una nota en Notes.
