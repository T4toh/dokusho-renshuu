# historias/ — pipeline Aozora → catálogo JSON

Convierte cuentos de Aozora Bunko en los JSON que consume la app
(`catalogo/` en la raíz del repo, servido vía raw.githubusercontent).
Python stdlib only, sin pip.

## Obras (declaradas en `obras.json`)

| id | Obra | Card Aozora |
|----|------|-------------|
| momotaro | 桃太郎 — 楠山正雄 | [18376](https://www.aozora.gr.jp/cards/000329/card18376.html) |
| urashima_taro | 浦島太郎 — 楠山正雄 | [3390](https://www.aozora.gr.jp/cards/000329/card3390.html) |
| issunboshi | 一寸法師 — 楠山正雄 | [43457](https://www.aozora.gr.jp/cards/000329/card43457.html) |
| kachikachi_yama | かちかち山 — 楠山正雄 | [18377](https://www.aozora.gr.jp/cards/000329/card18377.html) |

## Descarga de fuentes (manual, a `fuentes/`, gitignored)

Cada zip trae un único `.txt` (Shift_JIS, ruby `《》`); renombrar al valor
de `archivo` en `obras.json`. Ejemplo con momotaro:

```bash
mkdir -p fuentes && cd fuentes
wget https://www.aozora.gr.jp/cards/000329/files/18376_ruby_12074.zip
unzip -o 18376_ruby_12074.zip && rm 18376_ruby_12074.zip
mv *.txt momotaro.txt
# repetir con el campo "url" de cada obra en obras.json
```

## Uso

```bash
python3 genera_jlpt.py                  # regenerar src/jlpt.py (solo si cambia KANJIDIC2)
python3 pipeline.py                     # fuentes/ + obras.json → ../catalogo/
python3 verify_catalogo.py              # verificación (exit 1 si falla)
python3 -m unittest discover tests -v   # tests
```

## Contrato de datos (app, catálogo v2)

- `furigana` = `[inicio, fin, lectura]`, **fin exclusivo**, índices sobre el
  `texto` de la oración.
- `traduccion` siempre `null` (reservado para el toggle a inglés).
- `catalogo.json` = `{"version": 2, "historias": [{id, titulo, titulo_lectura,
  titulo_en, autor, dificultad, tamaño, version, kanjis_unicos, oraciones}]}`
  — `tamaño` en bytes del JSON de la historia; `titulo_lectura` es la lectura
  en kana del título; `titulo_en` la traducción al inglés; `kanjis_unicos` y
  `oraciones` son stats agregadas de la historia completa (metadata curada,
  no presente en el JSON individual de la historia en `catalogo/historias/`).
- `dificultad` ∈ `{facil, media, dificil}` (umbral en `src/dificultad.py`).
- El diálogo `「...。...。」` es UNA oración (el segmentador no corta dentro
  de comillas) — mismo algoritmo a portar en Kotlin (Plan 3). Ninguna oración
  es solo puntuación de cierre (spans residuales se fusionan).

## Agregar una obra

1. Agregar entrada a `obras.json` (id, archivo, fuente, url).
2. Descargar el `.txt` a `fuentes/` (ver arriba).
3. `python3 pipeline.py && python3 verify_catalogo.py`
4. Commitear `catalogo/` regenerado.

## Licencias

Textos de Aozora Bunko en dominio público (autor 楠山正雄, †1950).
Atribución de fuente en cada JSON (`fuente: aozora:<card>`).
