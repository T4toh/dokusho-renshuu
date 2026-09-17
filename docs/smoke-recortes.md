# Smoke de dispositivo — recortes/notas (Plan recortes-notas)

> **SIN EJECUTAR TODAVÍA (redactado 2026-09-17).** Se corre en un POCO
> 2412DPC0AG, HyperOS, **Android 16 (API 36)**, navegación por gestos — el
> mismo equipo del smoke de `docs/smoke-captura-ocr.md`. Requiere permiso de
> overlay y de notificaciones ya concedidos (si no, repetir los pasos 2-3 de
> ese documento antes de empezar acá).
>
> Nada de esto es testeable sin hardware — es la única verificación que la
> feature va a tener. Cada paso dice qué esperar para que una falla sea
> reconocible.

```
cd app && ./gradlew installDebug
```

1. **Biblioteca.** Esperado: dos pestañas, `Stories` y `Notes`. El top bar
   tiene tres acciones (`Import` `Export` `About`) — con `Scan` mudado a
   adentro de Notes, entran holgadas en vertical (el smoke anterior tenía
   cuatro ahí y ya pedía vigilar esto).
2. **Pestaña Notes vacía** (antes de la primera captura). Esperado: mensaje
   explicativo y botón `Scan`.
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
    texto sin haber cerrado nada de la nota anterior). Esperado: dos notas
    distintas en la lista, la más nueva arriba; abrir cada una y confirmar
    que ninguna muestra la miniatura de imagen de la otra.
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
17. `Export`. Esperado: entre los mazos generados están `Dokusho — Scans` y
    `Dokusho — Words`. Abrir `Dokusho — Scans` (AnkiDroid o el .apkg) y
    confirmar que solo trae vocabulario tocado en notas, nunca el tocado en
    historias; abrir `Dokusho — Words` y confirmar lo inverso.
18. **Import manual** (`Import` en el top bar de Biblioteca) sigue
    funcionando igual que antes. Esperado: crea una **historia** en la
    pestaña Stories, nunca una nota en Notes.
