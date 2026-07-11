# Plan 4b — Import de texto propio (diseño)

> Brainstorming 2026-07-11. Spec madre: `2026-07-06-dokusho-renshuu-design.md` (líneas 151-152, 173, 178-180, 207-208).

## Objetivo

El usuario pega texto japonés o abre un `.txt`, y la app lo convierte en una historia local indistinguible de las del catálogo: mismo JSON schema v2, con furigana generada por Kuromoji y persistida. Lector, toggle カナ, progreso y export Anki funcionan sin cambios. Se agrega además selección de historias en el export del mazo "Dokusho — Stories".

## Decisiones de diseño (validadas con el usuario)

1. **Furigana persistida al importar.** Kuromoji corre una sola vez durante el import y las ternas quedan guardadas en el JSON. El spec madre decía "on-demand", pero persistir evita tocar `LectorViewModel`/`TextoConFurigana` y mantiene un único camino de datos. Costo: unos segundos al importar textos largos (aceptado).
2. **Entrada doble**: campo de texto grande (pegar del clipboard) + botón "Open .txt" vía SAF (`ACTION_OPEN_DOCUMENT`). Decodificación UTF-8 únicamente; si el archivo no decodifica, error claro (sin fallback cp932 en v1).
3. **Metadata manual mínima**: título obligatorio, dificultad elegida por el usuario (easy/medium/hard, default medium), autor opcional. No se porta el clasificador JLPT.
4. **Borrado solo de importadas**, con diálogo de confirmación. El progreso y los kanjis/palabras tocados NO se borran (histórico de estudio).
5. **Anki**: las importadas entran al mazo "Dokusho — Stories" como cualquier historia (GUID `story:<id>:<kanji>` ya distingue por id). Nuevo: la sección Stories de `ExportScreen` lista las historias con checkboxes (todas marcadas por default) y el .apkg lleva solo los subdecks elegidos.
6. **Persistencia en `filesDir/importadas/<id>.json`**, directorio hermano de `historias/` (descargas). "Es importada" = el archivo vive en ese directorio; eso habilita el borrado y el badge en la biblioteca. Sin Room, sin migraciones.

## Flujo

```
Biblioteca ── botón Import ──▶ ImportScreen
  campo texto (pegar)  |  Open .txt (SAF, UTF-8)
  título* · dificultad (default medium) · autor?
        │ Import
        ▼
  Heurística % CJK ── bajo ──▶ diálogo "doesn't look Japanese" (continuar/cancelar)
        │ ok
        ▼  (background)
  párrafos = líneas no vacías
  → SegmentadorTexto (port Python, con fusión de residuos)
  → GeneradorFurigana (Kuromoji, trim de okurigana)
  → Historia JSON schema v2
  → escritura atómica tmp→rename en filesDir/importadas/<id>.json
        │
        ▼
  Biblioteca: tarjeta nueva con badge "imported" (+ acción borrar)
```

## Componentes

