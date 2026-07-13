# Fix UI Export: scroll de la lista + detalles por obra (diseño)

> Brainstorming 2026-07-11. Resuelve la sección "Backlog UI Export" de `docs/ESTADO.md`:
> el BUG de la lista de checkboxes que no scrollea (10 historias desbordan, ticks
> superpuestos — visto en tablet) y las filas peladas (solo título).

## Objetivo

Que la sección Stories de `ExportScreen` escale a N historias: lista scrolleable con
counts/botones fijos arriba y el bloque de resultado ("Exported…" + Share) siempre
visible abajo, y cada fila con contexto (autor · dificultad).

## Decisiones (validadas con el usuario)

1. **Layout**: lista scrolleable, resto fijo. Counts + 3 botones de export arriba
   (fijos); lista de historias en el medio con scroll propio; bloque Listo/Share
   abajo, visible sin scrollear.
2. **Detalles por fila**: `autor · dificultad` en línea secundaria (estilo tarjeta
   de biblioteca). Sin conteo de kanjis.

## Cambios

### 1. `dominio/anki/ArmadorMazos.kt` — datos

`HistoriaResumen` gana dos campos:

```kotlin
data class HistoriaResumen(val id: String, val titulo: String, val autor: String, val dificultad: String)
```

- `dificultad` viaja en schema (`facil|media|dificil`), igual que en `Historia`.
- `resumenHistorias()` los llena desde la `Historia` ya parseada (cero I/O extra).
- Call sites que construyen `HistoriaResumen` (tests de armador y de ExportViewModel)
  se actualizan.

### 2. `ui/export/ExportScreen.kt` — layout y fila

- Column raíz sigue fija (sin scroll): counts + botones como hoy.
- La lista pasa de `Column { for (...) }` a `LazyColumn(Modifier.weight(1f, fill = false))`:
  - scrollea sola cuando el contenido excede el espacio del medio;
  - `fill = false`: con pocas historias no se estira ni empuja el bloque Listo al fondo;
  - elimina el overflow que superponía los ticks.
- El bloque `Listo` (resumen + botón Share) queda después de la lista, dentro de la
  Column fija → siempre visible, también recién exportado con lista larga.
- Fila nueva: `Row(Checkbox, Column(titulo, detalle))`:
  - título: `bodyMedium` (como hoy);
  - detalle: `bodySmall` + `onSurfaceVariant`, texto = partes no vacías unidas con
    `" · "` — autor puede ser vacío (importadas) → queda solo la dificultad, nunca
    separador colgante;
  - dificultad mostrada en UI en inglés (`Easy|Medium|Hard`), con el mismo mapeo
    schema→UI que usa la tarjeta de biblioteca (reusar el helper existente si es
    accesible; si es privado de BibliotecaScreen, replicar el patrón — decidir en
    implementación con el código a la vista, sin acoplar screens entre sí).

## Testing

- `ArmadorMazosTest`: `resumenHistorias()` devuelve autor y dificultad de la historia.
- `ExportViewModelTest`: compila con el data class ampliado (ajuste mecánico de
  constructores en fakes/asserts si los hay).
- Screens sin unit test (convención del repo). Smoke en tablet:
  1. Lista de 10 scrollea; ticks sin superponerse; botones y counts fijos durante el scroll.
  2. Cada fila muestra `autor · Difficulty` (y solo `Difficulty` si se prueba con una importada).
  3. Exportar Stories → "Exported…" + Share visibles sin scrollear, con la lista larga.
- Suite completa de la app verde antes del PR.

## Addendum 2026-07-12: grid adaptivo (feedback post-smoke, mismo PR #11)

En tablet landscape la lista de una columna deja ~70% del ancho vacío. Validado con
el usuario: la lista pasa a grid adaptivo, sin tocar el resto del layout.

- `LazyColumn` → `LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 300.dp))`,
  mismo `Modifier.weight(1f, fill = false)`, mismo `items(historiasStories, key = { it.id })`,
  fila interna idéntica (Checkbox + título + detalle).
- `300.dp` = mismo valor que la grilla de la biblioteca (`BibliotecaScreen.kt:119`) —
  consistencia visual y de código. Tablet: ~2 columnas portrait, ~3 landscape;
  teléfono angosto: 1 columna (degrada solo, sin ramas por tamaño de pantalla).
- Header y bloque Listo/Share sin cambios; el grid scrollea en el espacio del medio.
- Testing: sin unit tests nuevos (screen); compile + suite completa + smoke tablet
  en ambas orientaciones (columnas esperadas, scroll con header fijo, Exported+Share
  visibles sin scrollear).

## Addendum 2026-07-12 (2): botones de export en fila cuando hay ancho

En landscape los 3 botones apilados desaprovechan el ancho. Validado con el usuario:
FlowRow auto-wrap, sin ramas por tamaño de pantalla.

- El `Column(Modifier.width(IntrinsicSize.Max))` que envuelve los 3 `BotonExport`
  pasa a `FlowRow(horizontalArrangement/verticalArrangement = spacedBy(12.dp))`;
  los `Spacer(12.dp)` intermedios se van (los reemplaza el arrangement).
- Cada `BotonExport` se envuelve en `Modifier.widthIn(min = 160.dp).width(IntrinsicSize.Max)`.
  El `widthIn` externo iguala los 3 botones (feedback del usuario en POCO: anchos
  por texto se veían desparejos): 160.dp cubre cómodo el texto más largo actual
  (~150.dp); si un texto futuro lo excede, ese botón crece solo. Con 160.dp la
  tablet mantiene fila de 3 en ambas orientaciones; en teléfono portrait (~443.dp)
  pueden entrar 2 por línea (wrap 2+1, uniforme). `BotonExport` gana parámetro
  `modifier` para recibirlo.
- Con ancho (tablet, teléfono landscape): fila de 3; angosto: bajan de línea solos.
- `FlowRow` puede requerir `@OptIn(ExperimentalLayoutApi::class)` según versión de
  foundation — agregar solo si el compile lo pide.
- Testing: compile + suite + smoke tablet (fila en ambas orientaciones o wrap
  correcto) y POCO portrait (apilados, sin regresión); POCO sin taps adb —
  navega el usuario, capturas por screencap.

## Fuera de alcance

- Toggle de checkboxes durante `Generando` (minor conocido, benigno — backlog).
- Separador colgante en la tarjeta de *biblioteca* (mismo síntoma, otra pantalla — backlog).
- Cambios de dominio más allá de los 2 campos de `HistoriaResumen`.
