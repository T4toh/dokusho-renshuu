# Estado del proyecto

> Contexto portable entre máquinas/sesiones. Actualizar al cerrar cada plan.
> Spec: `docs/superpowers/specs/2026-07-06-dokusho-renshuu-design.md`

## Dónde estamos (2026-07-09)

| Plan | Subsistema                                       | Estado                                                                                                               |
| ---- | ------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------- |
| 1    | `diccionario/` — parser → diccionario.db         | ✅ Completo (PR #1 mergeado, release [db-v1](https://github.com/T4toh/dokusho-renshuu/releases/tag/db-v1) publicado) |
| 2    | `historias/` — pipeline Aozora → JSON + catálogo | ✅ Completo ([PR #2](https://github.com/T4toh/dokusho-renshuu/pull/2) mergeado)                                                                                  |
| 3    | `app/` — lector Android (Kotlin + Compose)       | ✅ Completo ([PR #3](https://github.com/T4toh/dokusho-renshuu/pull/3)) |
| 3.5  | pulido + repaso básico (db-v2, catálogo v2, app) | ✅ Completo ([PR #4](https://github.com/T4toh/dokusho-renshuu/pull/4)) |
| 4    | `app/` — mazos .apkg + import de texto           | ⏳ Siguiente. Plan a escribir (writing-plans)                                                                         |

## Datos operativos

- **Release db vigente**: `db-v2` = `diccionario-v2.db` (73.3 MB, metadata version=2, glosas limpias: parser Jitendex descarta 23 marcadores de structured content; MAX_ORACIONES_POR_KANJI=30). db-v1 queda obsoleto.
- **Fuentes** (URLs vigentes en `diccionario/README.md`): Jitendex ya NO distribuye por GitHub release assets; Tatoeba discontinuó el export directo de pares → `diccionario/fuentes_tatoeba.py` los arma desde exports por-idioma.
- **Contrato para la app (Plan 3)**: `oracion_palabra` solo indexa términos de 2-6 chars; palabras de 1 kanji → fallback a `oracion_kanji`. Listas en el db = JSON arrays (`ensure_ascii=False`). Versión de esquema en tabla `metadata`.
- **Catálogo**: schema v2 (`titulo_lectura`, `titulo_en` nullable, `kanjis_unicos`, `oraciones`; sin encabezados de sección; urashima_taro ahora `media`). La app exige version==2. URL raw `https://raw.githubusercontent.com/T4toh/dokusho-renshuu/main/catalogo/catalogo.json`.
- **Entorno**: builds JVM (gradle/Android Studio, Planes 3-4) van en la PC secundaria — la principal tiene un bug de CPU que cuelga con Java. Python (Plan 2) anda en cualquiera.
- **Contrato furigana**: `[inicio, fin, lectura]` con fin exclusivo sobre el texto de la oración; diálogo `「…」` = 1 oración (portar igual en Kotlin, Plan 3).
- `historias/src/jlpt.py` es generado (regenerar con `genera_jlpt.py` solo si cambia KANJIDIC2).
- **App (Plan 3)**: `app/` compila con JDK 17+ (probado JDK 21) + SDK 36 (PC secundaria); assets generados por gradle tasks (`descargarDiccionario` baja el db del release con escritura atómica tmp→rename; `copiarHistorias` empaqueta `catalogo/`). AGP 9.2 usa Kotlin built-in (sin plugin kotlin-android ni kotlinOptions). UI en inglés; tabla Room `kanjis_tocados` (kanji, dificultad nullable easy/medium/hard, timestamp — migración 1→2 no destructiva) — insumo Plan 4 junto con `palabras_tocadas`; tests con maxHeapSize 2g (OOM Kuromoji).
- **Para Plan 4**: portar segmentador de `historias/src/segmentador.py` CON la regla de fusión de spans; formato `.apkg` = zip + SQLite (referencia genanki); IDs estables por kanji (guid Anki).

## Backlog diferido (review final Plan 1 — no bloqueante)

- `jitendex.py`: heurística "item lista plana = redirect" — si un release futuro de Jitendex usa listas para otra cosa, glosas con `→` espurio. El check de vacíos de verify_db no lo detectaría.
- `verify_db.py` CLI: correr sobre ruta inexistente crea un db vacío antes de fallar (falta guard `os.path.exists`).
- `tatoeba.py` / `verify_db.py`: `int()` sin guarda — línea con id no numérico aborta el build con ValueError sin contexto.
- `fuentes_tatoeba.py`: carga `eng_sentences.tsv` entero en memoria (OK como one-shot; si molesta, leer links primero y cargar solo ids necesarios).
- `jitendex.py`: sin test multi-archivo `term_bank_*`; li-anidado-en-li se aplana en una glosa.
- Recomendación pendiente: guardar procedencia de fuentes (fechas) en tabla `metadata` del db.

## Backlog diferido (review final Plan 2 — no bloqueante)

- `segmentador`: margen navaja de urashima_taro (pct 0.449 vs umbral facil 0.45) — retoque del texto o del set JLPT lo flipea; considerar test de borde de umbral.
- `verify_catalogo`: no valida `catalogo['version']`, ids duplicados ni archivos huérfanos en `catalogo/historias/`.
- `japones`: `_EXTRAS_BASE` sin tests de 〆ヵヶ; helpers sin guarda multi-char (igual que diccionario/).
- `aozora`: sin tests de colofón ASCII `底本:` ni delimitadores-sin-colofón; `lstrip('　')` redundante tras `strip()`.
- `pipeline`: cp932 puede decodificar UTF-8 como mojibake sin lanzar (mitigado por sanity check manual); rama fallback utf-8 sin test.
- `emisor`: sin tests de multi-historia/ids duplicados en `emitir`.
- `genera_jlpt`: fixture sin entrada `jlpt=3` (rama N4 sin test directo).

## Backlog diferido (reviews Plan 3 — no bloqueante)

- `BuscadorPalabras`: regla 2-6 chars cuenta UTF-16 units, no code points — diverge solo con kanji fuera del BMP (catálogo actual validado BMP-only; riesgo conocido #4 del plan).
- `lecturaDelToken`: tests no cubren overlap parcial ni borde fin==inicio; guard de `mover()` con oraciones vacías sin test.
- `tocarPalabra`: sin guard de doble-tap (race UX last-wins, benigna).
- Rotación re-dispara `LaunchedEffect(Unit)` → refetch de catálogo/historia (flash de Cargando; idempotente).
- `DiccionarioSqlite` no expone `close()`; test deja ruido CloseGuard en stderr.
- `HistoriasRepo`: doc de ClienteHttpReal dice IOException pero lanza IllegalArgumentException; `.tmp` huérfano posible si el proceso muere entre write y rename (filtrado por extensión, no rompe).
- Gradle: tasks de assets sin group/description; `copiarHistorias` copia cualquier extensión.
- `App`: warm-up thread de lazies sin `runCatching` — si `DiccionarioSqlite.abrir` lanza (disco lleno) mata el proceso al arranque en vez de en la primera navegación (lazy SYNCHRONIZED no cachea fallas; con runCatching el path del VM reintentaría en contexto).

## Backlog diferido (Plan 3.5 — no bloqueante)

- db-v2: marcadores `〔… only〕` de restricción de formas siguen filtrando a las glosas (3.645 entradas, 904 como primera glosa) — el span no tiene `data.content` (solo `title="valid only for these forms and/or readings"` / hijos `form-special`), invisible al blacklist actual; requiere rebuild db-v2.1 con descarte por `title`.
- Status bar con íconos claros sobre fondo claro en light mode (falta windowLightStatusBar en el theme); ídem barra de gestos/navigation bar — debería adaptarse al tema (feedback 2026-07-09).
- Portada muestra "0% read" con progreso <1% (truncado a int; el botón Continue/Start ya se arregló en 7ab6b7d).
- Progreso guardado se corre ~1 posición cuando se regenera el catálogo (índices sobre JSON nuevo; one-time, benigno).
- jitendex: xref/sense-note descartados enteros — "See also" se pierde (aceptado; revisar si se quiere conservar con label).
- verify_db no detecta un sentido individual borrado en palabra multi-sentido.
- MigrationTestHelper no usado (exportSchema=false); ProgresoDaoFake overridea registrarAperturaKanji (primitivas dead-code en fake).
- Review section: kanjisPorDificultad consultado 2x por dificultad.
- Cards de biblioteca muestran dificultad cruda `facil/media/dificil` en UI inglesa (mapear a Easy/Medium/Hard); lookup por lectura sin guard de kana (palabra kanji fuera del db puede resolver a homófono); DIFICULTADES duplicado en VM y Screen.

## Feedback pendiente (2026-07-09 — candidatos a Plan 3.6/4)

- Scroll con el dedo en el lector (arriba/abajo) además de los botones Previous/Next.
- Toggle de lectura para katakana: mostrar pronunciación en hiragana sobre tokens katakana — NO requiere otro diccionario (Kuromoji ya da lecturas y `katakanaAHiragana` existe en Tokenizador); toggle tipo furigana.
- Detalle de kanji adaptable: en tablet sobra ancho (layout 2 columnas: kanji+chips | secciones); en teléfono reducir scroll.

## Proceso de trabajo usado

Brainstorming → spec → plan por subsistema (`docs/superpowers/plans/`) → ejecución subagent-driven (implementer + reviewer por tarea, review final de branch) → PR. Repetir por plan.
