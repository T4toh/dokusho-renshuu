# Plan 2: historias/ (pipeline Aozora → catálogo JSON)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## Context

Plan 1 (`diccionario/`) está completo y publicado (`db-v1`). ESTADO.md marca Plan 2 como siguiente: el pipeline `historias/` que convierte cuentos de Aozora Bunko en los JSON que consumirá la app Android (Plan 3), más el `catalogo/` commiteado que se sirve vía raw.githubusercontent. Sin este subsistema no hay contenido para el lector.

Decisiones confirmadas con el usuario (2026-07-07):
- **JLPT para dificultad**: módulo `src/jlpt.py` generado one-shot desde `kanjidic2.xml` y **commiteado** (pipeline autocontenido).
- **Obras v1**: 4 cuentos de 楠山正雄 — 桃太郎 (card 18376), 浦島太郎 (card 3390), 一寸法師 (card 43457), かちかち山 (card 18377). URLs de zip verificadas contra aozora.gr.jp.
- **Descarga**: manual documentada en README (patrón `diccionario/`, cero HTTP en código). `fuentes/` gitignored.

**Goal:** Pipeline Python que convierte los .txt de Aozora Bunko (Shift_JIS, ruby `《》`, anotaciones `［＃］`) en `catalogo/historias/<id>.json` + `catalogo/catalogo.json` con furigana alineada, oraciones segmentadas y dificultad estimada.

**Architecture:** Módulos por responsabilidad en `historias/src/` (`aozora.py` limpieza+ruby, `segmentador.py` oraciones, `dificultad.py` con `jlpt.py` generado, `emisor.py` JSON atómico); CLI `pipeline.py` orquesta según manifest `obras.json`; `verify_catalogo.py` valida el output (patrón `verify_db.py`). Output commiteado en `catalogo/` (raíz del repo).

**Tech Stack:** Python 3.11+, stdlib only (json, re, os, xml.etree, unittest, argparse). Sin pip.

## Global Constraints

- **Python stdlib only** — cero dependencias externas. Sin pip.
- **Comentarios y nombres en español** (términos técnicos en inglés OK).
- Código en `historias/`; output en `catalogo/` (raíz del monorepo, **sí se commitea**).
- `historias/fuentes/` (los .txt crudos de Aozora) **gitignored**.
- Formato JSON de historia = el del spec (`docs/superpowers/specs/2026-07-06-dokusho-renshuu-design.md`): `furigana` = `[inicio, fin, lectura]` con **fin exclusivo**, índices sobre el `texto` de cada oración; `traduccion: null` en v1. Se agrega campo `version` (int) a la historia — el catálogo lo copia.
- `catalogo.json` = `{"version": 1, "historias": [{id, titulo, autor, dificultad, tamaño, version}]}` (wrapper con versión de formato; el spec pedía la lista — el wrapper permite evolucionar el formato sin romper la app).
- JSON con `ensure_ascii=False, indent=1`; escritura **atómica** (tmp + `os.replace`) — nunca emitir a medias.
- Pipeline **falla ruidoso** (excepciones sin catch); la validación fina va en `verify_catalogo.py` (junta errores, `✗`/`✓`, exit 1).
- El segmentador se porta a Kotlin en Plan 3 — mantenerlo simple y sin dependencias entre módulos.
- Tests con `unittest`: `cd historias && python -m unittest discover tests -v`.
- Commits desde la raíz del monorepo, prefijo `feat(historias):` / `test(historias):` / `docs(historias):`.
- Trabajo en branch `feature/plan-2-historias`; PR al final (proceso del Plan 1).

## Ejecución — paso 0

1. `git checkout -b feature/plan-2-historias` (main ya está al día con origin, verificado hoy).
2. Guardar este plan (desde la sección de header hacia abajo) como `docs/superpowers/plans/2026-07-07-plan-2-historias.md` y commitear: `docs: plan 2 — pipeline historias/`.
3. Ejecutar tasks con subagent-driven-development (implementer + reviewer por task).

## File Structure

```
historias/
├── pipeline.py             # CLI: fuentes/ + obras.json → ../catalogo/
├── verify_catalogo.py      # verificación integral del catálogo emitido
├── genera_jlpt.py          # one-shot: kanjidic2.xml → src/jlpt.py
├── obras.json              # manifest de obras (id, archivo, fuente, url)
├── README.md               # obras, descarga, uso, contrato de datos
├── .gitignore              # fuentes/ y __pycache__/
├── src/
│   ├── __init__.py
│   ├── japones.py          # es_kanji, es_base_ruby, extraer_kanjis
│   ├── aozora.py           # limpieza de marcado + extracción de ruby
│   ├── segmentador.py      # párrafos → oraciones, furigana reindexada
│   ├── jlpt.py             # GENERADO por genera_jlpt.py — commiteado
│   ├── dificultad.py       # facil/media/dificil
│   └── emisor.py           # JSON de historia + catalogo.json, atómico
└── tests/
    ├── __init__.py
    ├── fixtures/
    │   ├── fragmento_aozora.txt   # UTF-8 en repo (cp932 se testea aparte)
    │   └── kanjidic2_min.xml      # copia del fixture de diccionario/
    ├── test_japones.py
    ├── test_aozora.py
    ├── test_segmentador.py
    ├── test_genera_jlpt.py
    ├── test_dificultad.py
    ├── test_emisor.py
    ├── test_verify.py
    └── test_pipeline.py

catalogo/                   # OUTPUT commiteado (raíz del repo)
├── catalogo.json
└── historias/
    ├── momotaro.json
    ├── urashima_taro.json
    ├── issunboshi.json
    └── kachikachi_yama.json
```

---

### Task 1: Scaffolding + helpers de japonés

**Files:**
- Create: `historias/.gitignore`, `historias/src/__init__.py`, `historias/tests/__init__.py`
- Create: `historias/src/japones.py`
- Test: `historias/tests/test_japones.py`

**Interfaces:**
- Produces: `japones.es_kanji(c: str) -> bool`; `japones.es_base_ruby(c: str) -> bool` (kanji o `々〆ヵヶ`); `japones.extraer_kanjis(texto: str) -> list` (únicos, en orden). Duplicado deliberado de `diccionario/src/japones.py` — los subsistemas son autocontenidos (este se porta a Kotlin en Plan 3).

- [ ] **Step 1: Crear estructura y .gitignore**

```bash
mkdir -p historias/src historias/tests/fixtures
touch historias/src/__init__.py historias/tests/__init__.py
```

`historias/.gitignore`:
```
fuentes/
__pycache__/
```

- [ ] **Step 2: Test que falla**

`historias/tests/test_japones.py`:
```python
import unittest

from src import japones


class TestJapones(unittest.TestCase):
    def test_es_kanji(self):
        self.assertTrue(japones.es_kanji('語'))
        self.assertFalse(japones.es_kanji('た'))   # hiragana
        self.assertFalse(japones.es_kanji('タ'))   # katakana
        self.assertFalse(japones.es_kanji('。'))
        self.assertFalse(japones.es_kanji('々'))   # marca de repetición

    def test_es_base_ruby(self):
        # la base implícita de un ruby incluye kanji y marcas como 々
        self.assertTrue(japones.es_base_ruby('語'))
        self.assertTrue(japones.es_base_ruby('々'))
        self.assertFalse(japones.es_base_ruby('た'))
        self.assertFalse(japones.es_base_ruby('。'))

    def test_extraer_kanjis_unicos_en_orden(self):
        self.assertEqual(japones.extraer_kanjis('物語は物語です。'), ['物', '語'])

    def test_extraer_kanjis_sin_kanji(self):
        self.assertEqual(japones.extraer_kanjis('ひらがなだけ'), [])


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 3: Correr — falla**

Run: `cd historias && python -m unittest tests.test_japones -v`
Expected: ERROR — `No module named 'src.japones'`.

- [ ] **Step 4: Implementar**

`historias/src/japones.py`:
```python
"""Helpers de caracteres japoneses (duplicado autocontenido de diccionario/)."""

