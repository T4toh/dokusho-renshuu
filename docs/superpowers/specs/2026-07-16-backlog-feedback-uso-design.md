# Cierre del backlog "feedback de uso" (app + decks) — diseño

Fecha: 2026-07-16
Estado: aprobado (brainstorming con Tatoh)

## Problema

ESTADO.md acumula el feedback de uso real del 2026-07-13 (leyendo momotaro
en la beta): 3 items de app y 5 de mazos Anki. Se cierran todos en esta
tanda; los "Backlog diferido" (hardening/tests teóricos) quedan afuera.

Items de app:

1. La app nunca actualiza historias bundleadas: `refrescarCatalogo` filtra
   el catálogo remoto a `id !in idsLocales` y `cargarHistoria` sirve el
   asset si no hay descargada → un catálogo regenerado no llega a apps
   instaladas (beta.1 muestra furigana vieja).
2. Falta el número de oración en el lector para reportar casos puntuales.
3. Selección libre de texto + búsqueda: el diccionario no tiene expresiones
   largas ni frases adverbiales; hace falta seleccionar un tramo y buscarlo
   en el browser.

Items de decks (tarjetas Anki):

4. Resaltar el kanji/término objetivo dentro de la oración.
5. Traducción literal al inglés de la oración.
6. Pronunciación en hiragana (kun) siempre primero.
7. Más separación visual entre pronunciaciones.
8. Mejores separadores entre pronunciaciones.

## Decisiones tomadas

- **Update de historias**: botón "Update" por historia (no auto-update):
  el progreso guardado puede correrse ~1 oración al regenerar el catálogo,
  el usuario decide cuándo. Detección por `tamaño` del catálogo remoto vs
  local (`tamaño` = `os.path.getsize` del JSON, emisor.py:77 — comparación
  exacta con `File.length()` de la descargada; para bundleadas se compara
  contra la entrada del catálogo asset). Sin cambio de pipeline ni schema.
  Limitación aceptada: un cambio de igual tamaño en bytes no se detecta.
- **Número de oración**: junto al piquito ▸ del lector (discreto, siempre
  visible); oculto en portada.
- **Selección libre**: long-press en un token ancla la selección, tap en
  otro token extiende el rango; acciones **Search web** (intent de búsqueda,
  texto sin furigana), **Copy** y cancelar. No se usa `SelectionContainer`
  nativo (chocaría con los taps por token y arrastraría la furigana).
- **Traducción de historias**: pre-generada en el pipeline (LLM one-off),
  **inglés literal, NO funcional** (el inglés natural confunde más de lo
  que aporta). Se guarda en el campo `traduccion` del JSON de oración, que
  ya existe en el schema y hoy se emite siempre null → sin bump de schema;
  parsers viejos lo ignoran. Fuentes versionadas en
  `historias/traducciones/<id>.json` para pipeline reproducible.
- **Lecturas en tarjeta Kanji**: dos líneas etiquetadas, kun (hiragana)
  primero, on (katakana) después; etiqueta gris chica; lecturas separadas
  por `・` con espacios. Línea vacía no se muestra (condicionales Anki).
- **Orden de PRs**: A (app) → B (pipeline/traducciones) → C (decks). A va
  primero para que el catálogo regenerado en B llegue a apps instaladas.

## PR A — app: update + número de oración + selección

**A1 — update de historias**

- `BibliotecaViewModel.refrescarCatalogo` (BibliotecaViewModel.kt:93-104):
  además de las historias remotas nuevas, computar por historia local
  no-importada un flag `actualizacionDisponible` comparando `tamaño` remoto
  vs local (asset: entrada de `catalogoLocal()`; descargada:
  `File.length()` de `filesDir/historias/<id>.json`).
- UI biblioteca: badge/botón "Update" en la tarjeta → `descargarHistoria(id)`
  (ya existe, escritura atómica); la descargada pisa el asset por la
  prioridad descargada > asset de `historiasLocales()`. El flag se limpia
  al completar la descarga.
- Importadas quedan fuera de la comparación.

**A2 — número de oración**

- `LectorScreen.kt:267-284` (indicadores ▸/◂ fijos): `Text` chico con
  `indiceActual + 1` junto al piquito; oculto cuando `enPortada`.

**A3 — selección de tokens + búsqueda web**

- `FilaGrupo` (TextoConFurigana.kt:137-165): `combinedClickable` por token
  de contenido — long-press ancla selección; tap con selección activa
  extiende el rango [min, max] por `PalabraToken.inicio/fin`; tap sin
  selección sigue abriendo el diccionario.
