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
| 3.6  | detalles de UI (barras, lector scroll libre, kanji 2col) | ✅ Completo (PR pendiente — actualizar con #N al abrir) |
| 3.7  | katakana-ruby + fix alineador de furigana        | ✅ Completo ([PR #6](https://github.com/T4toh/dokusho-renshuu/pull/6)) |
| 4a   | `app/` — mazos Anki (.apkg) con oraciones rotativas | ✅ Completo ([PR #7](https://github.com/T4toh/dokusho-renshuu/pull/7)) |
| 4a.1 | `app/` — mazos por historia (subdecks de pre-lectura) | ✅ Completo ([PR #8](https://github.com/T4toh/dokusho-renshuu/pull/8)) |
| 4b   | `app/` — import de texto propio                  | ✅ Completo ([PR #9](https://github.com/T4toh/dokusho-renshuu/pull/9))                                               |
| 4c   | catalogo/ — tanda 2 de historias (6 obras)       | ✅ Completo ([PR #10](https://github.com/T4toh/dokusho-renshuu/pull/10))                                             |
| fix  | app/ — UI export: grid adaptivo + autor · dificultad + botones en fila (FlowRow) | ✅ Completo ([PR #11](https://github.com/T4toh/dokusho-renshuu/pull/11))            |
| A    | app/ — backlog feedback de uso: update de historias + nº de oración + selección/Search web | ✅ Completo (PR pendiente — actualizar con #N al abrir; smoke en tablet pendiente) |

## Datos operativos

- **Release app vigente**: [v0.1.0-beta.2](https://github.com/T4toh/dokusho-renshuu/releases/tag/v0.1.0-beta.2) (prerelease, APK release minificado R8 firmado con debug key, 42.7 MB, main 26e4af6 post-PR #12 — catálogo con furigana completa en assets; smoke OK en tablet, instalada ahí con `install -r`). beta.1 queda obsoleta: muestra furigana vieja porque la app no re-descarga historias bundleadas. El usuario la usa en el día a día y junta feedback para la próxima tanda.
- **Release db vigente**: `db-v2` = `diccionario-v2.db` (73.3 MB, metadata version=2, glosas limpias: parser Jitendex descarta 23 marcadores de structured content; MAX_ORACIONES_POR_KANJI=30). db-v1 queda obsoleto.
- **Fuentes** (URLs vigentes en `diccionario/README.md`): Jitendex ya NO distribuye por GitHub release assets; Tatoeba discontinuó el export directo de pares → `diccionario/fuentes_tatoeba.py` los arma desde exports por-idioma.
- **Contrato para la app (Plan 3)**: `oracion_palabra` solo indexa términos de 2-6 chars; palabras de 1 kanji → fallback a `oracion_kanji`. Listas en el db = JSON arrays (`ensure_ascii=False`). Versión de esquema en tabla `metadata`.
- **Catálogo**: schema v2 (`titulo_lectura`, `titulo_en` nullable, `kanjis_unicos`, `oraciones`; sin encabezados de sección; urashima_taro ahora `media`). La app exige version==2. URL raw `https://raw.githubusercontent.com/T4toh/dokusho-renshuu/main/catalogo/catalogo.json`. **Tanda 2 (Plan 4c)**: +6 historias (10 en total, sin suplentes) — `hanasaka_jijii`, `shitakiri_suzume`, `kintaro` de 楠山正雄; `gongitsune`, `tebukuro_wo_kaini` de 新美南吉; `kumo_no_ito` de 芥川龍之介 (dificultad `facil` las 5 primeras, `media` kumo_no_ito). Invariante byte-idéntico de los 4 JSON de tanda 1 verificado. Gaiji de 3er/4to nivel (`犍` en 犍陀多, kumo_no_ito) resueltos vía tabla `_GAIJI_CONOCIDOS` en `historias/src/aozora.py`. Furigana completa desde 2026-07-13 (PR #12): huecos de ruby Aozora rellenados con janome/IPADIC en el pipeline (spec docs/superpowers/specs/2026-07-13-furigana-relleno-catalogo-design.md); el invariante byte-idéntico de tanda 1 dejó de valer. OJO: el catálogo actualizado NO llega a apps ya instaladas (historias bundleadas nunca se re-descargan, ver backlog) — beta.1 muestra furigana vieja; requiere APK nuevo.
- **Entorno**: builds JVM (gradle/Android Studio, Planes 3-4) van en la PC secundaria — la principal tiene un bug de CPU que cuelga con Java. Python (Plan 2) anda en cualquiera.
- **Contrato furigana**: `[inicio, fin, lectura]` con fin exclusivo sobre el texto de la oración; diálogo `「…」` = 1 oración (portar igual en Kotlin, Plan 3).
- `historias/src/jlpt.py` es generado (regenerar con `genera_jlpt.py` solo si cambia KANJIDIC2).
- **App (Plan 3)**: `app/` compila con JDK 17+ (probado JDK 21) + SDK 36 (PC secundaria); assets generados por gradle tasks (`descargarDiccionario` baja el db del release con escritura atómica tmp→rename; `copiarHistorias` empaqueta `catalogo/`). AGP 9.2 usa Kotlin built-in (sin plugin kotlin-android ni kotlinOptions). UI en inglés; tabla Room `kanjis_tocados` (kanji, dificultad nullable easy/medium/hard, timestamp — migración 1→2 no destructiva) — insumo Plan 4 junto con `palabras_tocadas`; tests con maxHeapSize 2g (OOM Kuromoji).
- **Lector (3.7)**: toggle カナ (pref `katakana`, default ON) muestra hiragana sobre runs de katakana (precomputado en el VM); catálogo con spans de furigana disjuntos (check en verify_catalogo).
- **Anki (4a)**: writer .apkg propio en `dominio/anki/` (schema Anki 2.1 legacy verificado contra genanki; GUID = base91 de SHA-256, golden test). Mazos "Dokusho — Words" (todas las palabras tocadas) y "Dokusho — Kanji" (solo taggeados). Oraciones de historias con ruby HTML + relleno Tatoeba (cap 5), rotación por review via JS en el template. Re-export actualiza sin duplicar (validado en AnkiDroid). OJO: el separador de campos U+001F va como escape Kotlin, nunca literal (`grep -cP '\x1f'` debe dar 0 en fuentes). 4a.1 suma "Dokusho — Stories" (un .apkg, subdeck `::` por historia, todos los kanjis en orden de primera aparición, oraciones solo de esa historia sin Tatoeba, GUID `story:<id>:<kanji>` disjunto del mazo Kanji; EscritorApkg generalizado a N mazos).
- **Para Plan 4b**: portar segmentador de `historias/src/segmentador.py` CON la regla de fusión de spans; furigana de texto importado = Kuromoji puro (sin alineador Aozora).
- **Import (4b)**: segmentador Kotlin en `dominio/SegmentadorTexto.kt` (port fiel del Python, incluida la regla de fusión de spans); furigana persistida generada con Kuromoji (con trim de okurigana; puede errar lecturas de nombres propios, límite conocido); historias importadas en `filesDir/importadas/` (nunca pisan ids de catálogo — reimportar el mismo título asigna id `-2`, `-3`, etc.); export "Dokusho — Stories" ahora con selección por checkbox (catálogo + importadas). Límite extra conocido: el texto importado se indexa por chars UTF-16 — kanji fuera del BMP (rarísimos) podrían desalinear la furigana, mismo límite conocido del catálogo (riesgo #4 del plan).

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
- Portada muestra "0% read" con progreso <1% (truncado a int; el botón Continue/Start ya se arregló en 7ab6b7d).
- Progreso guardado se corre ~1 posición cuando se regenera el catálogo (índices sobre JSON nuevo; one-time, benigno).
- jitendex: xref/sense-note descartados enteros — "See also" se pierde (aceptado; revisar si se quiere conservar con label).
- verify_db no detecta un sentido individual borrado en palabra multi-sentido.
- MigrationTestHelper no usado (exportSchema=false); ProgresoDaoFake overridea registrarAperturaKanji (primitivas dead-code en fake).
- Review section: kanjisPorDificultad consultado 2x por dificultad.
- lookup por lectura sin guard de kana (palabra kanji fuera del db puede resolver a homófono); DIFICULTADES duplicado en VM y Screen.

## Backlog diferido (Plan 4b — no bloqueante)

- ~~Agregar más historias base al catálogo (pipeline Plan 2).~~ Resuelto en Plan 4c (tanda 2, +6 obras).
- Deck names Anki: dos imports con mismo título → mismo nombre de deck `Dokusho — Stories::<título>` con ids distintos (Anki resuelve por nombre → cartas mezcladas); título con `::` anida de más. Desambiguar nombre o filtrar `::`.
- Tarjeta de biblioteca: autor vacío muestra `" · Medium"` (separador colgante) — armar string solo con partes no vacías.
- `puedeImportar` en ImportViewModel es dead code (la Screen recomputa el predicado) — borrar o usar.
- `resumenHistorias()` doc sugiere ahorro de I/O que no existe (parsea historias completas igual).
- DetectorJapones: katakana halfwidth (U+FF61–FF9F) no cuenta como japonés (texto legacy dispara el aviso; benigno).

## Backlog diferido (fix UI export — review final, teórico)

- ExportScreen: toggle de un checkbox recompone todas las filas visibles (la lambda del item lee el set `seleccionadas` entero) — imperceptible con 10 historias; revisar si la lista crece mucho.
- Viewport muy corto (teléfono en landscape): header y bloque Exported se miden primero y la lista weighted puede quedar ~0dp — decisión del spec (header/footer fijos), tablet OK en ambas orientaciones.

## Backlog diferido (tanda 2 — review final, teórico)

- `aozora.py`: gaiji conocido SIN prefijo ※ se dropearía (convención Aozora siempre lo trae) — hardening: lookup incondicional en `_GAIJI_CONOCIDOS`.
- Sin test de línea con gaiji + anotación normal juntas (re.sub multi-match, cubierto por trace).
- Si aparecen más gaiji: keyear `_GAIJI_CONOCIDOS` por sufijo JIS (`第3水準1-87-71`) en vez de la descripción exacta (varía entre archivos).

## Backlog feedback de uso (2026-07-13 — leyendo momotaro)

### Mazos Anki (`dominio/anki/`)

- Marcar (resaltar) el kanji objetivo dentro de la oración de la tarjeta.
- Agregar traducción literal al inglés de la oración.
- Separar un poco más las pronunciaciones entre sí (espaciado visual).
- Poner siempre primero la pronunciación en hiragana (uso más común, también en doblajes).
- Mejorar los separadores entre pronunciaciones.

### App Dokusho

- ~~Poder seleccionar cualquier texto del lector (selección libre).~~ Resuelto (PR A backlog-app): long-press ancla, tap extiende rango de tokens en la misma oración; resaltado + barra contextual.
- ~~Poder buscar lo seleccionado — ¿búsqueda en Google? Definir alcance.~~ Resuelto (PR A): Search web (`ACTION_WEB_SEARCH`, fallback URL de Google) + Copy; texto sin furigana, partículas intermedias incluidas.
- ~~Furigana faltante en momotaro: por alguna razón siempre falta antes de へ (catálogo → alineador `historias/src/aozora.py`, no Kuromoji).~~ Resuelto: la fuente Aozora trae ruby parcial (no era bug del alineador); relleno con janome en el pipeline.
- ~~Faltan muchas más furiganas en general (auditar cobertura).~~ Resuelto: cobertura 11.5%–92.9% → 94.3%–100% con relleno janome/IPADIC (gap residual: 犍陀多 y ~13 kanji sueltos fuera de IPADIC).
- ~~Algunas furiganas están mal: p. ej. 水 = "miizu" (¿みいず?) en vez de みず — revisar origen (ruby Aozora vs `GeneradorFurigana`/Kuromoji).~~ No es bug: `水《みいず》` es la canción del cuento (alarga vocales: かあらいぞ/ああまいぞ); fiel al original.
- ~~**La app nunca actualiza historias bundleadas**~~ Resuelto (PR A): `HistoriasRepo.tamanioLocal` + `BibliotecaViewModel.actualizables` comparan `tamaño` remoto vs local; botón Update por historia re-descarga (descargada pisa asset). Limitaciones aceptadas: cambio remoto de igual tamaño en bytes no se detecta; comparación ciega a dirección (un remoto MÁS VIEJO que el asset también ofrece Update y "downgradearía" — mitigable comparando `version` si alguna vez molesta); CDN desincronizado (catalogo.json nuevo + historia vieja) deja el flag hasta que sincroniza.
- ~~Mostrar número de oración en el lector (p. ej. atrás del piquito de navegación) para poder reportar casos puntuales (pedido de uso real, 2026-07-13).~~ Resuelto (PR A): número 1-based junto al piquito ▸.

## Proceso de trabajo usado

Brainstorming → spec → plan por subsistema (`docs/superpowers/plans/`) → ejecución subagent-driven (implementer + reviewer por tarea, review final de branch) → PR. Repetir por plan.