# caracteres que pueden integrar la base de un ruby además de kanji
_EXTRAS_BASE = '々〆ヵヶ'


def es_kanji(caracter: str) -> bool:
    """CJK Unified Ideographs (U+4E00–U+9FFF)."""
    return '一' <= caracter <= '鿿'


def es_base_ruby(caracter: str) -> bool:
    """Caracteres que forman la base implícita de un ruby 《》."""
    return es_kanji(caracter) or caracter in _EXTRAS_BASE


def extraer_kanjis(texto: str) -> list:
    """Kanjis únicos en orden de aparición."""
    vistos = set()
    resultado = []
    for c in texto:
        if es_kanji(c) and c not in vistos:
            vistos.add(c)
            resultado.append(c)
    return resultado
```

- [ ] **Step 5: Correr — pasa** (`OK`, 4 tests)

- [ ] **Step 6: Commit**

```bash
git add historias/
git commit -m "feat(historias): scaffolding + helpers de japonés"
```

---

### Task 2: aozora.limpiar_linea — ruby y anotaciones

**Files:**
- Create: `historias/src/aozora.py`
- Test: `historias/tests/test_aozora.py`

**Interfaces:**
- Consumes: `japones.es_base_ruby` (Task 1).
- Produces: `aozora.limpiar_linea(linea: str) -> tuple` — `(texto_limpio: str, furigana: list)` donde `furigana` = lista de `[inicio, fin, lectura]`, **fin exclusivo**, índices sobre `texto_limpio`.

**Contexto de formato Aozora (.txt ruby):**
- Ruby: `漢字《かんじ》` — la base es la corrida de kanji (y `々〆ヵヶ`) inmediatamente anterior a `《`. Con `｜` la base se marca explícita: `｜お土産《おみやげ》` (permite base con kana).
- Anotaciones de entrada: `［＃...］` (sangrías, 傍点, notas) — se eliminan enteras.
- Sangría de párrafo: espacio full-width `　` inicial — se elimina.

- [ ] **Step 1: Test que falla**

`historias/tests/test_aozora.py`:
```python
import unittest

from src import aozora


class TestLimpiarLinea(unittest.TestCase):
    def test_ruby_kanji_simple(self):
        texto, furigana = aozora.limpiar_linea('しば刈《か》りに行く')
        self.assertEqual(texto, 'しば刈りに行く')
        self.assertEqual(furigana, [[2, 3, 'か']])

    def test_ruby_multi_kanji(self):
        texto, furigana = aozora.limpiar_linea('洗濯《せんたく》に')
        self.assertEqual(texto, '洗濯に')
        self.assertEqual(furigana, [[0, 2, 'せんたく']])

    def test_ruby_con_marca_de_repeticion(self):
        texto, furigana = aozora.limpiar_linea('昔々《むかしむかし》、')
        self.assertEqual(texto, '昔々、')
        self.assertEqual(furigana, [[0, 2, 'むかしむかし']])

    def test_barra_marca_base_explicita(self):
        # sin ｜ la base sería solo 土産; con ｜ incluye el お
        texto, furigana = aozora.limpiar_linea('これは｜お土産《おみやげ》だ')
        self.assertEqual(texto, 'これはお土産だ')
        self.assertEqual(furigana, [[3, 6, 'おみやげ']])

    def test_multiples_rubies_en_una_linea(self):
        texto, furigana = aozora.limpiar_linea('山《やま》と川《かわ》')
        self.assertEqual(texto, '山と川')
        self.assertEqual(furigana, [[0, 1, 'やま'], [2, 3, 'かわ']])

    def test_anotacion_se_elimina(self):
        texto, furigana = aozora.limpiar_linea(
            '［＃５字下げ］一［＃「一」は中見出し］')
        self.assertEqual(texto, '一')
        self.assertEqual(furigana, [])

    def test_sangria_fullwidth_se_elimina(self):
        texto, _ = aozora.limpiar_linea('　むかし、むかし。')
        self.assertEqual(texto, 'むかし、むかし。')

    def test_apertura_sin_cierre_queda_literal(self):
        texto, furigana = aozora.limpiar_linea('壊れた《ルビ')
        self.assertEqual(texto, '壊れた《ルビ')
        self.assertEqual(furigana, [])

    def test_ruby_sin_base_se_ignora(self):
        texto, furigana = aozora.limpiar_linea('《よみ》')
        self.assertEqual(texto, '')
        self.assertEqual(furigana, [])


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 2: Correr — falla**

Run: `cd historias && python -m unittest tests.test_aozora -v`
Expected: ERROR — `No module named 'src.aozora'`.

- [ ] **Step 3: Implementar**

`historias/src/aozora.py`:
```python
"""Limpieza de marcado Aozora Bunko y extracción de furigana (ruby)."""
import re

from . import japones

_ANOTACION = re.compile(r'［＃.*?］')


def limpiar_linea(linea: str) -> tuple:
    """Una línea del cuerpo → (texto_limpio, furigana).

    furigana = [[inicio, fin, lectura], ...] con fin exclusivo,
    índices sobre texto_limpio.
    """
    linea = _ANOTACION.sub('', linea)
    linea = linea.strip().lstrip('　')  # sangría Aozora
    texto = []
    furigana = []
    inicio_marcado = None  # posición fijada por ｜
    i = 0
    while i < len(linea):
        c = linea[i]
        if c == '｜':
            inicio_marcado = len(texto)
            i += 1
        elif c == '《':
            cierre = linea.find('》', i + 1)
            if cierre == -1:
                texto.append(c)  # 《 sin cerrar: literal
                i += 1
                continue
            lectura = linea[i + 1:cierre]
            if inicio_marcado is not None:
                inicio = inicio_marcado
            else:
                inicio = len(texto)
                while inicio > 0 and japones.es_base_ruby(texto[inicio - 1]):
                    inicio -= 1
            if lectura and inicio < len(texto):
                furigana.append([inicio, len(texto), lectura])
            inicio_marcado = None
            i = cierre + 1
        else:
            texto.append(c)
            i += 1
    return ''.join(texto), furigana
```

- [ ] **Step 4: Correr — pasa** (`OK`, 9 tests)

- [ ] **Step 5: Commit**

```bash
git add historias/
git commit -m "feat(historias): extracción de ruby y limpieza de anotaciones Aozora"
```

---

### Task 3: aozora.extraer_cuerpo + parsear — obra completa

**Files:**
- Create: `historias/tests/fixtures/fragmento_aozora.txt`
- Modify: `historias/src/aozora.py` (agregar funciones al final)
- Test: `historias/tests/test_aozora.py` (agregar clases)

**Interfaces:**
- Produces: `aozora.extraer_cuerpo(texto_completo: str) -> tuple` — `(titulo: str, autor: str, lineas_cuerpo: list)`; `aozora.parsear(texto_completo: str) -> dict` — `{'titulo': str, 'autor': str, 'parrafos': [(texto, furigana), ...]}` (un párrafo por línea no vacía del cuerpo). Lanza `ValueError` si el archivo no parece una obra de Aozora (fallar ruidoso).

**Contexto de formato:** línea 1 = título, línea 2 = autor; bloque de notación delimitado por dos líneas de guiones ASCII (`----…`); colofón desde la línea que empieza con `底本：`.

