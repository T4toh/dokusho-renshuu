# Dokusho Renshū (読書練習) — Diseño

**Fecha**: 2026-07-06
**Estado**: aprobado en brainstorming, pendiente de plan de implementación

## Propósito

App Android nativa para practicar lectura de japonés. Alternativa simple a Anki
para quien ya domina su mazo: leer textos largos reales (cuentos de dominio
público) en fragmentos cortos, con full kanji, tocando palabras para ver su
significado, y generando mazos Anki a partir de lo leído.

Público: principiantes/intermedios que quieren practicar lectura y kanjis.

## Decisiones tomadas

| Tema | Decisión |
|------|----------|
| Stack | Kotlin + Jetpack Compose, Android puro. Compose deja puerta abierta a Compose Multiplatform (iOS) si algún día hay Mac. |
| Contenido | Híbrido: 3-5 historias empaquetadas en APK + catálogo descargable + importación de texto propio. |
| Hosting catálogo | GitHub como CDN (raw.githubusercontent). Migrable a Cloudflare después sin tocar la app. |
| Tap en palabra | Palabra completa primero (lectura + significado), con drill-down a cada kanji individual. |
| Furigana | Toggle global en top bar. Full kanji por defecto. Nunca romaji. |
| Fragmentos | Avance por oración, con oraciones anteriores visibles atenuadas (contexto). |
| Export Anki | Archivo `.apkg` + share intent. API de AnkiDroid solo si hace falta después. |
| Cartas Anki | Kanji-céntricas: kanji al frente; dorso con significados, lecturas, oración de la historia + 2-3 oraciones Tatoeba. Varias oraciones por kanji para evitar memorizar estructura en vez del kanji. |
| Oraciones ejemplo | Tatoeba offline dentro del db (las de jisho.org salen de ahí). Sin APIs. |
| Tokenización | On-device con Kuromoji (JVM). El pipeline NO pre-tokeniza. |
| Organización | Monorepo `dokusho-renshuu` con app + parser + pipeline + catálogo. |
| Futuro (fuera de scope v1) | Toggle por oración a inglés. El formato JSON ya reserva el campo `traduccion`. |

## Arquitectura general

```
┌─ diccionario/ (Python) ────────────────────┐
│ jitendex JSON + KANJIDIC2 + Tatoeba        │
│         → diccionario.db (SQLite)          │
└────────────────┬───────────────────────────┘
                 │ db vía GitHub Releases → assets del APK
                 ▼
┌─ app/ (Kotlin + Compose) ──────────────────┐
│ Lector · Diccionario · Generador .apkg     │
│ Tokenizador Kuromoji on-device             │
└────────────────▲───────────────────────────┘
                 │ HTTP GET
┌─ historias/ (Python) ──────────────────────┐
│ Aozora Bunko → limpieza → JSON             │
│ → catalogo/ (commiteado, servido raw)      │
└────────────────────────────────────────────┘
```

### Estructura del monorepo

```
dokusho-renshuu/
├── app/            # Android — Kotlin + Compose
├── diccionario/    # parser v2 — Python (evolución de jitendex-parser)
├── historias/      # pipeline — Python (Aozora → JSON)
├── catalogo/       # OUTPUT versionado: catalogo.json + historias/*.json
└── docs/           # specs y planes
```

- `diccionario.db` NO se commitea (50-80 MB). Se publica en GitHub Releases;
  la app lo incorpora a `app/src/main/assets/` (descarga manual o gradle task).
- Las historias JSON sí se commitean (chicas). Cambio de formato JSON =
  pipeline + app actualizados en un commit atómico.
- Orden de build: `diccionario/` primero (la app no arranca sin db);
  `historias/` en paralelo; `app/` al final.

## Componente 1: diccionario/ (parser v2)

Evolución del jitendex-parser existente. Python stdlib-only, mismo estilo.

**Fuentes** (todas libres): Jitendex actualizado (palabras JP→EN),
KANJIDIC2 (kanjis: significados, lecturas, JLPT, strokes),
Tatoeba pares JP-EN (CC-BY, oraciones de ejemplo).

**Esquema SQLite:**

```sql
palabras        (id, termino, lectura, significados, tags)
kanjis          (kanji PK, significados, on_yomi, kun_yomi, jlpt, strokes)
oraciones       (id, japones, ingles)
oracion_kanji   (kanji, id_oracion)      -- qué oraciones usan cada kanji
oracion_palabra (termino, id_oracion)    -- ejemplos por palabra
```

- `oracion_kanji` se precalcula al parsear → consulta instantánea en la app.
- Tatoeba filtrado a pares con traducción EN directa (~200-300k). Si el db
  supera ~80 MB, capar a 50 oraciones por kanji priorizando las cortas.
- `verify_db.py` actualizado: counts por fuente, integridad FK, kanjis
  huérfanos, spot-checks de kanjis conocidos.
- Output versionado: `diccionario-vX.db`. La app declara versión mínima.

## Componente 2: historias/ (pipeline)

**Input**: obras de Aozora Bunko (HTML o texto con marcado propio).

**Proceso:**
1. Descargar/leer obra por ID de Aozora
2. Limpiar marcado Aozora, notas de editor, headers legales
3. Extraer furigana del ruby de Aozora (`《ふりがな》`) y guardarla alineada
   — mejor calidad que furigana generada por tokenizador
4. Segmentar párrafos → oraciones (split por 。！？ respetando comillas 「」)
5. Estimar dificultad: % kanji fuera de JLPT N5-N4 + largo promedio de
   oración → etiqueta fácil/media/difícil
