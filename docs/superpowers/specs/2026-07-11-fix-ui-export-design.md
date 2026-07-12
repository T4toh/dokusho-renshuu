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

## Fuera de alcance

- Toggle de checkboxes durante `Generando` (minor conocido, benigno — backlog).
- Separador colgante en la tarjeta de *biblioteca* (mismo síntoma, otra pantalla — backlog).
- Cambios de dominio más allá de los 2 campos de `HistoriaResumen`.