- [ ] **Step 1: Crear fixture** (UTF-8; la decodificación cp932 se testea en Task 9)

`historias/tests/fixtures/fragmento_aozora.txt`:
```
桃太郎
楠山正雄

-------------------------------------------------------
【テキスト中に現れる記号について】

《》：ルビ
（例）昔々《むかしむかし》

｜：ルビの付く文字列の始まりを特定する記号
（例）｜お土産《おみやげ》

［＃］：入力者注　主に外字の説明や、傍点の位置の指定
（例）［＃「ばば」に傍点］
-------------------------------------------------------

　むかし、むかし、あるところに、おじいさんとおばあさんがありました。まいにち、おじいさんは山へしば刈《か》りに、おばあさんは川へ洗濯《せんたく》に行きました。
　「おや、これは｜お土産《おみやげ》に結構《けっこう》だね。」と、おじいさんは言いました。

底本：「日本の神話と十大昔話」講談社学術文庫、講談社
```

- [ ] **Step 2: Agregar tests que fallan**

Agregar a `historias/tests/test_aozora.py` (arriba: `import os`; abajo de `TestLimpiarLinea`):
```python
FIXTURE = os.path.join(os.path.dirname(__file__), 'fixtures',
                       'fragmento_aozora.txt')


class TestParsear(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(FIXTURE, encoding='utf-8') as f:
            cls.obra = aozora.parsear(f.read())

    def test_titulo_y_autor(self):
        self.assertEqual(self.obra['titulo'], '桃太郎')
        self.assertEqual(self.obra['autor'], '楠山正雄')

    def test_solo_el_cuerpo(self):
        # el bloque de notación y el colofón quedan afuera
        self.assertEqual(len(self.obra['parrafos']), 2)
        for texto, _ in self.obra['parrafos']:
            self.assertNotIn('底本', texto)
            self.assertNotIn('《', texto)
            self.assertNotIn('｜', texto)

    def test_furigana_alineada(self):
        texto, furigana = self.obra['parrafos'][0]
        inicio = texto.index('刈')
        self.assertIn([inicio, inicio + 1, 'か'], furigana)
        inicio = texto.index('洗濯')
        self.assertIn([inicio, inicio + 2, 'せんたく'], furigana)

    def test_archivo_invalido_falla_ruidoso(self):
        with self.assertRaises(ValueError):
            aozora.parsear('una línea\n')
```

- [ ] **Step 3: Correr — falla**

Run: `cd historias && python -m unittest tests.test_aozora -v`
Expected: ERROR — `module 'src.aozora' has no attribute 'parsear'`.

- [ ] **Step 4: Implementar**

Agregar al final de `historias/src/aozora.py` (y arriba, junto a `_ANOTACION`, el delimitador):
```python
_DELIMITADOR = re.compile(r'^-{10,}\s*$')
```

```python
def extraer_cuerpo(texto_completo: str) -> tuple:
    """Un .txt de Aozora → (titulo, autor, lineas_cuerpo).

    Título en línea 1, autor en línea 2; el cuerpo arranca tras el segundo
    delimitador de guiones (si existe) y termina antes del colofón (底本：).
    """
    lineas = texto_completo.splitlines()
    if len(lineas) < 3:
        raise ValueError('archivo demasiado corto para ser una obra de Aozora')
    titulo = lineas[0].strip()
    autor = lineas[1].strip()

    delimitadores = [i for i, l in enumerate(lineas) if _DELIMITADOR.match(l)]
    inicio = delimitadores[1] + 1 if len(delimitadores) >= 2 else 2

    fin = len(lineas)
    for i in range(inicio, len(lineas)):
        if lineas[i].startswith('底本：') or lineas[i].startswith('底本:'):
            fin = i
            break
    return titulo, autor, lineas[inicio:fin]


def parsear(texto_completo: str) -> dict:
    """Obra completa → {titulo, autor, parrafos: [(texto, furigana)]}."""
    titulo, autor, lineas = extraer_cuerpo(texto_completo)
    parrafos = []
    for linea in lineas:
        texto, furigana = limpiar_linea(linea)
        if texto:
            parrafos.append((texto, furigana))
    if not parrafos:
        raise ValueError(f'"{titulo}": cuerpo vacío tras la limpieza')
    return {'titulo': titulo, 'autor': autor, 'parrafos': parrafos}
```

- [ ] **Step 5: Correr — pasa** (`OK`, 13 tests en test_aozora)

- [ ] **Step 6: Commit**

```bash
git add historias/
git commit -m "feat(historias): parseo de obra Aozora completa con fixture real"
```

---

### Task 4: Segmentador de oraciones

**Files:**
- Create: `historias/src/segmentador.py`
- Test: `historias/tests/test_segmentador.py`

**Interfaces:**
- Produces: `segmentador.segmentar(texto: str) -> list` (spans `(inicio, fin)` exclusivos); `segmentador.segmentar_parrafo(texto: str, furigana: list) -> list` de `(texto_oracion, furigana_oracion)` con índices reajustados a cada oración.
- Nota: este algoritmo se porta a Kotlin en Plan 3 — sin dependencias de otros módulos, sin regex.

- [ ] **Step 1: Test que falla**

`historias/tests/test_segmentador.py`:
```python
import unittest

from src import segmentador


def _textos(texto):
    return [texto[i:f] for i, f in segmentador.segmentar(texto)]


class TestSegmentar(unittest.TestCase):
    def test_split_por_punto(self):
        self.assertEqual(
            _textos('むかしがありました。まいにち行きました。'),
            ['むかしがありました。', 'まいにち行きました。'])

    def test_exclamacion_e_interrogacion(self):
        self.assertEqual(
            _textos('来た！どこ？帰る。'),
            ['来た！', 'どこ？', '帰る。'])

    def test_dialogo_multi_oracion_no_se_parte(self):
        # el 。 dentro de 「」 no corta: la cita entera + coda = 1 oración
        self.assertEqual(
            _textos('「おや。これは。」と言いました。'),
            ['「おや。これは。」と言いました。'])

    def test_resto_sin_puntuacion_final(self):
        self.assertEqual(_textos('一'), ['一'])

    def test_texto_vacio(self):
        self.assertEqual(segmentador.segmentar(''), [])


class TestSegmentarParrafo(unittest.TestCase):
    def test_furigana_se_reindexa_por_oracion(self):
        texto = 'しば刈りに行く。洗濯する。'
        furigana = [[2, 3, 'か'], [8, 10, 'せんたく']]
        oraciones = segmentador.segmentar_parrafo(texto, furigana)
        self.assertEqual(oraciones, [
            ('しば刈りに行く。', [[2, 3, 'か']]),
            ('洗濯する。', [[0, 2, 'せんたく']]),
        ])


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 2: Correr — falla**

Run: `cd historias && python -m unittest tests.test_segmentador -v`

- [ ] **Step 3: Implementar**

`historias/src/segmentador.py`:
```python
"""Segmentación de párrafos en oraciones, preservando furigana alineada.

Este algoritmo se porta a Kotlin en Plan 3 — mantener simple.
"""

FIN_ORACION = '。！？'
APERTURA = '「『（'
CIERRE = '」』）'


def segmentar(texto: str) -> list:
    """Spans [inicio, fin) de cada oración.

    Corta en 。！？ solo fuera de comillas/paréntesis: el diálogo
    「...。...。」 queda como una sola oración.
    """
    spans = []
    inicio = 0
    profundidad = 0
    for i, c in enumerate(texto):
        if c in APERTURA:
            profundidad += 1
        elif c in CIERRE:
            profundidad = max(0, profundidad - 1)
        elif c in FIN_ORACION and profundidad == 0:
            spans.append((inicio, i + 1))
            inicio = i + 1
    if texto[inicio:].strip():
        spans.append((inicio, len(texto)))
    return spans


