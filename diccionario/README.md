# diccionario/ — parser de diccionario.db

Combina tres fuentes libres en un SQLite para la app Dokusho Renshū.
Python stdlib only, sin pip. Evolución de
[jitendex-parser](https://github.com/T4toh/jitendex-parser).

## Fuentes (descargar a `fuentes/`, gitignored)

`construir()` exige los nombres exactos de abajo — no hay fallback a
nombres de fixture, así que hay que respetarlos tal cual.

| Fuente | Qué aporta | Descarga | Nombre esperado |
|--------|-----------|----------|------------------|
| Jitendex (Yomitan) | palabras JP→EN | https://jitendex.org/pages/downloads.html → `jitendex-yomitan.zip` (hosteado en `github.com/stephenmk/stephenmk.github.io/releases/latest`) → extraer `term_bank_*.json` a `fuentes/` | `term_bank_*.json` (uno o más) |
| KANJIDIC2 | kanjis: lecturas, significados, JLPT | http://www.edrdg.org/kanjidic/kanjidic2.xml.gz → `gunzip` | `kanjidic2.xml` |
| Tatoeba | oraciones de ejemplo JP-EN (CC-BY) | ver abajo (Tatoeba ya no ofrece un export directo "Sentence pairs") | `pares_jpn_eng.tsv` |

```bash
cd diccionario && mkdir -p fuentes && cd fuentes

# Jitendex: bajar el zip y extraer los term_bank_*.json a este directorio
wget -O jitendex-yomitan.zip \
  "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip"
unzip -j jitendex-yomitan.zip 'term_bank_*.json' -d .
rm jitendex-yomitan.zip

# KANJIDIC2
wget http://www.edrdg.org/kanjidic/kanjidic2.xml.gz && gunzip kanjidic2.xml.gz
```

### Tatoeba: generar `pares_jpn_eng.tsv`

Tatoeba dejó de publicar el export directo "Sentence pairs" jpn→eng
(la sección "Downloads" ahora sólo ofrece exports por idioma). Hay que
cruzar tres archivos de https://downloads.tatoeba.org/exports/per_language/:

```bash
cd diccionario/fuentes
wget https://downloads.tatoeba.org/exports/per_language/jpn/jpn_sentences.tsv.bz2
wget https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2
wget https://downloads.tatoeba.org/exports/per_language/jpn/jpn-eng_links.tsv.bz2
bunzip2 jpn_sentences.tsv.bz2 eng_sentences.tsv.bz2 jpn-eng_links.tsv.bz2
cd ..
python fuentes_tatoeba.py --fuentes fuentes   # genera fuentes/pares_jpn_eng.tsv
```

`fuentes_tatoeba.py` es un script stdlib-only (con su propio test en
`tests/test_fuentes_tatoeba.py`) que arma las 4 columnas que espera
`src.tatoeba.parsear_pares` (id_jpn, japonés, id_eng, inglés) cruzando
`jpn_sentences.tsv` + `eng_sentences.tsv` vía `jpn-eng_links.tsv`, ignorando
links sin oración correspondiente en alguno de los dos idiomas.

Nota: se usó el export por-idioma `jpn-eng_links.tsv` (~1-4 MB) en lugar del
`links.tar.bz2` global (cientos de MB) porque ya viene filtrado al par
jpn-eng. Se confirmó que los tres archivos reales vienen con fin de línea
LF (no CRLF), así que `parsear_pares` no necesitó el ajuste `rstrip('\r\n')`
que preveía el riesgo conocido — si en el futuro un export trae CRLF, ese
es el fix a aplicar (con test que reproduzca el caso primero).

## Uso

```bash
python parser.py                      # fuentes/ → diccionario-v1.db
python verify_db.py diccionario-v1.db # verificación (exit 1 si falla)
python -m unittest discover tests -v  # tests
```

**Contrato de `oracion_palabra`**: solo indexa términos de 2 a 6 caracteres
(`LARGO_MIN_TERMINO`/`LARGO_MAX_TERMINO` en `src/constructor.py`). Para
palabras de un solo kanji (猫, 山, ...) no hay filas en `oracion_palabra`:
la app debe hacer fallback a `oracion_kanji` del kanji correspondiente para
mostrar oraciones de ejemplo.

## Release

1. `python parser.py && python verify_db.py diccionario-v1.db`
2. Chequeo manual: abrir el db, buscar 2-3 palabras conocidas (`verify_db.py`
   ya hace spot-checks de 語/物/日/人 y 物語)
3. `gh release create db-v1 diccionario/diccionario-v1.db --title "diccionario.db v1" \
    --notes "Jitendex v(fecha del zip descargado) + KANJIDIC2 <fecha> + Tatoeba <fecha>"`
4. La app (Plan 3) baja este asset a `app/src/main/assets/`

## Licencias

- Jitendex: CC BY-SA 4.0 · KANJIDIC2: CC BY-SA 4.0 (EDRDG) · Tatoeba: CC-BY 2.0 FR
- Atribución requerida en la app (pantalla "Acerca de", Plan 3)