6. Emitir `catalogo/historias/<id>.json` + actualizar `catalogo/catalogo.json`

**Formato de historia:**

```json
{
  "id": "momotaro",
  "titulo": "桃太郎", "autor": "楠山正雄",
  "fuente": "aozora:001091", "licencia": "dominio público",
  "dificultad": "facil",
  "parrafos": [
    { "oraciones": [
      { "texto": "むかし、むかし...",
        "furigana": [[2, 4, "..."]],
        "traduccion": null }
    ]}
  ]
}
```

`furigana` = lista `[inicio, fin, lectura]` sobre índices del texto.
`traduccion` es null en v1 (reservado para el toggle a inglés futuro).

**Catálogo** (`catalogo.json`): lista de `{id, titulo, autor, dificultad,
tamaño, version}`. La app lo baja de
`raw.githubusercontent.com/T4toh/dokusho-renshuu/main/catalogo/catalogo.json`.

**Empaquetadas en APK**: 3-5 historias cortas fáciles (ej: Momotarō) como
assets, misma estructura JSON. App usable sin red desde el primer arranque.

## Componente 3: app/ (Android)

Kotlin + Jetpack Compose. MVVM simple, sin over-engineering.
UI y comentarios en español. Paquete: `com.tatoh.dokushorenshu`.

### Pantallas

1. **Biblioteca** (home): historias locales + catálogo remoto. Tarjetas con
   título, autor, dificultad, progreso (%). Botón importar texto propio.
2. **Lector** (pantalla central):
   - Oración actual grande, centrada, full kanji
   - Oraciones anteriores arriba, atenuadas; scroll para más contexto
   - Toggle furigana en top bar (global, persiste)
   - Tap en palabra → bottom sheet: palabra, lectura, significados +
     botón "ver kanjis" → drill-down a cada kanji
   - Avance con botón siguiente / swipe; posición guardada por historia
   - Las palabras tocadas se registran (insumo para mazos)
3. **Detalle kanji**: significados, on/kun, JLPT, oraciones Tatoeba.
4. **Generar mazo**: por historia → preview de contenido → exportar `.apkg`
   → share intent (AnkiDroid importa directo).

### Capas

```
ui/       Composables + ViewModels (StateFlow)
dominio/  Tokenizador (Kuromoji), generador apkg, lógica de mazos,
          segmentador de oraciones (portado del pipeline)
datos/    DiccionarioDao (SQLite readonly desde assets)
          HistoriasRepo (assets + descargadas + importadas)
          ProgresoDao (Room: posición, palabras tocadas, prefs)
```

- `diccionario.db` se copia de assets al filesystem en el primer arranque;
  se abre readonly.
- Texto importado: pegar texto o abrir `.txt` → segmentador de oraciones en
  Kotlin (mismo algoritmo que el pipeline) → historia local sin furigana
  pre-alineada (furigana on-demand vía Kuromoji).

### Generación de mazos .apkg

- Formato apkg = zip con SQLite (`collection.anki2`) + media. Generado en
  Kotlin puro (`android.database.sqlite` + `java.util.zip`), sin dependencia
  externa. Referencia de estructura: genanki.
- **Modelo de carta** (kanji-céntrica):
  - Frente: kanji solo
  - Dorso: significados + on/kun yomi + oración de la historia donde apareció
    (kanji resaltado) + 2-3 oraciones Tatoeba con traducción EN
- **Selección de contenido** (por historia):
  - Modo automático: todos los kanjis de la historia, orden de aparición,
    sin duplicados
  - Filtros: solo lo tocado al leer · excluir JLPT N5 · rango de oraciones leídas
- **IDs estables**: mismo kanji → mismo `guid` Anki; re-importar actualiza
  en vez de duplicar.
- Export vía `FileProvider` + share intent.

## Manejo de errores

- **Sin red**: biblioteca muestra lo local; catálogo remoto con mensaje y
  retry. Nunca bloquea la lectura.
- **Descarga corrupta/parcial**: validar JSON contra estructura esperada
  antes de guardar; si falla, descartar y avisar.
- **Palabra sin definición**: bottom sheet muestra lectura de Kuromoji +
  "sin definición". Nunca crash ni sheet vacío.
- **Import de texto**: heurística de % de caracteres CJK; si no parece
  japonés, avisar antes de guardar.
- **db de assets**: verificación de versión al arranque; corrupta o faltante
  → re-copiar de assets.
- **Pipeline/parser**: fallar ruidoso con log claro; nunca emitir JSON o db
  a medias.

## Testing

- **diccionario/**: unittest (estilo dialogos_a_esp) — counts esperados por
  fuente, integridad FK, spot-checks de kanjis conocidos (語, 物).
  `verify_db.py` extendido.
- **historias/**: fixtures con fragmentos reales de Aozora → JSON esperado.
  Casos: ruby, comillas 「」, diálogo multi-oración.
- **app/**: JUnit para dominio — segmentador de oraciones, generador apkg
  (abrir el SQLite generado y validar esquema Anki), tokenización Kuromoji
  en tests JVM puros. UI: smoke tests de Compose solo si suman.
- **Validación real**: importar un `.apkg` generado a AnkiDroid manualmente
  antes de cerrar esa feature.

## Fuera de scope v1

- Toggle por oración a inglés (formato ya lo contempla)
- iOS / Compose Multiplatform
- SRS propio (para eso está Anki)
- Audio / TTS
- Integración directa con API de AnkiDroid (solo si `.apkg` queda corto)