def segmentar_parrafo(texto: str, furigana: list) -> list:
    """[(texto_oracion, furigana_oracion)] con índices reajustados."""
    oraciones = []
    for inicio, fin in segmentar(texto):
        f_oracion = [
            [f_ini - inicio, f_fin - inicio, lectura]
            for f_ini, f_fin, lectura in furigana
            if f_ini >= inicio and f_fin <= fin
        ]
        oraciones.append((texto[inicio:fin], f_oracion))
    return oraciones
```

- [ ] **Step 4: Correr — pasa** (`OK`, 6 tests)

- [ ] **Step 5: Commit**

```bash
git add historias/
git commit -m "feat(historias): segmentador de oraciones con comillas y furigana"
```

---

### Task 5: genera_jlpt.py + src/jlpt.py generado

**Files:**
- Create: `historias/genera_jlpt.py`
- Create: `historias/tests/fixtures/kanjidic2_min.xml` (copia de `diccionario/tests/fixtures/kanjidic2_min.xml`)
- Create (generado): `historias/src/jlpt.py`
- Test: `historias/tests/test_genera_jlpt.py`

**Interfaces:**
- Produces: `genera_jlpt.extraer_n5_n4(ruta_kanjidic: str) -> list` (kanjis con `jlpt` 4 o 3, ordenados); `genera_jlpt.generar(ruta_kanjidic: str, ruta_salida: str) -> int` (cantidad escrita). Módulo generado: `jlpt.KANJI_N5_N4: frozenset`.
- Contexto: KANJIDIC2 usa la escala vieja 1-4; nivel 4 ≈ N5, nivel 3 ≈ N4 (~284 kanjis en total). El módulo generado se commitea; se regenera solo si cambia KANJIDIC2.

- [ ] **Step 1: Copiar fixture**

```bash
cp diccionario/tests/fixtures/kanjidic2_min.xml historias/tests/fixtures/
```
(El fixture tiene 語 con `<jlpt>4</jlpt>` y 物 sin jlpt.)

- [ ] **Step 2: Test que falla**

`historias/tests/test_genera_jlpt.py`:
```python
import os
import tempfile
import unittest

import genera_jlpt

FIXTURE = os.path.join(os.path.dirname(__file__), 'fixtures',
                       'kanjidic2_min.xml')


class TestGeneraJlpt(unittest.TestCase):
    def test_extraer_n5_n4(self):
        # 語 tiene jlpt 4 (≈N5); 物 no tiene jlpt → queda afuera
        self.assertEqual(genera_jlpt.extraer_n5_n4(FIXTURE), ['語'])

    def test_modulo_generado_es_importable(self):
        with tempfile.TemporaryDirectory() as tmp:
            ruta = os.path.join(tmp, 'jlpt.py')
            n = genera_jlpt.generar(FIXTURE, ruta)
            self.assertEqual(n, 1)
            ns = {}
            with open(ruta, encoding='utf-8') as f:
                exec(f.read(), ns)
            self.assertIn('語', ns['KANJI_N5_N4'])
            self.assertNotIn('物', ns['KANJI_N5_N4'])


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 3: Correr — falla**

Run: `cd historias && python -m unittest tests.test_genera_jlpt -v`

- [ ] **Step 4: Implementar**

`historias/genera_jlpt.py`:
```python
"""Genera src/jlpt.py con el set de kanjis JLPT N5-N4 desde KANJIDIC2.

KANJIDIC2 usa la escala vieja 1-4: nivel 4 ≈ N5, nivel 3 ≈ N4.
Uso: python genera_jlpt.py [--kanjidic ../diccionario/fuentes/kanjidic2.xml]
El módulo generado se commitea; regenerar solo si cambia KANJIDIC2.
"""
import argparse
import xml.etree.ElementTree as ET

PLANTILLA = '''"""Kanjis JLPT N5-N4 (niveles 4 y 3 de la escala vieja de KANJIDIC2).

Generado por genera_jlpt.py — no editar a mano.
"""

KANJI_N5_N4 = frozenset(
    '{kanjis}'
)
'''


def extraer_n5_n4(ruta_kanjidic: str) -> list:
    """Kanjis con jlpt 4 o 3, ordenados (output determinístico)."""
    kanjis = []
    # iterparse: el archivo real pesa ~15 MB
    for _, elem in ET.iterparse(ruta_kanjidic, events=('end',)):
        if elem.tag != 'character':
            continue
        if elem.findtext('misc/jlpt') in ('4', '3'):
            kanjis.append(elem.findtext('literal'))
        elem.clear()
    return sorted(kanjis)


def generar(ruta_kanjidic: str, ruta_salida: str) -> int:
    kanjis = extraer_n5_n4(ruta_kanjidic)
    with open(ruta_salida, 'w', encoding='utf-8') as f:
        f.write(PLANTILLA.format(kanjis=''.join(kanjis)))
    return len(kanjis)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kanjidic',
                        default='../diccionario/fuentes/kanjidic2.xml')
    parser.add_argument('--salida', default='src/jlpt.py')
    args = parser.parse_args()
    n = generar(args.kanjidic, args.salida)
    print(f'✓ {args.salida}: {n} kanjis N5-N4')


if __name__ == '__main__':
    main()
```

- [ ] **Step 5: Correr — pasa** (`OK`, 2 tests)

- [ ] **Step 6: Generar src/jlpt.py real**

Requiere `diccionario/fuentes/kanjidic2.xml` (quedó de Plan 1). Si falta:
```bash
cd diccionario && mkdir -p fuentes && cd fuentes && \
  wget http://www.edrdg.org/kanjidic/kanjidic2.xml.gz && gunzip kanjidic2.xml.gz
```

Run: `cd historias && python genera_jlpt.py`
Expected: `✓ src/jlpt.py: ~284 kanjis N5-N4` (aceptable 250-350; fuera de eso, investigar antes de commitear).

Sanity: `cd historias && python -c "from src.jlpt import KANJI_N5_N4; print(len(KANJI_N5_N4), '日' in KANJI_N5_N4)"` → `~284 True`.

- [ ] **Step 7: Commit** (incluye el módulo generado)

```bash
git add historias/
git commit -m "feat(historias): generador de set JLPT N5-N4 + módulo generado"
```

---

### Task 6: Estimador de dificultad

**Files:**
- Create: `historias/src/dificultad.py`
- Test: `historias/tests/test_dificultad.py`

**Interfaces:**
- Consumes: `japones.es_kanji` (Task 1), `jlpt.KANJI_N5_N4` (Task 5).
- Produces: `dificultad.metricas(oraciones: list, kanji_conocidos=None) -> dict` (`{'pct_fuera': float, 'largo_promedio': float}`); `dificultad.calcular(oraciones: list, kanji_conocidos=None) -> str` (`'facil' | 'media' | 'dificil'`). `oraciones` = lista de textos (strings). `kanji_conocidos` inyectable para tests; default `KANJI_N5_N4`.

- [ ] **Step 1: Test que falla**

