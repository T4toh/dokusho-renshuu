# Plan 4a.1: mazos Anki por historia — diseño

> Origen: feedback del usuario post plan 4a (PR #7): "está bueno para poder pegarles una mirada antes de empezar con la historia". Decisiones cerradas en brainstorming 2026-07-10.
> Base: infraestructura Anki de 4a (`dominio/anki/`: ModeloNotas, EscritorApkg, ArmadorMazos) ya mergeándose.

## Problema

Antes de leer una historia, el usuario quiere repasar todos sus kanjis en Anki. Los mazos de 4a solo cubren lo ya tocado/taggeado; falta un mazo de pre-lectura por historia con TODOS los kanjis únicos de esa historia.

## Decisiones (aprobadas)

- **Empaquetado**: UN botón "Export Stories deck" → UN `dokusho-stories.apkg` con subdeck por historia usando la sintaxis `::` de Anki (`Dokusho — Stories::桃太郎`, etc.). Un solo share/import; se estudia por subdeck o todo junto. Descartado: un .apkg por historia (más taps, escala mal cuando exista import de texto propio).
- **Oraciones**: SOLO de esa historia (el punto es prepararse para ese texto), cap 5, rotación JS idéntica a 4a. SIN relleno Tatoeba. Kanji que aparece 1 vez → 1 oración.
- **Branch**: plan chico propio (4a.1) desde main DESPUÉS del merge de PR #7. No se apila en #7.

## Estructura de cartas

Mismo modelo `NotaKanji` de 4a (mismo model ID, mismos templates/CSS/JS): `Kanji` → `OnYomi` + `KunYomi` + `Significados` + `[Dificultad]` + oración rotativa con ruby.

- `Dificultad` = tag del usuario si ese kanji está taggeado (easy/medium/hard), vacío si no (el template ya omite el badge con campo vacío).
- **GUID por historia+kanji**: `story:<idHistoria>:<kanji>` — NUNCA `kanji:<kanji>`. Si compartiera GUID con el mazo "Dokusho — Kanji" o con otra historia, el import de Anki pisaría la nota existente (match global por GUID) y la carta no aparecería en el subdeck nuevo. Mismo kanji en 2 historias = 2 notas, cada una con las oraciones de SU historia. Re-export del mismo archivo actualiza sin duplicar (invariante de 4a intacto).
- **Orden de cartas = orden de primera aparición del kanji en la historia** (due incremental en ese orden): se estudia en el orden de lectura.

## Datos y arquitectura

- **Set de kanjis por historia**: extraído del texto de las oraciones (HistoriasRepo ya carga las historias locales; check de rango CJK unificado — reutilizar el helper de detección de kanji existente en la app si lo hay, si no uno propio en el armador). `kanjis_unicos` del catálogo es solo un count, no la lista.
- **Deck IDs**: constantes determinísticas derivadas del id de historia (hash estable, mismo estilo que los IDs fijos de 4a). Deck name = `Dokusho — Stories::<titulo>` (título japonés del catálogo).
- `ArmadorMazos`: método nuevo `armarHistorias()` que devuelve, por historia local, (deckId, nombre, notas ordenadas por primera aparición) + count de kanjis omitidos. Enriquece contra `Diccionario` (lecturas/significados de kanji); kanji sin entrada en el db → se omite y se cuenta ("skipped"), como en 4a.
- `EscritorApkg`: hoy escribe los 2 mazos fijos + Default. Generalizar para aceptar N (deckId, nombre, notas) — los mazos de 4a pasan por el mismo camino (sin cambio de comportamiento observable: mismos IDs/nombres/GUIDs; los tests existentes lo verifican).
- `ExportViewModel/Screen`: tercer botón "Export Stories deck" bajo los dos actuales; mismo ciclo Idle/Generando/Listo/Error; archivo estable `dokusho-stories.apkg` en cache/export; resumen "Exported N stories (M kanji)" + "(K skipped)" si aplica; share idéntico.

## Manejo de errores

Mismos patrones de 4a: export nunca crashea (catch → Error + log), archivo parcial se borra en fallo/cancelación, guard de doble tap, botón deshabilitado si no hay historias locales (hint). Historia individual ilegible → se salta esa historia y se reporta en el resumen, no aborta el export.

## Testing

- Armador: set de kanjis correcto contra fixture real de momotaro (count esperado = kanjis únicos reales del fixture); orden = primera aparición; oraciones solo de esa historia con ruby; cap 5; kanji taggeado hereda dificultad; kanji fuera del db omitido y contado; GUIDs `story:momotaro:洗` estables entre corridas y disjuntos del mazo Kanji.
- Escritor: .apkg con N decks — col.decks contiene Default + N entradas con nombres `::`; notas/cards asignadas al deck correcto; regresión de los mazos 4a byte-equivalente en lo observable (tests existentes verdes sin tocar asserts).
- VM: estados + resumen con counts; suite completa verde (base 127).
- Gate en dispositivo: import real en AnkiDroid → subdecks colapsables bajo "Dokusho — Stories", carta con oración de la historia correcta, orden de estudio = orden de aparición, re-import actualiza sin duplicar, y los mazos de 4a re-exportados siguen actualizando los suyos (GUIDs disjuntos).

## Fuera de scope

Selección de qué historias exportar (van todas las locales); mazos de PALABRAS por historia; audio; import de texto (4b).
