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
| A    | app/ — backlog feedback de uso: update de historias + nº de oración + selección/Search web | ✅ Completo ([PR #13](https://github.com/T4toh/dokusho-renshuu/pull/13); smoke en tablet pendiente) |
| B    | historias/catalogo — traducciones literales en inglés por oración | ✅ Completo ([PR #14](https://github.com/T4toh/dokusho-renshuu/pull/14)) |
| C    | app/ — tarjetas Anki: objetivo resaltado, traducción, kun primero, separadores ・ | ✅ Completo ([PR #15](https://github.com/T4toh/dokusho-renshuu/pull/15)) |
| fix  | app/ — selección: extender con auxiliares/partículas + Search web en browser default | ✅ Completo ([PR #16](https://github.com/T4toh/dokusho-renshuu/pull/16)) |
| D    | app/ — feedback 2026-08-18: forma de diccionario (Words + tarjeta de kanji), ejemplos con kanjis simples, toggle EN sin JS | ✅ Completo ([PR #17](https://github.com/T4toh/dokusho-renshuu/pull/17); smoke del .apkg en AnkiDroid OK) |
| E    | app/ — captura OCR con overlay flotante (migración de Kanji-no-Ryoushi) | ✅ Completo ([PR #19](https://github.com/T4toh/dokusho-renshuu/pull/19) mergeado; **smoke de dispositivo COMPLETO el 2026-09-17** — todos los pasos vigentes OK, ver `docs/smoke-captura-ocr.md`) |
| F    | app/ — recortes/notas: la captura crea su propio tipo de contenido en vez de una historia | ✅ Completo (mergeado a `main` en `4892b14`, sin PR; **smoke de dispositivo COMPLETO el 2026-09-17**, ver `docs/smoke-recortes.md`) |
| fix  | app/ — la burbuja no se queda con el foco (Back y toques muertos con la burbuja activa) + smoke de captura OCR cerrado | ✅ Completo ([PR #20](https://github.com/T4toh/dokusho-renshuu/pull/20)) |
| fix  | app/ — cancelar la captura ya no quema el permiso de MediaProjection + el segundo pedido vuelve a mostrar el diálogo | ✅ Completo ([PR #21](https://github.com/T4toh/dokusho-renshuu/pull/21)) |
| G    | app/ — una sesión de MediaProjection por vida de la burbuja: un solo Service, espejo persistente, cerrar la burbuja con long-press | ✅ Completo (PR pendiente — actualizar con #N al abrir; spec `docs/superpowers/specs/2026-09-18-sesion-mediaprojection-design.md`) |

## Datos operativos

- **Release app vigente**: [v0.1.0-beta.3](https://github.com/T4toh/dokusho-renshuu/releases/tag/v0.1.0-beta.3) (prerelease, APK release minificado R8 firmado con debug key, 42.7 MB, main f9c2377 post-PRs #13-#16 — backlog feedback de uso completo: Update por historia, nº de oración, selección+Search web, traducciones en catálogo y tarjetas, kun primero). El usuario la baja a mano de la release para validar el flujo completo. beta.2 y anteriores obsoletas.
- **Release db vigente**: `db-v2` = `diccionario-v2.db` (73.3 MB, metadata version=2, glosas limpias: parser Jitendex descarta 23 marcadores de structured content; MAX_ORACIONES_POR_KANJI=30). db-v1 queda obsoleto.
- **Fuentes** (URLs vigentes en `diccionario/README.md`): Jitendex ya NO distribuye por GitHub release assets; Tatoeba discontinuó el export directo de pares → `diccionario/fuentes_tatoeba.py` los arma desde exports por-idioma.
- **Contrato para la app (Plan 3)**: `oracion_palabra` solo indexa términos de 2-6 chars; palabras de 1 kanji → fallback a `oracion_kanji`. Listas en el db = JSON arrays (`ensure_ascii=False`). Versión de esquema en tabla `metadata`.
- **Catálogo**: schema v2 (`titulo_lectura`, `titulo_en` nullable, `kanjis_unicos`, `oraciones`; sin encabezados de sección; urashima_taro ahora `media`). La app exige version==2. URL raw `https://raw.githubusercontent.com/T4toh/dokusho-renshuu/main/catalogo/catalogo.json`. **Tanda 2 (Plan 4c)**: +6 historias (10 en total, sin suplentes) — `hanasaka_jijii`, `shitakiri_suzume`, `kintaro` de 楠山正雄; `gongitsune`, `tebukuro_wo_kaini` de 新美南吉; `kumo_no_ito` de 芥川龍之介 (dificultad `facil` las 5 primeras, `media` kumo_no_ito). Invariante byte-idéntico de los 4 JSON de tanda 1 verificado. Gaiji de 3er/4to nivel (`犍` en 犍陀多, kumo_no_ito) resueltos vía tabla `_GAIJI_CONOCIDOS` en `historias/src/aozora.py`. Furigana completa desde 2026-07-13 (PR #12): huecos de ruby Aozora rellenados con janome/IPADIC en el pipeline (spec docs/superpowers/specs/2026-07-13-furigana-relleno-catalogo-design.md); el invariante byte-idéntico de tanda 1 dejó de valer. OJO: el catálogo actualizado NO llega a apps ya instaladas (historias bundleadas nunca se re-descargan, ver backlog) — beta.1 muestra furigana vieja; requiere APK nuevo.
- **Entorno**: builds JVM (gradle/Android Studio, Planes 3-4) van en la PC secundaria — la principal tiene un bug de CPU que cuelga con Java. Python (Plan 2) anda en cualquiera.
- **Contrato furigana**: `[inicio, fin, lectura]` con fin exclusivo sobre el texto de la oración; diálogo `「…」` = 1 oración (portar igual en Kotlin, Plan 3).
- **Traducciones (PR B)**: `traduccion` por oración = inglés LITERAL (no funcional), fuentes versionadas en `historias/traducciones/<id>.json` (`{"texto","traduccion"}` en orden párrafo→oración); el emisor valida conteo + texto exacto (falla ruidoso), `verify_catalogo` exige null o string no vacío con all-or-nothing por historia. Textos/furigana intactos → el progreso guardado no se corre; solo cambian `traduccion` y `tamaño` (lo que dispara el Update del PR #13 en apps instaladas). Generadas one-off con LLM (1249 oraciones); parsers viejos ignoran el campo.
- `historias/src/jlpt.py` es generado (regenerar con `genera_jlpt.py` solo si cambia KANJIDIC2).
- **App (Plan 3)**: `app/` compila con JDK 17+ (probado JDK 21) + SDK 36 (PC secundaria); assets generados por gradle tasks (`descargarDiccionario` baja el db del release con escritura atómica tmp→rename; `copiarHistorias` empaqueta `catalogo/`). AGP 9.2 usa Kotlin built-in (sin plugin kotlin-android ni kotlinOptions). UI en inglés; tabla Room `kanjis_tocados` (kanji, dificultad nullable easy/medium/hard, timestamp — migración 1→2 no destructiva) — insumo Plan 4 junto con `palabras_tocadas`; tests con maxHeapSize 2g (OOM Kuromoji).
- **Lector (3.7)**: toggle カナ (pref `katakana`, default ON) muestra hiragana sobre runs de katakana (precomputado en el VM); catálogo con spans de furigana disjuntos (check en verify_catalogo).
- **Anki (4a)**: writer .apkg propio en `dominio/anki/` (schema Anki 2.1 legacy verificado contra genanki; GUID = base91 de SHA-256, golden test). Mazos "Dokusho — Words" (todas las palabras tocadas) y "Dokusho — Kanji" (solo taggeados). Oraciones de historias con ruby HTML + relleno Tatoeba (cap 5), rotación por review via JS en el template. Re-export actualiza sin duplicar (validado en AnkiDroid). OJO: el separador de campos U+001F va como escape Kotlin, nunca literal (`grep -cP '\x1f'` debe dar 0 en fuentes). 4a.1 suma "Dokusho — Stories" (un .apkg, subdeck `::` por historia, todos los kanjis en orden de primera aparición, oraciones solo de esa historia sin Tatoeba, GUID `story:<id>:<kanji>` disjunto del mazo Kanji; EscritorApkg generalizado a N mazos).
- **Para Plan 4b**: portar segmentador de `historias/src/segmentador.py` CON la regla de fusión de spans; furigana de texto importado = Kuromoji puro (sin alineador Aozora).
- **Captura OCR (Plan E)**: los dos Services vienen de `Kanji-no-Ryoushi` (Flutter, main `79ad93d`), que queda obsoleto — su diccionario JitendEx, su historial y sus pantallas los cubre Dokusho mejor. OCR con `com.google.mlkit:text-recognition-japanese:16.0.1`, modelo embebido (offline, sin Play Services); el APK release pasó de 42.7 MB a **86,915,901 bytes (~82.9 MiB)** — la estimación original del spec (55-60 MB) estaba mal, contaba una sola ABI; la causa real es `libmlkit_google_ocr_pipeline.so` empaquetada una vez por ABI en las cuatro (arm64-v8a, armeabi-v7a, x86, x86_64), ~39 MB combinados, sin `abiFilters` ni `splits`. Recortar ABIs ahorraría ~29 MB pero se decidió NO hacerlo acá: elegir qué ABIs soportar es una decisión de producto, y filtrar a solo arm64-v8a rompe la instalación en el emulador x86_64; es un cambio de 1-3 líneas en el `buildType release` si algún día se quiere. El Service hace el OCR y manda solo el texto por Intent (sin MethodChannel ni callbacks estáticos). Una captura = una historia importada. Gateado a Android 10+ (API 29); `minSdk` de la app sigue en 26. Límites conocidos: **CORREGIDO el 2026-09-18 (Plan G)** — la afirmación vieja de que Android 14+ obliga a consentir en cada captura era incorrecta: lo de un solo uso es el TOKEN, y además un `MediaProjection` admite UN solo `createVirtualDisplay` (el segundo tira `SecurityException` y mata el proceso), pero ese display entrega N frames. Con un espejo persistente se consiente UNA vez por vida de la burbuja, verificado en dispositivo; ML Kit no garantiza el orden de las `Line` dentro de un bloque en texto vertical derecha-a-izquierda — por eso el texto queda editable en ImportScreen (arreglo: ordenar por `boundingBox`); si el proceso muere con el Import abierto, el texto capturado se reinyecta con un título de timestamp nuevo y se pierden las ediciones manuales (agujero aceptado: `ImportViewModel` no tiene `SavedStateHandle`, el borrador ya estaba perdido); el Intent de captura se marca consumido al leerlo, así que una Activity destruida entre la lectura y el consumo pierde esa captura (carrera angosta, el precio de que rotar no pise las ediciones). Backlog no migrado del TODO viejo: Quick Settings Tile, zoom en el overlay, ajustes de contraste/brillo pre-OCR, historial de capturas. **Smoke de dispositivo: COMPLETO (2026-09-16, ampliado y cerrado el 2026-09-17)** — corridas en POCO 2412DPC0AG / HyperOS V816OS3.0 / Android 16 (API 36) / navegación por gestos, que cubre las tres condiciones de riesgo a la vez. Verificado en hardware: burbuja (arrastre, snap, tap vs. drag), permisos, `startForeground` con `mediaProjection|specialUse` aceptado por HyperOS, `VirtualDisplay` 1220x2712, OCR real de ML Kit, **el recorte a la selección** (`Recorte escalado: Recorte(left=77, top=562, ancho=1086, alto=174)` para un arrastre de (77,562) a (1163,736), y el OCR devolvió los 11 chars exactos del texto seleccionado; el `.jpg` de la nota pesó 10.7 KB contra ~355 KB de una captura de pantalla completa), **el paso 16** (Back tratado como Cancel, y el botón `Cancel` visible y tocable por encima de la barra de gestos: `margenInferiorBotones()` da bien en este dispositivo) y **el paso 17** (área sin texto → `OCR sin texto: no se abre la app`, sin nota nueva). Salvedad del recorte: acá overlay y bitmap miden lo mismo, así que el escalado corrió 1:1 — el camino con factor ≠ 1, el del bug histórico, sigue cubierto sólo por los 8 tests JVM de `TextoOcrTest`. **Pasos 18, 19 y 20 cerrados el 2026-09-17 (corrida post-PR #21):** captura en frío tras `adb reboot` (boot a las 15:54:49, captura a las 15:56:45, `OCR devolvió 196 chars`, sin un solo reintento — no hay evidencia que justifique cambiar el `postDelayed` de 200 ms); segundo tap durante el OCR (`isCapturing = true` → `Ya hay una captura en curso, ignorando`, una sola nota, burbuja viva después — la ventana depende del tamaño del texto: ~150 ms con 40 chars, ~360 ms con 194); y revocar `SYSTEM_ALERT_WINDOW` en caliente (la burbuja desaparece sola, 0 líneas `FATAL`). Los pasos 7-15 quedaron obsoletos con el Plan F. OJO al reproducir por adb: `input tap` se pierde sobre la ventana de la burbuja (el evento cae en la Activity) — hay que usar `input swipe x y x y <ms>`; en la UI de Compose es al revés. Y un swipe que arranque en x < ~80 lo come el gesto de Back. **Bug encontrado y arreglado el 2026-09-17: con la burbuja activa la app dejaba de responder a toques y al Back** — el foco de entrada se lo llevaba la ventana de la burbuja (`mCurrentFocus` era la ventana type=2038 en vez de MainActivity), y en HyperOS se iban también los toques a la Activity. La causa: `FloatingBubbleService.showBubble()` armaba la ventana sin `FLAG_NOT_FOCUSABLE` a propósito, workaround de MIUI heredado de `Kanji-no-Ryoushi` ("Remover FLAG_NOT_FOCUSABLE para que MIUI reconozca la ventana"). Se le devolvió el flag y se reverificó en el Poco: el foco queda en MainActivity, Back y toques andan, y la burbuja sigue viéndose, arrastrándose, haciendo snap, detectando el tap y capturando igual — o sea el workaround ya no hace falta en esta ROM. Queda sin poder probarse si hace falta en MIUI viejo (no hay dispositivo): si alguna vez se reporta que la burbuja no se ve ahí, este flag es el primer sospechoso. Detalle y evidencia de logcat en `docs/smoke-captura-ocr.md`. OJO al debuggear: SurfaceFlinger de HyperOS usa el mismo tag `ScreenCapture` que la app — filtrar por `--pid`. El resto está verificado por compilación y 248 tests unitarios (0 failures).
- **Import (4b)**: segmentador Kotlin en `dominio/SegmentadorTexto.kt` (port fiel del Python, incluida la regla de fusión de spans); furigana persistida generada con Kuromoji (con trim de okurigana; puede errar lecturas de nombres propios, límite conocido); historias importadas en `filesDir/importadas/` (nunca pisan ids de catálogo — reimportar el mismo título asigna id `-2`, `-3`, etc.); export "Dokusho — Stories" ahora con selección por checkbox (catálogo + importadas). Límite extra conocido: el texto importado se indexa por chars UTF-16 — kanji fuera del BMP (rarísimos) podrían desalinear la furigana, mismo límite conocido del catálogo (riesgo #4 del plan).
- **Recortes/notas (Plan F)**: la captura ya no arma una `Historia` — arma un `Recorte` (`datos/Recorte.kt`), un tipo propio sin la metadata de catálogo (autor, licencia, dificultad, fuente) que no significa nada para tres líneas de manga; texto como fuente de verdad, párrafos como caché derivada regenerada al editar. Persiste en `filesDir/recortes/<id>.json` + `<id>.jpg` opcional (`RecortesRepo`, escritura atómica tmp→rename, id = timestamp en millis incrementado si colisiona, listado saltea JSON corrupto). Imagen en **JPEG calidad 90, no PNG**: un PNG de captura de pantalla completa pesa 2-5 MB y se guarda uno por nota; JPEG 90 deja una pantalla completa en ~700 KB. El Service escribe la captura en un slot fijo (`captura-pendiente.jpg`, `RecortesRepo.NOMBRE_PENDIENTE`) y pasa la ruta por Intent (`EXTRA_RUTA_IMAGEN`); ventana de colisión de ~100 ms en caliente (hasta ~2 s en frío) aceptada a propósito — pisarla exige una segunda captura completa (teardown de Service, permiso de nuevo, drag de región, OCR) adentro de esa ventana; si algún día molesta, el fix va en el Service (`captura-<timestamp>.jpg` + barrido), no en la intake. El vocabulario de recortes se separa del de historias con el prefijo `idHistoria = "recorte:<id>"` filtrado por `LIKE 'recorte:%'` en `ProgresoDb` (sin migración Room, el prefijo entra en la columna TEXT existente); el `:` es a propósito — `ImportadorHistoria.generarId()` sanea títulos con `[\/:*?"<>|.\s　]+` → `_`, así que un id de historia nunca puede traer `:`, pero sí `-` (una historia "recorte-123" da id `recorte-123`, que un filtro con `-` confundiría). Anki suma el mazo "Dokusho — Scans" con GUID propio `scan:<termino>` (`NotaWords.claveGuidPropia`) para que una palabra tocada en historia y en recorte no comparta GUID entre mazos; los GUID de Words/Kanji/Stories quedan byte-idénticos a antes. `ui/comun/` (`OracionRenderizada.kt`: `OracionPlana`/`ItemOracion`/`aplanar`; `Seleccion.kt`: `SeleccionTexto`/`BarraSeleccion`/`buscarEnWeb`) sale del lector para que la vista de recorte lo reuse sin duplicar. Tests: 248 → 299 (el salto neto es menor al de tests agregados: se borró `ProgresoDao.todasPalabras()` sin callers de producción, la lectura sin filtrar que la Task del mazo Scans existía para cerrar; el último lo suma el fix del review final, el guardado que fallaba matando el proceso), 0 failures. Smoke de dispositivo en `docs/smoke-recortes.md`: **COMPLETO el 2026-09-17** en POCO 2412DPC0AG / HyperOS V816OS3.0 / Android 16 / gestos. Verificado en hardware: captura → nota (el texto aterriza en la vista de recorte, no en Import), furigana y diccionario sobre texto capturado, la pestaña Notes sin la doble app bar ni los insets duplicados del hallazgo del review final, y el FAB `Scan` alcanzable. Encontrado y arreglado en el acto: el grid de Stories quedaba pegado a la línea del `PrimaryTabRow` porque usaba `Modifier.padding(horizontal)` sin relleno superior mientras Notes ya tenía `contentPadding` con 16dp arriba — ahora los dos usan `contentPadding` (va DENTRO del área scrolleable, así las cards pasan por debajo al scrollear en vez de cortarse). Cerrado el 2026-09-17 (segunda tanda, por adb): borrar-y-verificar (el par `.json` + `.jpg` desaparece, no uno solo), disco (`1.8M` con 8 notas, jpg más grande 388 KB = JPEG 90 confirmado), export (`Dokusho — Words` con `私` de una historia y `Dokusho — Scans` con `こと` de una nota, sin mezclarse y con GUID disjuntos) e Import manual (crea historia en `files/importadas/`, nunca nota). Único hueco que queda: el cruce del mismo término tocado de los dos lados (no apareció un término compartido a mano).

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

## Backlog feedback de uso (2026-08-18 — PR D)

### Resuelto

- ~~Los mazos ponen el kanji suelto/conjugado; debería ir en forma de diccionario (食べ → 食べる).~~ `LectorViewModel.tocarPalabra` guarda `token.formaBase ?: token.superficie` en `palabras_tocadas`. A propósito NO usa el término que resuelve el diccionario: el fallback por lectura puede devolver otra ortografía (おじいさん → 御爺さん) que el usuario nunca vio.
- ~~No usar ejemplos con kanjis complejos o múltiples.~~ `puntajeSimplicidad(texto, objetivo, jlptDe)` en `ArmadorMazos.kt`: cada kanji distinto ajeno al objetivo suma 1 + complejidad por JLPT de KANJIDIC (4 = 0 … 1 o sin nivel = 3); desempate por oración más corta; orden estable. Se aplica antes del cap de 5 en los tres caminos (historias en Words/Kanji, relleno Tatoeba —se piden `faltan * 4` candidatas—, y mazos por historia). PREFIERE, nunca excluye: si todas son complejas igual salen las 5 mejores.
- ~~Toggle para el inglés en las tarjetas (que no aparezca de una).~~ SIN JavaScript: `<input type="checkbox" id="en-check">` al principio del reverso + `<label for="en-check">EN</label>` como botón, y el CSS revela con `.en-check:checked ~ .significados` / `~ #oracion .traduccion` (por eso el checkbox va ANTES: `~` solo alcanza hermanos posteriores). Default oculto por CSS, `visibility` para no mover el layout. El estado se reinicia solo en cada carta porque AnkiDroid recarga la página entera por lado (`loadDataWithBaseURL`). La primera versión usaba JS (clase en `.card`/`<body>`) y en AnkiDroid el tap no cambiaba nada; ver ledger.
- ~~En el mazo Kanji el frente es el kanji pelado; un verbo debería ir en forma de diccionario (刈 → 刈る "cortar plantas"), criterio Kaishi.~~ `ArmadorMazos.formaDiccionario`: los candidatos salen de las lecturas kun de KANJIDIC con punto de okurigana (`か.る` → 刈る); las marcadas con guion (`-ゆ.き`, `おお-`) son prefijos/sufijos y se descartan. Con forma encontrada, el frente pasa a ser la palabra, la línea kun a su lectura (かる) y los significados a los de la palabra (3 glosas). Sin okurigana (山, 水) la tarjeta queda igual que antes. Aplica también a los mazos por historia.
- ~~Cuando el kanji es verbo Y sustantivo (食 → 食べる y 食〈しょく〉 "food"), la tarjeta debería tener los dos.~~ El frente lleva la forma verbal grande y el sustantivo debajo en chico (`.forma-alt`); el reverso agrega la glosa del sustantivo en `.sig-alt`, dentro de `.significados`, así el toggle EN también la tapa. Solo se busca el sustantivo si el kanji ADEMÁS tiene forma verbal (山 sin verbo queda pelado, sin repetir 山〈やま〉 al lado) y se exige `popularidad > 0`: casi todo kanji tiene alguna entrada suelta rarísima (見〈み〉, 切〈せつ〉, 刈〈かり〉 puntúan 0; 食〈しょく〉 y 山〈やま〉 puntúan 200).

### Ledger

- El toggle EN por JavaScript NO funcionó en AnkiDroid (botón visible, tap sin efecto) y la causa exacta quedó sin confirmar: el mazo se borró antes de poder inspeccionar el notetype instalado. Lo verificado: el `.apkg` exportado llevaba el CSS y el template nuevos (pull por `run-as` desde `cache/export`, notetype 1720000000002), y el mismo CSS+template render izado en Chrome headless dentro del wrapper real de AnkiDroid (`card_template.html` + `flashcard.css`) togglea bien. Se eliminó la dependencia de JS en vez de seguir persiguiéndola: el checkbox nativo no depende de que corra un script ni de mantener una clase en `<body>`, que es de la app y no nuestra.
- AnkiDroid carga cada lado con `loadDataWithBaseURL` (página nueva completa), así que el checkbox arranca destildado en cada carta sin necesidad de resetearlo.

- Las palabras tocadas ANTES de este cambio quedaron guardadas con la superficie conjugada (食べ). `armarWords` hace `distinct()`, así que a lo sumo aparece una carta duplicada 食べ / 食べる por palabra ya tocada. No se migra: implicaría lematizar en SQL o borrar progreso del usuario.
- Los mazos por historia dejaron de tomar las 5 primeras oraciones en orden de lectura: ahora toman las 5 más simples (el orden de lectura sobrevive solo como desempate estable).
- `jlptPorKanji` memoiza `buscarKanji` durante el armado; si el db cambiara en caliente, el puntaje usaría el valor viejo (imposible hoy: el export es one-shot).
- La forma de diccionario NO es única por kanji (食 → 食う y 食べる; 見 → 見る, 見える, 見せる) y `popularidad` no desempata: satura en 200 para todas. Se elige la que más oraciones de ejemplo tiene en el db (mejor proxy de frecuencia disponible: 食べる 10 vs 食う 7, 行く 10 vs 行う 5) y, a igual cantidad, la primera en el orden de KANJIDIC (見る antes que 見える). Verificado a mano contra `diccionario-v2.db` para 刈/食/行/見/切/分/大/高/山/水.
- El guid de la nota Kanji queda fijado a mano en `kanji:<kanji pelado>` (`claveGuidPropia`): si colgara del campo Kanji, que ahora puede traer 刈る, el primer export tras el cambio duplicaría cada nota en vez de actualizarla.
- El campo se sigue llamando `Kanji` aunque a veces trae una palabra: agregar un campo al notetype cambiaría el esquema y el re-import sobre una colección existente puede no mergear (duplicados / scheduling perdido).
- `puntajeSimplicidad` trata "sin nivel JLPT" igual que nivel 1 — un kanji común fuera del set JLPT (p. ej. nombres propios) penaliza de más.
- Pendiente menor de PR C que sigue abierto: `DetalleKanjiScreen` (UI) todavía separa lecturas con `、` en vez de `・`.

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

- ~~Marcar (resaltar) el kanji objetivo dentro de la oración de la tarjeta.~~ Resuelto (PR C): `<b class="objetivo">` por rangos (funciona aunque el objetivo cruce spans de ruby); color de acento con override de modo claro.
- ~~Agregar traducción literal al inglés de la oración.~~ Resuelto (PR B fuente + PR C tarjeta): historias vía `Oracion.traduccion` (inglés literal del catálogo), Tatoeba con su inglés propio; ambas en `<span class="traduccion">`. Limitación: importadas sin traducción.
- ~~Separar un poco más las pronunciaciones entre sí (espaciado visual).~~ Resuelto (PR C): dos líneas etiquetadas (`kun`/`on`).
- ~~Poner siempre primero la pronunciación en hiragana (uso más común, también en doblajes).~~ Resuelto (PR C): kun arriba, on abajo; línea vacía se oculta (condicionales Anki).
- ~~Mejorar los separadores entre pronunciaciones.~~ Resuelto (PR C): `、` → `・` con espacios. Pendiente menor: `DetalleKanjiScreen` (UI) sigue con `、` — unificar si molesta.

### App Dokusho

- ~~Poder seleccionar cualquier texto del lector (selección libre).~~ Resuelto (PR A backlog-app): long-press ancla, tap extiende rango de tokens en la misma oración; resaltado + barra contextual.
- ~~Poder buscar lo seleccionado — ¿búsqueda en Google? Definir alcance.~~ Resuelto (PR A): Search web (`ACTION_WEB_SEARCH`, fallback URL de Google) + Copy; texto sin furigana, partículas intermedias incluidas.
- ~~Furigana faltante en momotaro: por alguna razón siempre falta antes de へ (catálogo → alineador `historias/src/aozora.py`, no Kuromoji).~~ Resuelto: la fuente Aozora trae ruby parcial (no era bug del alineador); relleno con janome en el pipeline.
- ~~Faltan muchas más furiganas en general (auditar cobertura).~~ Resuelto: cobertura 11.5%–92.9% → 94.3%–100% con relleno janome/IPADIC (gap residual: 犍陀多 y ~13 kanji sueltos fuera de IPADIC).
- ~~Algunas furiganas están mal: p. ej. 水 = "miizu" (¿みいず?) en vez de みず — revisar origen (ruby Aozora vs `GeneradorFurigana`/Kuromoji).~~ No es bug: `水《みいず》` es la canción del cuento (alarga vocales: かあらいぞ/ああまいぞ); fiel al original.
- ~~**La app nunca actualiza historias bundleadas**~~ Resuelto (PR A): `HistoriasRepo.tamanioLocal` + `BibliotecaViewModel.actualizables` comparan `tamaño` remoto vs local; botón Update por historia re-descarga (descargada pisa asset). Limitaciones aceptadas: cambio remoto de igual tamaño en bytes no se detecta; comparación ciega a dirección (un remoto MÁS VIEJO que el asset también ofrece Update y "downgradearía" — mitigable comparando `version` si alguna vez molesta); CDN desincronizado (catalogo.json nuevo + historia vieja) deja el flag hasta que sincroniza.
- ~~Mostrar número de oración en el lector (p. ej. atrás del piquito de navegación) para poder reportar casos puntuales (pedido de uso real, 2026-07-13).~~ Resuelto (PR A): número 1-based junto al piquito ▸.

## Captura OCR — sesión de MediaProjection (Plan G, 2026-09-18)

- **Un solo Service.** `FloatingBubbleService` desapareció: `CapturaService` hospeda la
  burbuja, la sesión y el overlay. Una sola notificación (`id=1002`), un solo ciclo de vida,
  y se fueron las credenciales estáticas `captureResultCode`/`captureResultData`, que eran
  estado global compartido entre dos Services y ya habían causado el bug del PR #21.
- **Una sesión por vida de la burbuja.** Se consiente al primer tap que lo necesite y esa
  sesión sirve todas las capturas hasta que la burbuja se apague. Verificado: cinco capturas
  seguidas, un solo diálogo.
- **El espejo (VirtualDisplay + ImageReader) vive con la sesión, no con la captura**, y esto
  NO es opcional: un `MediaProjection` admite un solo `createVirtualDisplay`, el segundo tira
  `SecurityException` y **mata el proceso**. Lo aprendimos crasheando en la segunda captura.
- **Costo medido y mitigado:** el espejo componía ~55 fps continuos mientras la burbuja
  estuviera encendida (`VDS-ScreenCapture SINK ... queueBuffer: fps=54.57`). Se resolvió
  con `setSurface(null)` entre capturas (el espejo "duerme") y re-enganchando el productor
  al arrancar cada captura. Medido en dispositivo (`docs/smoke-captura-ocr.md`, "El espejo
  duerme entre capturas"): **0 frames en 6 segundos** con la burbuja encendida y sin
  capturar, y **5 capturas seguidas sin una sola falla**.
- **La sesión muere en cinco lugares**: `detenerTodo()`, `onDestroy()`, el
  `MediaProjection.Callback.onStop()` (con chequeo de identidad, porque el callback de una
  proyección vieja llega DESPUÉS de que el campo ya apunta a la nueva y si no la anula),
  `abrirSesion()` al reemplazar la sesión vieja por una nueva (`CapturaService.kt:200`), y
  el camino de falla de `armarEspejo()` (`CapturaService.kt:230`). Cuando muere sola, la
  burbuja sigue viva y el próximo tap pide consentimiento: el comportamiento de antes quedó
  como camino de recuperación.
- **Cerrar la burbuja sin entrar a la app**: long-press la convierte en ✕, un segundo toque
  cierra todo, y a los 3 s revierte sola. El gesto que arma la ✕ no puede además cerrarla
  (bug encontrado en dispositivo: colapsaba las dos interacciones en una).
- **Rotación**: el `VirtualDisplay` conserva el tamaño con el que nació, así que al rotar se
  redimensiona y se reemplaza el `ImageReader`. **Sin verificar en dispositivo**: el ROM
  ignora `user_rotation` por adb, hay que girar el teléfono a mano.
- **El riesgo #1 del spec no se materializó**: media hora con la burbuja encendida y la
  sesión abierta sin capturar, y HyperOS **no** mató el Service — mismo pid a los 30
  minutos, y el tap siguiente capturó sin pedir consentimiento ni rearmar el espejo.
- **Pendiente de smoke a mano**: rotar el teléfono entre capturas (el ROM ignora
  `user_rotation` por adb) y frenar la proyección desde el panel del sistema.

## Backlog diferido (Plan F recortes/notas — review final, no bloqueante)

- Borrar un recorte NO borra sus filas de `palabras_tocadas`: `RecortesRepo.borrar()` saca `<id>.json` y `<id>.jpg`, pero el vocabulario queda bajo `recorte:<id>` para siempre. La tarjeta no se rompe — el mazo Scans sigue emitiendo la nota, `armarOraciones` no encuentra oración en el recorte ausente y cae a Tatoeba, así que queda usable — pero acá borrar notas es un flujo de primera clase y rutinario (long-press en la lista), no una excepción como borrar una historia importada: los términos se acumulan sin ninguna vía de purga. La spec nunca decidió esto (ni "se borra en cascada" ni "se conserva a propósito").
- Un `<id>.json` corrupto se saltea en `listar()` (nunca tumbar la lista entera), así que la nota desaparece de la UI mientras sus DOS archivos siguen en disco: sin entrada en la lista no hay long-press, y no hay ninguna otra forma de borrarlos desde la app.
- `borrar()` ignora el resultado de `jpg(id).delete()` mientras `quitarImagen()` lo chequea a propósito y falla si da false — inconsistencia entre hermanos. En `borrar()` el criterio es que el JSON define la existencia (un `.jpg` huérfano no resucita nada), pero el efecto es que un borrado fallido de la imagen deja bytes en disco sin aviso ni log.
- `ui/comun` sigue importando de `ui.lector` los tres símbolos de furigana (`GrupoFurigana`/`TextoConFurigana`/`calcularGruposFurigana`), y aparte `ui/recortes` importa `PalabraSheet` de `ui.lector`. Es exactamente la dirección de dependencia que el KDoc de `ui/comun` dice existir para eliminar — la promoción quedó por la mitad (se movieron `OracionPlana`/`ItemOracion`/`aplanar` y `SeleccionTexto`/`BarraSeleccion`/`buscarEnWeb`, no estos). Cosmético: no hay ciclo ni bug, solo el módulo común dependiendo del específico.
- El borrador de edición vive en `RecorteViewModel`, así que sobrevive a rotación pero NO a muerte de proceso: no hay `SavedStateHandle` (mismo agujero que `ImportViewModel`). Importa más en HyperOS, que mata procesos en background con ganas: dejar la nota a medio editar, irse a otra app y volver puede devolver el texto viejo sin ningún aviso.

## Backlog feedback de uso (2026-09-17 — smoke de recortes en el Poco)

Cuatro pedidos salidos de usar la feature en dispositivo (POCO / HyperOS / Android 16).
Ninguno es un bug de lo implementado: son huecos.

- **Una sola autorización de MediaProjection por sesión del bubble.** Hoy pide permiso en
  cada captura y molesta de verdad. **La justificación que quedó escrita en el spec del
  Plan E —"Android 14+ invalida el token después de cada sesión, no hay forma de
  evitarlo"— es incorrecta**: lo de un solo uso es el TOKEN (`resultCode`/`resultData`),
  pero el `MediaProjection` obtenido con él puede vivir y servir muchas capturas. El
  código lo mata a propósito en `cleanup()` (`mediaProjection?.stop()`) y además hace
  `stopSelf()`, o sea una vida de Service = una captura. Un grabador de pantalla
  mantiene UNA sesión y saca N frames; eso es lo que habría que hacer acá, consintiendo
  al encender el bubble. Costo: el indicador de "grabando pantalla" queda prendido
  mientras el bubble esté activo, y el `VirtualDisplay`+`ImageReader` quedan retenidos.
  Riesgo concreto: HyperOS mata servicios en foreground de forma agresiva, hay que ver
  si la sesión sobrevive. Es cambio de arquitectura del Service, no un ajuste.
- **Controles para ajustar la selección antes de capturar** (agrandar/mover el recuadro,
  zoom para texto chico). El `TODO.md` de `Kanji-no-Ryoushi` ya lo pedía ("zoom en
  overlay para texto pequeño") y el Plan E lo dejó explícitamente fuera de alcance.
- ~~**Recortar sobre la imagen ya guardada y re-correr el OCR.**~~ Resuelto: `Rescan area`
  en la vista de nota (con la imagen expandida) entra en modo recorte, se arrastra un
  recuadro sobre la foto y `Scan` deja el texto reconocido **en el editor**, sin guardarlo
  — el texto viejo sobrevive hasta que el usuario toque `Save`. La imagen guardada NO se
  toca: el recorte vive en memoria, así que se puede recortar otra zona las veces que haga
  falta. Decisiones que quedaron: la parte Android (decodificar + recortar + OCR) vive en
  `RecortadorOcr` (interfaz) con `RecortadorOcrMlKit` como implementación, para que el
  ViewModel se pueda testear en JVM plano sin `Bitmap`; en modo recorte la imagen se achica
  hasta entrar entera en pantalla (a ancho completo una captura queda más alta que el
  viewport y la mitad de abajo no se podía ni ver ni recortar, y el arrastre se come el
  scroll de la lista); y `Scan`/`Cancel` van en la app bar por lo mismo. Sigue fuera de
  alcance el zoom y redimensionar el recuadro después de soltarlo (se vuelve a arrastrar).
- **Títulos editables en las notas.** El spec de recortes los descartó a propósito ("la
  primera línea alcanza para identificar la nota"); el uso real dice que no alcanza.

## Proceso de trabajo usado

Brainstorming → spec → plan por subsistema (`docs/superpowers/plans/`) → ejecución subagent-driven (implementer + reviewer por tarea, review final de branch) → PR. Repetir por plan.