`historias/tests/test_dificultad.py`:
```python
import unittest

from src import dificultad


class TestDificultad(unittest.TestCase):
    def test_facil_kanji_conocidos_y_oraciones_cortas(self):
        self.assertEqual(
            dificultad.calcular(['山へ行く。'], kanji_conocidos={'山', '行'}),
            'facil')

    def test_dificil_por_kanji_desconocidos(self):
        # 2 de 3 kanjis fuera del set → pct 0.67 ≥ 0.45
        self.assertEqual(
            dificultad.calcular(['鬱蒼たる森。'], kanji_conocidos={'森'}),
            'dificil')

    def test_dificil_por_oraciones_largas(self):
        larga = 'あ' * 60 + '。'
        self.assertEqual(
            dificultad.calcular([larga], kanji_conocidos=set()), 'dificil')

    def test_media(self):
        # pct 1/3 ≈ 0.33: entre 0.25 y 0.45
        self.assertEqual(
            dificultad.calcular(['山山森。'], kanji_conocidos={'山'}), 'media')

    def test_sin_kanji_es_facil(self):
        self.assertEqual(
            dificultad.calcular(['ひらがなだけ。'], kanji_conocidos=set()),
            'facil')

    def test_metricas(self):
        m = dificultad.metricas(['山山森。'], kanji_conocidos={'山'})
        self.assertAlmostEqual(m['pct_fuera'], 1 / 3)
        self.assertAlmostEqual(m['largo_promedio'], 4.0)


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 2: Correr — falla**

Run: `cd historias && python -m unittest tests.test_dificultad -v`

- [ ] **Step 3: Implementar**

`historias/src/dificultad.py`:
```python
"""Estimación de dificultad de una historia: facil / media / dificil.

Métrica del spec: % de ocurrencias de kanji fuera de JLPT N5-N4
+ largo promedio de oración.
"""
from . import japones
from .jlpt import KANJI_N5_N4

# umbrales iniciales; ajustar con los números reales del catálogo (Task 10)
MAX_PCT_FACIL = 0.25
MAX_LARGO_FACIL = 35
MIN_PCT_DIFICIL = 0.45
MIN_LARGO_DIFICIL = 55


def metricas(oraciones: list, kanji_conocidos=None) -> dict:
    """oraciones = textos de oración. kanji_conocidos inyectable en tests."""
    if kanji_conocidos is None:
        kanji_conocidos = KANJI_N5_N4
    total = fuera = 0
    for texto in oraciones:
        for c in texto:
            if japones.es_kanji(c):
                total += 1
                if c not in kanji_conocidos:
                    fuera += 1
    return {
        'pct_fuera': fuera / total if total else 0.0,
        'largo_promedio': (sum(len(t) for t in oraciones) / len(oraciones)
                           if oraciones else 0.0),
    }


def calcular(oraciones: list, kanji_conocidos=None) -> str:
    m = metricas(oraciones, kanji_conocidos)
    if (m['pct_fuera'] >= MIN_PCT_DIFICIL
            or m['largo_promedio'] >= MIN_LARGO_DIFICIL):
        return 'dificil'
    if (m['pct_fuera'] <= MAX_PCT_FACIL
            and m['largo_promedio'] <= MAX_LARGO_FACIL):
        return 'facil'
    return 'media'
```

- [ ] **Step 4: Correr — pasa** (`OK`, 6 tests)

- [ ] **Step 5: Commit**

```bash
git add historias/
git commit -m "feat(historias): estimador de dificultad por JLPT y largo de oración"
```

---

### Task 7: Emisor de JSON + catálogo

**Files:**
- Create: `historias/src/emisor.py`
- Test: `historias/tests/test_emisor.py`

**Interfaces:**
- Produces: `emisor.construir_historia(id_, titulo, autor, fuente, licencia, dificultad, parrafos) -> dict` donde `parrafos` = lista de párrafos, cada uno lista de `(texto, furigana)` (output de `segmentador.segmentar_parrafo`); `emisor.emitir(historias: list, dir_catalogo: str) -> dict` (escribe `historias/<id>.json` + `catalogo.json`, devuelve stats `{'historias': int}`); `emisor.VERSION_CATALOGO = 1`.
- Escritura atómica: tmp + `os.replace`. JSON `ensure_ascii=False, indent=1`.

- [ ] **Step 1: Test que falla**

`historias/tests/test_emisor.py`:
```python
import json
import os
import shutil
import tempfile
import unittest

from src import emisor


def _historia():
    return emisor.construir_historia(
        id_='momotaro', titulo='桃太郎', autor='楠山正雄',
        fuente='aozora:18376', licencia='dominio público',
        dificultad='facil',
        parrafos=[[('むかし、むかし。', []),
                   ('しば刈りに。', [[2, 3, 'か']])]])


class TestEmisor(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)

    def test_estructura_del_spec(self):
        historia = _historia()
        self.assertEqual(historia['id'], 'momotaro')
        self.assertEqual(historia['version'], 1)
        oracion = historia['parrafos'][0]['oraciones'][1]
        self.assertEqual(oracion['texto'], 'しば刈りに。')
        self.assertEqual(oracion['furigana'], [[2, 3, 'か']])
        self.assertIsNone(oracion['traduccion'])

    def test_emitir_escribe_historia_y_catalogo(self):
        emisor.emitir([_historia()], self.tmp)
        ruta = os.path.join(self.tmp, 'historias', 'momotaro.json')
        with open(os.path.join(self.tmp, 'catalogo.json'),
                  encoding='utf-8') as f:
            catalogo = json.load(f)
        self.assertEqual(catalogo['version'], emisor.VERSION_CATALOGO)
        entrada = catalogo['historias'][0]
        self.assertEqual(entrada['id'], 'momotaro')
        self.assertEqual(entrada['titulo'], '桃太郎')
        self.assertEqual(entrada['dificultad'], 'facil')
        self.assertEqual(entrada['version'], 1)
        self.assertEqual(entrada['tamaño'], os.path.getsize(ruta))

    def test_sin_archivos_temporales_residuales(self):
        emisor.emitir([_historia()], self.tmp)
        for raiz, _, archivos in os.walk(self.tmp):
            for nombre in archivos:
                self.assertFalse(nombre.endswith('.tmp'),
                                 f'residuo: {raiz}/{nombre}')

    def test_json_sin_escapes_ascii(self):
        emisor.emitir([_historia()], self.tmp)
        ruta = os.path.join(self.tmp, 'historias', 'momotaro.json')
        with open(ruta, encoding='utf-8') as f:
            self.assertIn('桃太郎', f.read())  # ensure_ascii=False


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 2: Correr — falla**

Run: `cd historias && python -m unittest tests.test_emisor -v`

- [ ] **Step 3: Implementar**

`historias/src/emisor.py`:
```python
"""Emisión de los JSON de historias y del catálogo. Escritura atómica."""
import json
import os

VERSION_CATALOGO = 1


def construir_historia(id_, titulo, autor, fuente, licencia,
                       dificultad, parrafos) -> dict:
    """parrafos = [[(texto, furigana), ...] por párrafo] → dict del spec."""
    return {
        'id': id_,
        'titulo': titulo,
        'autor': autor,
        'fuente': fuente,
        'licencia': licencia,
        'dificultad': dificultad,
        'version': 1,
        'parrafos': [
            {'oraciones': [
                {'texto': texto, 'furigana': furigana, 'traduccion': None}
                for texto, furigana in oraciones
            ]}
            for oraciones in parrafos
        ],
    }


def _escribir_json(ruta: str, datos) -> None:
    """Atómico: tmp + replace. Nunca deja un JSON a medias."""
    tmp = ruta + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(datos, f, ensure_ascii=False, indent=1)
        f.write('\n')
    os.replace(tmp, ruta)


def emitir(historias: list, dir_catalogo: str) -> dict:
    """Escribe historias/<id>.json + catalogo.json. Devuelve stats."""
    dir_historias = os.path.join(dir_catalogo, 'historias')
    os.makedirs(dir_historias, exist_ok=True)
    entradas = []
    for historia in historias:
        ruta = os.path.join(dir_historias, f"{historia['id']}.json")
        _escribir_json(ruta, historia)
        entradas.append({
            'id': historia['id'],
            'titulo': historia['titulo'],
            'autor': historia['autor'],
            'dificultad': historia['dificultad'],
            'tamaño': os.path.getsize(ruta),
            'version': historia['version'],
        })
    _escribir_json(os.path.join(dir_catalogo, 'catalogo.json'),
                   {'version': VERSION_CATALOGO, 'historias': entradas})
    return {'historias': len(entradas)}
```