Base: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/`.

### 1. `dominio/SegmentadorTexto.kt` (nuevo)

Port de `historias/src/segmentador.py` — deuda del Plan 3 que se paga acá:

- `segmentar(texto): List<IntRange-like (inicio, fin exclusivo)>` — corta en `。！？` solo con profundidad 0 de `「『（`/`」』）` (diálogo `「…。…」` = 1 oración, contrato existente).
- Regla de fusión: un span "residuo" (solo puntuación/espacios, `_es_residuo`) se fusiona con el span anterior (cubre `」` residual, `？` tras `！`).
- NO se portan `es_encabezado_seccion` ni la reindexación de furigana de `segmentar_parrafo` (específicos de Aozora; acá no hay furigana pre-alineada).

### 2. `dominio/GeneradorFurigana.kt` (nuevo)

Usa el `Tokenizador` existente (`dominio/Tokenizador.kt`, Kuromoji IPADIC, lectura ya convertida a hiragana):

- Por cada token cuya superficie contiene ≥1 kanji: emitir terna `[inicio, fin, lectura]` (índices relativos a la oración, fin exclusivo — contrato v2).
- **Trim de okurigana**: recortar el kana coincidente entre superficie y lectura por ambos extremos para que el ruby caiga solo sobre los kanji (ej. `走った` con lectura `はしった` → terna sobre `走` con `はし`). Si tras el trim la lectura queda vacía o la coincidencia es ambigua, emitir la terna sobre el token completo (degradación segura).
- Tokens sin kanji (kana, puntuación, latín) no emiten terna. Spans disjuntos garantizados: los tokens de Kuromoji no se solapan.
- Si el token no tiene lectura conocida (`*`), no se emite terna.

### 3. Serializador Historia→JSON (en `datos/ModelosHistoria.kt`)

Inverso de `ParserHistoria.parsear`: emite el schema v2 exacto (`id, titulo, autor, fuente, licencia, dificultad, version=2, parrafos[].oraciones[].{texto, furigana[[i,f,lectura]], traduccion:null}`). Contrato verificado con test round-trip `parsear(serializar(h)) == h` sobre una historia real de assets.

Campos para importadas: `fuente = "import"`, `licencia = "texto del usuario"`, `autor` vacío si no se dio.

### 4. `datos/HistoriasRepo.kt` (modificar)

- `dirImportadas = filesDir/importadas` (crear on-demand, igual patrón que `dirDescargas`).
- `importar(titulo, autor, dificultad, texto): Historia` — corre el pipeline (segmentar + furigana + serializar) y escribe atómico tmp→rename.
- `borrarImportada(id)` — borra el JSON; no toca Room.
- `historiasLocales()` fusiona el tercer origen. Prioridad del dedup en `LinkedHashMap`: descargada pisa asset (como hoy); importada nunca pisa una del catálogo — ids generados como slug del título con sufijo `-2`/`-3` si colisiona con cualquier id existente (assets + descargas + importadas).
- Entrada de biblioteca derivada del JSON importado: `titulo_lectura = null`, `titulo_en = null`, `kanjis_unicos` y `oraciones` contados del contenido, `tamaño` por caracteres. Marca `importada = true` para la UI (badge + borrar).

### 5. UI import (`ui/importar/`, nuevo)

- `ImportScreen.kt` + `ImportViewModel.kt`; ruta `importar` en `MainActivity`; callback `onImportar` en `BibliotecaScreen` (botón junto a Export).
- Botón Import deshabilitado si texto o título vacíos. Import corre en background con spinner (patrón del Export 4a).
- Heurística CJK: % de code points en rangos hiragana/katakana/CJK sobre los no-espacios; si < 50%, diálogo de aviso con continuar/cancelar (spec madre, manejo de errores).
- Borrar: ícono en la tarjeta importada → diálogo de confirmación → `borrarImportada` → refresh.
- UI en inglés (regla de la app).

### 6. Export con selección (`ui/export/`, modificar)

- Sección Stories de `ExportScreen`: lista de historias (catálogo + importadas) con checkbox, todas marcadas por default; botón export genera un solo .apkg con los subdecks elegidos.
- `ArmadorMazos` recibe el subset de ids (default: todas) — cambio de firma, no de lógica de mazos.

## Manejo de errores

| Caso | Comportamiento |
| --- | --- |
| Texto o título vacío | Botón Import deshabilitado |
| `.txt` no decodifica UTF-8 | Mensaje de error, no se carga |
| % CJK bajo | Diálogo de aviso, el usuario decide |
| Falla Kuromoji / IO al escribir | Snackbar de error; escritura atómica ⇒ no queda archivo a medias |
| Id colisiona | Sufijo `-2`/`-3` automático |

## Testing

- `SegmentadorTexto`: casos portados del test Python — corte en `。！？`, diálogo multi-oración = 1 span, fusión de `」` residual y `？` suelto, texto sin puntuación final.
- `GeneradorFurigana`: kanji puro, kanji+okurigana (trim), okurigana ambigua (fallback token completo), katakana/kana/latín (sin terna), token sin lectura.
- Serializador: round-trip con historia real de assets; furigana vacía; campos null.
- `HistoriasRepo`: importar y releer, borrar, dedup de id con sufijo, fusión de 3 orígenes.
- Heurística CJK: japonés puro, mixto, inglés puro, bordes del umbral.
- `ExportViewModel`: subset de historias respetado; default todas.
- Regla 4a vigente: `grep -cP '\x1f'` = 0 en fuentes (U+001F solo como escape Kotlin).

## Docs

- **Cómo importar historias**: sección de uso en `app/README.md` (pegar texto / abrir .txt, metadata, borrado, límites conocidos: UTF-8, furigana automática puede errar lecturas).
- **Backlog**: anotar en `docs/ESTADO.md` que hay que **agregar más historias base al catálogo** (pipeline Plan 2, no bloquea 4b).

## Fuera de alcance (v1)

- Fallback de encoding cp932/Shift-JIS.
- Edición de una historia importada (re-importar y borrar la vieja).
- Clasificación automática de dificultad (JLPT).
- Corrección manual de furigana errada.
