# Plan 4a: mazos Anki (.apkg) — diseño

> Origen: roadmap original (Plan 4) partido en 4a (mazos) y 4b (import de texto, ciclo futuro). Decisiones cerradas en brainstorming 2026-07-09.
> Insumos ya acumulados por la app: `palabras_tocadas` (Room, desde Plan 3) y `kanjis_tocados` con tags easy/medium/hard (Plan 3.5).

## Problema

El usuario toca palabras y taggea kanjis mientras lee, pero ese registro no sirve para estudiar. Quiere exportar mazos Anki (.apkg) para AnkiDroid, con cartas estilo Kaishi 1.5k (sin romaji, furigana visible) y oraciones de ejemplo reales de las historias — que roten en cada review.

## Decisiones (aprobadas)

- **Plan 4 partido**: 4a = mazos (este spec); 4b = import de texto propio (ciclo futuro, spec propio).
- **Generación**: writer .apkg propio en Kotlin (zip + `collection.anki2` SQLite schema Anki 2.1 legacy + archivo `media` vacío). Sin dependencias nuevas (SQLite nativo de Android); genanki (Python) como referencia de schema. Descartados: API instant-add de AnkiDroid (ata al provider, no genera archivo), TSV (pierde deck/templates).
- **Dos mazos**: "Dokusho — Words" (todas las `palabras_tocadas`, enriquecidas contra el diccionario offline) y "Dokusho — Kanji" (solo kanjis **taggeados**; el tag va en la carta; tocados-sin-tag no entran — ruido de consulta).
- **Ejemplos**: oraciones de las **historias locales** que contienen la palabra/kanji (escaneo offline de los JSON empaquetados/descargados), con **furigana embebida en formato Anki** (`漢字[かんじ]`, generada desde los spans reales fin-exclusivo); relleno con Tatoeba (db) hasta el cap. **Cap: 5 oraciones por nota** (historias primero, Tatoeba después).
- **Rotación por review**: campos `Oracion1..Oracion5` + JavaScript en el card template. Mecanismo concreto: el template renderiza `Oracion1` en un `<div id="oracion">` visible por defecto; los 5 campos van también en `<script type="text/plain">` (o divs ocultos) y un JS al final elige una no-vacía al azar y reemplaza el contenido del div. Sin JS → queda `Oracion1` (fallback garantizado).
- **Estilo de carta**: referencia Kaishi — sin romaji en ningún campo; palabra grande, lectura, significados, oración con ruby. Formato de ruby: **HTML `<ruby>漢字<rt>かんじ</rt></ruby>` generado directamente desde los spans** (no el filtro `{{furigana:}}` de Anki — el HTML funciona en todos los clientes y no depende del parsing de corchetes).
- **IDs estables** (re-export actualiza, no duplica): GUID de nota = hash determinístico del término (words) / del kanji (kanji deck), estilo genanki; model IDs y deck IDs constantes fijas en el código.
- **Export UX**: botón "Export" en la barra de Biblioteca (junto a About) → pantalla liviana: counts ("N words · M tagged kanji"), un botón por mazo → genera en `cacheDir` (ioDispatcher, estado de progreso) → **share intent** con FileProvider (a AnkiDroid, Drive, etc.). Sin permisos de storage.

## Estructura de cartas

**Nota Words** — campos: `Palabra`, `Lectura`, `Significados`, `Tag` (vacío), `Oracion1..5` (cada una: oración con furigana Anki + salto + traducción EN si la hay — Tatoeba sí, historias no traducen).
Carta (frente → dorso): `Palabra` → `Lectura` + `Significados` + oración rotativa.

**Nota Kanji** — campos: `Kanji`, `OnYomi`, `KunYomi`, `Significados`, `Dificultad`, `Oracion1..5`.
Carta: `Kanji` → lecturas + significados + `[dificultad]` + oración rotativa.

Palabra sin definición en el db → carta con lectura sola (nunca aborta el export). Kanji taggeado que ya no está en el db → se omite con aviso en el resultado ("exported N, skipped M").

## Arquitectura

- `dominio/anki/EscritorApkg.kt` — genera el zip+SQLite desde listas de notas ya armadas. Puro respecto a datos (recibe modelos), Robolectric para SQLite. Sin conocimiento de Room/diccionario.
- `dominio/anki/ModeloNotas.kt` — model IDs/deck IDs/GUIDs, templates HTML+JS+CSS, formato furigana Anki.
- `dominio/anki/ArmadorMazos.kt` — junta los datos: lee palabras/kanjis tocados (DAO), enriquece con `Diccionario`, escanea historias locales (`HistoriasRepo`) buscando oraciones que contengan el término/kanji, arma las notas. Testeable con fakes existentes.
- `ui/export/` — `ExportScreen` + `ExportViewModel` (patrón establecido: ioDispatcher, estados). Botón en `BibliotecaScreen` top bar + ruta en NavHost.
- FileProvider en el manifest (`${applicationId}.fileprovider`, paths de cache).

## Manejo de errores

- Sin datos → botones deshabilitados con hint ("Read and tap words first").
- Fallo de generación → snackbar con mensaje, log, nunca crash; archivo parcial en cache se borra.
- Share cancelado → no-op (el archivo queda en cache, se pisa en el próximo export).

## Testing

- Writer: el .apkg generado se abre con sqlite3 (unzip + PRAGMA integrity_check + counts de notes/cards); GUIDs idénticos entre dos corridas; zip con `media` = `{}`.
- Armador: notas correctas con fakes (palabra con/sin definición, kanji sin tag excluido, cap de 5 oraciones, prioridad historias>Tatoeba, furigana Anki bien formada desde spans reales de momotaro fixture).
- VM: estados (idle/generando/listo/error), counts.
- Gate final en dispositivo: export real → compartir a AnkiDroid en el POCO → importa, cartas se ven estilo Kaishi, la oración rota entre reviews, re-export no duplica notas.

## Fuera de scope

Import de texto (4b), audio/media en cartas, scheduling/SRS propio, export automático, selección fina de qué palabras exportar (va todo lo tocado).