- [ ] **Step 4: Correr — pasa** (`OK`, 4 tests)

- [ ] **Step 5: Commit**

```bash
git add historias/
git commit -m "feat(historias): emisor de JSON de historias y catálogo, atómico"
```

---

### Task 8: verify_catalogo.py

**Files:**
- Create: `historias/verify_catalogo.py`
- Test: `historias/tests/test_verify.py`

**Interfaces:**
- Consumes: catálogo emitido por `emisor.emitir` (Task 7).
- Produces: `verify_catalogo.verificar(dir_catalogo: str) -> list` (strings de error; vacía = OK). CLI: `python verify_catalogo.py [dir]` (default `../catalogo`, exit 1 con `✗` si hay errores, `✓ OK` si no) — patrón de `diccionario/verify_db.py`.

- [ ] **Step 1: Test que falla**

`historias/tests/test_verify.py`:
```python
import json
import os
import shutil
import tempfile
import unittest

import verify_catalogo
from src import emisor


def _historia_valida():
    return emisor.construir_historia(
        id_='momotaro', titulo='桃太郎', autor='楠山正雄',
        fuente='aozora:18376', licencia='dominio público',
        dificultad='facil',
        parrafos=[[('むかし、むかし。', []),
                   ('しば刈りに。', [[2, 3, 'か']])]])


class TestVerify(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)
        emisor.emitir([_historia_valida()], self.tmp)

    def _corromper(self, mutador):
        """Reescribe momotaro.json mutado (además desactualiza el tamaño)."""
        ruta = os.path.join(self.tmp, 'historias', 'momotaro.json')
        with open(ruta, encoding='utf-8') as f:
            historia = json.load(f)
        mutador(historia)
        with open(ruta, 'w', encoding='utf-8') as f:
            json.dump(historia, f, ensure_ascii=False)

    def test_catalogo_valido_sin_errores(self):
        self.assertEqual(verify_catalogo.verificar(self.tmp), [])

    def test_detecta_marcado_residual(self):
        self._corromper(lambda h: h['parrafos'][0]['oraciones'][0]
                        .update(texto='むかし《むかし》。'))
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('residual' in e for e in errores))

    def test_detecta_furigana_fuera_de_rango(self):
        self._corromper(lambda h: h['parrafos'][0]['oraciones'][1]
                        .update(furigana=[[2, 99, 'か']]))
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('furigana' in e for e in errores))

    def test_detecta_tamano_desactualizado(self):
        self._corromper(lambda h: None)  # reescritura compacta cambia bytes
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('tamaño' in e for e in errores))

    def test_detecta_historia_faltante(self):
        os.unlink(os.path.join(self.tmp, 'historias', 'momotaro.json'))
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('momotaro' in e for e in errores))


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 2: Correr — falla**

Run: `cd historias && python -m unittest tests.test_verify -v`

- [ ] **Step 3: Implementar**

`historias/verify_catalogo.py`:
```python
"""Verificación integral del catálogo emitido.

Uso: python verify_catalogo.py [dir_catalogo]   (default: ../catalogo)
Exit 0 si todo OK, exit 1 si hay errores.
"""
import json
import os
import sys

DIFICULTADES = {'facil', 'media', 'dificil'}
CLAVES_CATALOGO = {'id', 'titulo', 'autor', 'dificultad', 'tamaño', 'version'}
CLAVES_HISTORIA = {'id', 'titulo', 'autor', 'fuente', 'licencia',
                   'dificultad', 'version', 'parrafos'}
# ※ queda si hubo gaiji ［＃...］ sin resolver: también es residuo
MARCADO_RESIDUAL = ('《', '》', '｜', '［＃', '※')


def _verificar_historia(ruta: str, id_: str) -> list:
    errores = []
    with open(ruta, encoding='utf-8') as f:
        historia = json.load(f)
    faltantes = CLAVES_HISTORIA - set(historia)
    if faltantes:
        return [f'{id_}: faltan claves {sorted(faltantes)}']
    if historia['id'] != id_:
        errores.append(f"{id_}: id interno {historia['id']!r} no coincide")
    if historia['dificultad'] not in DIFICULTADES:
        errores.append(f"{id_}: dificultad {historia['dificultad']!r} inválida")
    if not historia['parrafos']:
        errores.append(f'{id_}: sin párrafos')
    for p, parrafo in enumerate(historia['parrafos']):
        if not parrafo.get('oraciones'):
            errores.append(f'{id_} p{p}: párrafo sin oraciones')
        for o, oracion in enumerate(parrafo.get('oraciones', [])):
            donde = f'{id_} p{p} o{o}'
            texto = oracion.get('texto', '')
            if not texto:
                errores.append(f'{donde}: texto vacío')
                continue
            if any(m in texto for m in MARCADO_RESIDUAL):
                errores.append(
                    f'{donde}: marcado Aozora residual: {texto[:30]}')
            if oracion.get('traduccion') is not None:
                errores.append(f'{donde}: traduccion debe ser null en v1')
            for furi in oracion.get('furigana', []):
                inicio, fin, lectura = furi
                if not (0 <= inicio < fin <= len(texto)) or not lectura:
                    errores.append(f'{donde}: furigana inválida {furi}')
    return errores


def verificar(dir_catalogo: str) -> list:
    errores = []
    ruta_catalogo = os.path.join(dir_catalogo, 'catalogo.json')
    if not os.path.exists(ruta_catalogo):
        return [f'no existe {ruta_catalogo}']
    with open(ruta_catalogo, encoding='utf-8') as f:
        catalogo = json.load(f)
    entradas = catalogo.get('historias', [])
    if not entradas:
        errores.append('catálogo sin historias')
    for entrada in entradas:
        faltantes = CLAVES_CATALOGO - set(entrada)
        if faltantes:
            errores.append(
                f"catálogo {entrada.get('id')}: faltan {sorted(faltantes)}")
            continue
        ruta = os.path.join(dir_catalogo, 'historias',
                            f"{entrada['id']}.json")
        if not os.path.exists(ruta):
            errores.append(f"catálogo: {entrada['id']} sin archivo {ruta}")
            continue
        real = os.path.getsize(ruta)
        print(f"  {entrada['id']}: {real:,} bytes, {entrada['dificultad']}")
        if entrada['tamaño'] != real:
            errores.append(
                f"{entrada['id']}: tamaño {entrada['tamaño']} != real {real}")
        errores.extend(_verificar_historia(ruta, entrada['id']))
    return errores


if __name__ == '__main__':
    dir_catalogo = sys.argv[1] if len(sys.argv) > 1 else '../catalogo'
    print(f'Verificando {dir_catalogo} ...')
    errores = verificar(dir_catalogo)
    if errores:
        print('ERRORES:')
        for e in errores:
            print(f'  ✗ {e}')
        sys.exit(1)
    print('✓ OK')