- Estado de selección en `LectorViewModel` (oración enfocada + rango de
  offsets); se limpia al navegar/enfocar otra oración o cancelar.
- Tokens seleccionados con background de resaltado.
- Barra contextual en modo selección: Search web (`ACTION_WEB_SEARCH`,
  fallback URL de Google con `ACTION_VIEW`), Copy (clipboard), X cancelar.
  El texto seleccionado = concatenación de superficies del rango (incluye
  tokens no-contenido intermedios), sin furigana.

## PR B — pipeline: traducciones literales + regen catálogo

- `historias/traducciones/<id>.json`: lista de traducciones paralela a las
  oraciones de la historia (mismo orden que el catálogo emitido). Generación
  one-off con LLM (subagentes), estilo literal: estructura japonesa
  preservada, glosas directas.
- `emisor.py`: al emitir cada oración, tomar la traducción por índice;
  falla ruidoso si el conteo de traducciones ≠ conteo de oraciones.
- `verify_catalogo.py`: chequear cobertura de `traduccion` (100% de
  oraciones con string no vacío en historias con archivo de traducciones).
- Regenerar `catalogo/`; assets de la app se actualizan vía gradle
  `copiarHistorias`. `tamaño` cambia → apps con PR A ven "Update".
- Textos y furigana no cambian → progreso guardado no se corre.

## PR C — app/decks: mejoras de tarjetas Anki

1. Parser/serializador de historias leen y persisten `traduccion`
   (`Oracion` suma `traduccion: String?`; hoy ParserHistoria lo ignora y
   SerializadorHistoria emite null).
2. `oracionARubyHtml` (ArmadorMazos.kt:210-226) recibe el término/kanji
   objetivo y envuelve sus ocurrencias en `<b class="objetivo">` (color de
   acento en CSS); ídem el relleno Tatoeba (hoy `escapeHtml` directo).
3. Traducción en tarjeta: historias usan `Oracion.traduccion`, Tatoeba ya
   trae `ingles`; ambas van en `<span class="traduccion">` (clase CSS ya
   definida y sin uso, ModeloNotas.kt:185-190) en vez de `<br>` plano.
4. `AFMT_KANJI` (ModeloNotas.kt:259): de `on kun` en una línea a dos líneas
   etiquetadas, kun primero (`{{#KunYomi}}` / `{{#OnYomi}}` para omitir
   vacías; CSS `.etiqueta-lectura` gris chica).
5. `joinToString("、")` → `joinToString(" ・ ")` en ArmadorMazos.kt:113-114
   y :173-174.
- Stories reutiliza el modelo Kanji (EscritorApkg.kt:142-150) → hereda
  todo. Words gana resaltado + traducción (una sola `Lectura`, sin cambio
  de orden).
- Re-export actualiza notas sin duplicar (GUID estable); los templates
  viajan con el modelo en el .apkg.

## Manejo de errores

- Update: fallo de red en `descargarHistoria` → mismo manejo actual
  (escritura atómica, el asset sigue sirviendo); el flag persiste.
- Traducciones: emisor falla ruidoso ante conteos desparejos; historia sin
  archivo de traducciones emite `traduccion: null` (comportamiento actual).
- Tarjetas: oración sin traducción → span omitido (sin "null" visible).

## Testing

- Unit: comparación de update en BibliotecaViewModel (remoto más nuevo /
  igual / historia importada); estado de selección en LectorViewModel
  (anclar, extender, limpiar al navegar); texto seleccionado sin furigana;
  resaltado del objetivo (ocurrencias múltiples, objetivo dentro de span
  ruby); traducción presente/ausente; orden kun/on y condicionales en
  template; separadores `・`. Golden test .apkg actualizado.
- Pytest: merge de traducciones en emisor (conteo ok / desparejo),
  cobertura en verify_catalogo.
- Invariante U+001F: `grep -cP '\x1f'` = 0 en fuentes Kotlin.
- End-to-end por PR: build APK en esta máquina, `adb install -r` en tablet
  (quirks MIUI); smoke: badge Update aparece tras regen y desaparece al
  actualizar; número junto al piquito; long-press → selección → Search web
  abre browser; export .apkg → AnkiDroid → kun primero, objetivo resaltado,
  traducción visible.

## No-objetivos

- Auto-update de historias, hash/checksum en el catálogo, bump de schema.
- Selección nativa de Android (`SelectionContainer`) o selección a nivel
  de caracteres.
- Traducción funcional/natural de oraciones; traducir historias importadas
  por el usuario (Kuromoji no traduce; fuera de alcance).
- Búsqueda interna en el diccionario para frases (el caso de uso es
  justamente lo que el diccionario no tiene).