```

- [ ] **Step 4: Correr — pasa** (`OK`, 5 tests)

- [ ] **Step 5: Commit**

```bash
git add historias/
git commit -m "feat(historias): verify_catalogo con estructura, furigana y residuos"
```

---

### Task 9: pipeline.py CLI + obras.json

**Files:**
- Create: `historias/pipeline.py`
- Create: `historias/obras.json`
- Test: `historias/tests/test_pipeline.py`

**Interfaces:**
- Consumes: `aozora.parsear`, `segmentador.segmentar_parrafo`, `dificultad.calcular`, `emisor.construir_historia`, `emisor.emitir`, `verify_catalogo.verificar` (en el test de integración).
- Produces: `pipeline.leer_fuente(ruta: str) -> str` (decodifica cp932, fallback utf-8); `pipeline.procesar_obra(ruta_txt: str, declaracion: dict) -> dict` (historia lista para emitir). CLI: `python pipeline.py [--fuentes fuentes] [--obras obras.json] [--salida ../catalogo]`. Falla ruidoso: archivo faltante o inválido aborta sin emitir nada.

- [ ] **Step 1: Crear obras.json** (URLs verificadas contra aozora.gr.jp el 2026-07-07; el campo `url` es referencia para la descarga manual, el código no lo usa)

`historias/obras.json`:
```json
[
 {
  "id": "momotaro",
  "archivo": "momotaro.txt",
  "fuente": "aozora:18376",
  "url": "https://www.aozora.gr.jp/cards/000329/files/18376_ruby_12074.zip"
 },
 {
  "id": "urashima_taro",
  "archivo": "urashima_taro.txt",
  "fuente": "aozora:3390",
  "url": "https://www.aozora.gr.jp/cards/000329/files/3390_ruby_6090.zip"
 },
 {
  "id": "issunboshi",
  "archivo": "issunboshi.txt",
  "fuente": "aozora:43457",
  "url": "https://www.aozora.gr.jp/cards/000329/files/43457_ruby_23696.zip"
 },
 {
  "id": "kachikachi_yama",
  "archivo": "kachikachi_yama.txt",
  "fuente": "aozora:18377",
  "url": "https://www.aozora.gr.jp/cards/000329/files/18377_ruby_11923.zip"
 }
]
```

- [ ] **Step 2: Test que falla**

`historias/tests/test_pipeline.py`:
```python
import os
import shutil
import tempfile
import unittest

import pipeline
import verify_catalogo
from src import emisor

FIXTURE = os.path.join(os.path.dirname(__file__), 'fixtures',
                       'fragmento_aozora.txt')


class TestLeerFuente(unittest.TestCase):
    def test_decodifica_shift_jis(self):
        with open(FIXTURE, encoding='utf-8') as f:
            contenido = f.read()
        with tempfile.NamedTemporaryFile('w', suffix='.txt', delete=False,
                                         encoding='cp932') as f:
            f.write(contenido)
            ruta = f.name
        self.addCleanup(os.unlink, ruta)
        self.assertEqual(pipeline.leer_fuente(ruta), contenido)


class TestProcesarObra(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)
        with open(FIXTURE, encoding='utf-8') as f:
            contenido = f.read()
        self.ruta_txt = os.path.join(self.tmp, 'momotaro.txt')
        with open(self.ruta_txt, 'w', encoding='cp932') as f:
            f.write(contenido)

    def test_end_to_end_pasa_verify(self):
        historia = pipeline.procesar_obra(
            self.ruta_txt, {'id': 'momotaro', 'fuente': 'aozora:18376'})
        self.assertEqual(historia['titulo'], '桃太郎')
        self.assertEqual(historia['autor'], '楠山正雄')
        self.assertIn(historia['dificultad'], {'facil', 'media', 'dificil'})
        # el diálogo del fixture queda como una sola oración
        oraciones_p2 = historia['parrafos'][1]['oraciones']
        self.assertEqual(len(oraciones_p2), 1)
        dir_catalogo = os.path.join(self.tmp, 'catalogo')
        emisor.emitir([historia], dir_catalogo)
        self.assertEqual(verify_catalogo.verificar(dir_catalogo), [])

    def test_fuente_inexistente_falla_ruidoso(self):
        with self.assertRaises(FileNotFoundError):
            pipeline.procesar_obra(
                os.path.join(self.tmp, 'nada.txt'),
                {'id': 'x', 'fuente': 'aozora:0'})


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 3: Correr — falla**

Run: `cd historias && python -m unittest tests.test_pipeline -v`

- [ ] **Step 4: Implementar**

`historias/pipeline.py`:
```python
"""CLI: construye el catálogo desde las obras de Aozora en fuentes/.

Uso: python pipeline.py [--fuentes fuentes] [--obras obras.json]
                        [--salida ../catalogo]
Las obras se declaran en obras.json; los .txt se descargan a mano
(ver README.md). Falla ruidoso: cualquier error aborta sin emitir.
"""
import argparse
import json
import os

from src import aozora, dificultad, emisor, segmentador

LICENCIA_DEFAULT = 'dominio público'


def leer_fuente(ruta: str) -> str:
    """Lee un .txt de Aozora (Shift_JIS; algunos archivos nuevos son UTF-8)."""
    with open(ruta, 'rb') as f:
        crudo = f.read()
    try:
        return crudo.decode('cp932')
    except UnicodeDecodeError:
        return crudo.decode('utf-8')


def procesar_obra(ruta_txt: str, declaracion: dict) -> dict:
    """Un .txt de Aozora → dict de historia listo para emitir."""
    obra = aozora.parsear(leer_fuente(ruta_txt))
    parrafos = [
        segmentador.segmentar_parrafo(texto, furigana)
        for texto, furigana in obra['parrafos']
    ]
    textos = [t for oraciones in parrafos for t, _ in oraciones]
    return emisor.construir_historia(
        id_=declaracion['id'],
        titulo=declaracion.get('titulo', obra['titulo']),
        autor=declaracion.get('autor', obra['autor']),
        fuente=declaracion['fuente'],
        licencia=declaracion.get('licencia', LICENCIA_DEFAULT),
        dificultad=dificultad.calcular(textos),
        parrafos=parrafos,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fuentes', default='fuentes')
    parser.add_argument('--obras', default='obras.json')
    parser.add_argument('--salida', default='../catalogo')
    args = parser.parse_args()

    with open(args.obras, encoding='utf-8') as f:
        declaraciones = json.load(f)

    historias = []
    for decl in declaraciones:
        historia = procesar_obra(
            os.path.join(args.fuentes, decl['archivo']), decl)
        n_oraciones = sum(len(p['oraciones'])
                          for p in historia['parrafos'])
        print(f"  {historia['id']}: {len(historia['parrafos'])} párrafos, "
              f"{n_oraciones} oraciones, dificultad {historia['dificultad']}")
        historias.append(historia)

    emisor.emitir(historias, args.salida)
    print(f'✓ {len(historias)} historias emitidas en {args.salida}/')
    print('Correr verify_catalogo.py antes de commitear.')


if __name__ == '__main__':
    main()
```

- [ ] **Step 5: Correr — pasa** (`OK`, 3 tests)

- [ ] **Step 6: Correr TODOS los tests**

Run: `cd historias && python -m unittest discover tests -v`
Expected: `OK` (todos los tests de tasks 1-9).

- [ ] **Step 7: Commit**

```bash
git add historias/
git commit -m "feat(historias): CLI del pipeline + manifest de obras"
```

---

### Task 10: README + build real del catálogo + cierre

**Files:**
- Create: `historias/README.md`
- Create (generados): `catalogo/catalogo.json`, `catalogo/historias/*.json` (4 archivos)
- Modify: `docs/ESTADO.md`

**Interfaces:**
- Consumes: CLI de Task 9 y verify de Task 8.
- Produces: `catalogo/` commiteado, consumible por la app vía `https://raw.githubusercontent.com/T4toh/dokusho-renshuu/main/catalogo/catalogo.json`.

- [ ] **Step 1: Escribir README**

`historias/README.md`:
````markdown
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
python genera_jlpt.py                  # regenerar src/jlpt.py (solo si cambia KANJIDIC2)
python pipeline.py                     # fuentes/ + obras.json → ../catalogo/
python verify_catalogo.py              # verificación (exit 1 si falla)
python -m unittest discover tests -v   # tests
```

## Contrato de datos (app, Plan 3)

- `furigana` = `[inicio, fin, lectura]`, **fin exclusivo**, índices sobre el
  `texto` de la oración.
- `traduccion` siempre `null` en v1 (reservado para el toggle a inglés).
- `catalogo.json` = `{"version": 1, "historias": [{id, titulo, autor,
  dificultad, tamaño, version}]}` — `tamaño` en bytes del JSON de la historia.
- `dificultad` ∈ `{facil, media, dificil}` (umbral en `src/dificultad.py`).
- El diálogo `「...。...。」` es UNA oración (el segmentador no corta dentro
  de comillas) — mismo algoritmo a portar en Kotlin (Plan 3).

## Agregar una obra

1. Agregar entrada a `obras.json` (id, archivo, fuente, url).
2. Descargar el `.txt` a `fuentes/` (ver arriba).
3. `python pipeline.py && python verify_catalogo.py`
4. Commitear `catalogo/` regenerado.

## Licencias

Textos de Aozora Bunko en dominio público (autor 楠山正雄, †1950).
Atribución de fuente en cada JSON (`fuente: aozora:<card>`).
````

- [ ] **Step 2: Descargar fuentes reales**

```bash
cd historias && mkdir -p fuentes && cd fuentes
wget https://www.aozora.gr.jp/cards/000329/files/18376_ruby_12074.zip
unzip -o 18376_ruby_12074.zip && rm 18376_ruby_12074.zip && mv *.txt momotaro.txt
wget https://www.aozora.gr.jp/cards/000329/files/3390_ruby_6090.zip
unzip -o 3390_ruby_6090.zip && rm 3390_ruby_6090.zip && mv $(ls *.txt | grep -v momotaro) urashima_taro.txt
wget https://www.aozora.gr.jp/cards/000329/files/43457_ruby_23696.zip
unzip -o 43457_ruby_23696.zip && rm 43457_ruby_23696.zip && mv $(ls *.txt | grep -vE 'momotaro|urashima') issunboshi.txt
wget https://www.aozora.gr.jp/cards/000329/files/18377_ruby_11923.zip
unzip -o 18377_ruby_11923.zip && rm 18377_ruby_11923.zip && mv $(ls *.txt | grep -vE 'momotaro|urashima|issunboshi') kachikachi_yama.txt
ls -la  # deben quedar exactamente los 4 .txt de obras.json
```

Nota: si el formato real difiere de lo asumido (título/autor no en líneas 1-2, delimitadores distintos), ajustar `aozora.extraer_cuerpo` + fixture + test ANTES de seguir (TDD con el formato real).

- [ ] **Step 3: Build real + verificación**

Run: `cd historias && python pipeline.py && python verify_catalogo.py`
Expected: 4 líneas de stats (párrafos/oraciones/dificultad por obra), `✓ 4 historias emitidas`, y `✓ OK` del verify. Cuentos infantiles → se espera `facil` o `media`; si alguno da `dificil`, revisar métricas con:
```bash
python -c "
import json, pipeline
from src import dificultad
for d in json.load(open('obras.json')):
    h = pipeline.procesar_obra(f\"fuentes/{d['archivo']}\", d)
    textos = [o['texto'] for p in h['parrafos'] for o in p['oraciones']]
    print(d['id'], dificultad.metricas(textos))
"
```
y ajustar umbrales de `src/dificultad.py` con criterio (commit separado si se tocan).

- [ ] **Step 4: Sanity check manual**

Run: `head -c 1200 catalogo/historias/momotaro.json`
Verificar a ojo: título 桃太郎, primer párrafo legible, furigana con índices chicos, sin `《` ni `［＃` en textos.

- [ ] **Step 5: Commit del catálogo + README**

```bash
git add historias/README.md catalogo/
git commit -m "feat(historias): catálogo v1 con 4 cuentos de 楠山正雄"
```

- [ ] **Step 6: Actualizar ESTADO.md**

En `docs/ESTADO.md`: Plan 2 → `✅ Completo (PR #N)`; Plan 3 → `⏳ Siguiente. Plan a escribir (writing-plans)`. Agregar a Datos operativos:
- Catálogo: `catalogo/catalogo.json` commiteado, URL raw `https://raw.githubusercontent.com/T4toh/dokusho-renshuu/main/catalogo/catalogo.json`, formato `{"version": 1, "historias": [...]}`.
- Contrato furigana: `[inicio, fin, lectura]` con fin exclusivo sobre el texto de la oración; diálogo `「…」` = 1 oración (portar igual en Kotlin, Plan 3).
- `historias/src/jlpt.py` es generado (regenerar con `genera_jlpt.py` solo si cambia KANJIDIC2).

```bash
git add docs/ESTADO.md
git commit -m "docs(ESTADO.md): plan 2 completo, plan 3 siguiente"
```

- [ ] **Step 7: Review final de branch + PR**

Usar superpowers:requesting-code-review sobre el diff completo del branch; después:
```bash
git push -u origin feature/plan-2-historias
gh pr create --title "Plan 2: historias/ — pipeline Aozora → catálogo JSON" \
  --body "Pipeline Aozora → JSON + catálogo v1 (4 cuentos de 楠山正雄). Plan: docs/superpowers/plans/2026-07-07-plan-2-historias.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

---

## Verificación end-to-end

1. `cd historias && python -m unittest discover tests -v` → `OK` (~35 tests).
2. `python pipeline.py && python verify_catalogo.py` → `✓ OK` con las 4 obras reales.
3. Inspección manual de `catalogo/historias/momotaro.json` (Step 4 de Task 10).
4. `python -m json.tool catalogo/catalogo.json` → parsea, 4 entradas con `tamaño` > 0.

## Self-Review (hecho al escribir el plan)

- **Cobertura del spec (Componente 2)**: limpieza de marcado + colofón (Tasks 2-3), furigana de ruby alineada (Task 2), segmentación con comillas (Task 4), dificultad JLPT + largo (Tasks 5-6), emisión JSON + catálogo (Task 7, real en Task 10), formato del spec con `traduccion: null` (Task 7), fail-loud + escritura atómica (Tasks 7, 9). Casos de test pedidos por el spec: ruby, comillas 「」, diálogo multi-oración — cubiertos (Tasks 2, 4, 9).
- **Fuera de scope de este plan**: empaquetar historias en el APK (Plan 3 copia los JSON a assets); tokenización (la app la hace on-device).
- **Sin placeholders**: todo el código y fixtures inline.
- **Consistencia de tipos**: `limpiar_linea/segmentar_parrafo` devuelven tuplas `(texto, furigana)` consumidas con esos nombres en `pipeline.procesar_obra`; `construir_historia(..., parrafos=[[(texto, furigana)]])` igual en tests de Tasks 7-9; `verificar(dir) -> list` igual en Tasks 8-9.
- **Riesgos conocidos**: (1) formato exacto de los .txt reales se confirma en Task 10 Step 2 con nota de ajuste TDD; (2) umbrales de dificultad son iniciales — Task 10 Step 3 los contrasta con datos reales; (3) headers de sección de Aozora (`一`, `二` sueltos) quedan como párrafos de una "oración" sin puntuación — aceptado para v1 (la app los muestra como texto).
