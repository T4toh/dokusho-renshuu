# Plan 1: diccionario/ (parser v2 → diccionario.db)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parser Python que combina Jitendex + KANJIDIC2 + Tatoeba en un único `diccionario.db` SQLite para la app Dokusho Renshū.

**Architecture:** Módulos independientes por fuente (`jitendex.py`, `kanjidic.py`, `tatoeba.py`) que parsean a dataclasses; un `constructor.py` que orquesta inserción, índices kanji→oración (cap 50) y palabra→oración (cap 10); CLI en `parser.py`. Evolución del jitendex-parser existente (`~/Repos/Personal/jitendex-parser`), reescrito con tests.

**Tech Stack:** Python 3.11+, stdlib only (sqlite3, json, xml.etree, unittest, argparse, dataclasses). Sin pip.

## Global Constraints

- **Python stdlib only** — cero dependencias externas (igual que dialogos_a_esp).
- **Comentarios y nombres en español** (términos técnicos en inglés OK).
- Todo vive en `diccionario/` dentro del monorepo `dokusho-renshuu`.
- Los archivos fuente descargados van a `diccionario/fuentes/` (**gitignored**).
- `diccionario.db` NO se commitea — se publica en GitHub Releases.
- Versión de esquema: `DB_VERSION = 1`, guardada en tabla `metadata`.
- Cap: **50 oraciones por kanji**, **10 por palabra**, priorizando las más cortas.
- Listas (significados, lecturas, tags) se guardan como **JSON array** (`ensure_ascii=False`).
- Tests con `unittest`, corridos como: `python -m unittest discover diccionario/tests -v` (desde la raíz del repo: `cd diccionario && python -m unittest discover tests -v`).
- Commits desde la raíz del monorepo, prefijo `feat(diccionario):` / `test(diccionario):`.

## File Structure

```
diccionario/
├── parser.py               # CLI: orquesta build completo
├── verify_db.py            # verificación integral del db generado
├── README.md               # fuentes, URLs de descarga, uso, release
├── .gitignore              # fuentes/ y *.db
├── src/
│   ├── __init__.py
│   ├── esquema.py          # DDL + DB_VERSION
│   ├── japones.py          # helpers de caracteres (es_kanji, extraer_kanjis)
│   ├── jitendex.py         # term_bank_*.json → [Palabra]
│   ├── kanjidic.py         # kanjidic2.xml → [Kanji]
│   ├── tatoeba.py          # pares TSV → [Oracion]
│   └── constructor.py      # arma el db: inserts + índices + caps + metadata
└── tests/
    ├── __init__.py
    ├── fixtures/
    │   ├── term_bank_1.json
    │   ├── kanjidic2_min.xml
    │   └── pares_min.tsv
    ├── test_japones.py
    ├── test_jitendex.py
    ├── test_kanjidic.py
    ├── test_tatoeba.py
    └── test_constructor.py
```

---

### Task 1: Scaffolding + esquema

**Files:**
- Create: `diccionario/.gitignore`, `diccionario/src/__init__.py`, `diccionario/tests/__init__.py`
- Create: `diccionario/src/esquema.py`
- Test: `diccionario/tests/test_esquema.py`

**Interfaces:**
- Produces: `esquema.DB_VERSION: int`, `esquema.DDL: str` (script SQL completo, ejecutable con `executescript`).

- [ ] **Step 1: Crear estructura y .gitignore**

```bash
mkdir -p diccionario/src diccionario/tests/fixtures
touch diccionario/src/__init__.py diccionario/tests/__init__.py
```

`diccionario/.gitignore`:
```
fuentes/
*.db
__pycache__/
```

- [ ] **Step 2: Escribir test que falla**

`diccionario/tests/test_esquema.py`:
```python
import sqlite3
import unittest

from src import esquema


class TestEsquema(unittest.TestCase):
    def setUp(self):
        self.conn = sqlite3.connect(':memory:')
        self.conn.executescript(esquema.DDL)

    def tearDown(self):
        self.conn.close()

    def test_tablas_existen(self):
        tablas = {
            fila[0] for fila in self.conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'")
        }
        esperadas = {'metadata', 'palabras', 'kanjis', 'oraciones',
                     'oracion_kanji', 'oracion_palabra'}
        self.assertTrue(esperadas.issubset(tablas))

    def test_version_es_entero_positivo(self):
        self.assertIsInstance(esquema.DB_VERSION, int)
        self.assertGreater(esquema.DB_VERSION, 0)


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 3: Correr test — debe fallar**

Run: `cd diccionario && python -m unittest tests.test_esquema -v`
Expected: ERROR — `No module named 'src.esquema'` (o AttributeError por DDL).

- [ ] **Step 4: Implementar esquema.py**

`diccionario/src/esquema.py`:
```python
"""Esquema SQLite de diccionario.db. Ver spec: docs/superpowers/specs/."""

DB_VERSION = 1

DDL = """
CREATE TABLE metadata (
    clave TEXT PRIMARY KEY,
    valor TEXT NOT NULL
);

CREATE TABLE palabras (
    id INTEGER PRIMARY KEY,
    termino TEXT NOT NULL,
    lectura TEXT,
    significados TEXT NOT NULL,  -- JSON array de strings (inglés)
    tags TEXT,                   -- JSON array de strings
    popularidad INTEGER          -- score de Jitendex, para ordenar resultados
);

CREATE TABLE kanjis (
    kanji TEXT PRIMARY KEY,
    significados TEXT NOT NULL,  -- JSON array (inglés)
    on_yomi TEXT NOT NULL,       -- JSON array
    kun_yomi TEXT NOT NULL,      -- JSON array
    jlpt INTEGER,                -- escala vieja 1-4 de KANJIDIC2; NULL si no figura
    strokes INTEGER
);

CREATE TABLE oraciones (
    id INTEGER PRIMARY KEY,      -- id original de Tatoeba
    japones TEXT NOT NULL,
    ingles TEXT NOT NULL
);

CREATE TABLE oracion_kanji (
    kanji TEXT NOT NULL REFERENCES kanjis(kanji),
    id_oracion INTEGER NOT NULL REFERENCES oraciones(id),
    PRIMARY KEY (kanji, id_oracion)
);

CREATE TABLE oracion_palabra (
    termino TEXT NOT NULL,
    id_oracion INTEGER NOT NULL REFERENCES oraciones(id),
    PRIMARY KEY (termino, id_oracion)
);

CREATE INDEX idx_palabras_termino ON palabras(termino);
CREATE INDEX idx_palabras_lectura ON palabras(lectura);
"""
```

- [ ] **Step 5: Correr test — debe pasar**

Run: `cd diccionario && python -m unittest tests.test_esquema -v`
Expected: `OK` (2 tests).

- [ ] **Step 6: Commit**

```bash
git add diccionario/
git commit -m "feat(diccionario): esquema SQLite v1 con tests"
```

---

### Task 2: Helpers de japonés

**Files:**
- Create: `diccionario/src/japones.py`
- Test: `diccionario/tests/test_japones.py`

**Interfaces:**
- Produces: `japones.es_kanji(c: str) -> bool`; `japones.extraer_kanjis(texto: str) -> list[str]` (únicos, en orden de aparición).

- [ ] **Step 1: Test que falla**

`diccionario/tests/test_japones.py`:
```python
import unittest

from src import japones


class TestJapones(unittest.TestCase):
    def test_es_kanji(self):
        self.assertTrue(japones.es_kanji('語'))
        self.assertTrue(japones.es_kanji('物'))
        self.assertFalse(japones.es_kanji('た'))   # hiragana
        self.assertFalse(japones.es_kanji('タ'))   # katakana
        self.assertFalse(japones.es_kanji('a'))
        self.assertFalse(japones.es_kanji('。'))
        self.assertFalse(japones.es_kanji('々'))   # marca de repetición, no kanji

    def test_extraer_kanjis_unicos_en_orden(self):
        self.assertEqual(
            japones.extraer_kanjis('物語は物語です。'),
            ['物', '語'])

    def test_extraer_kanjis_sin_kanji(self):
        self.assertEqual(japones.extraer_kanjis('ひらがなだけ'), [])


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 2: Correr — falla** (`No module named 'src.japones'`)

Run: `cd diccionario && python -m unittest tests.test_japones -v`

- [ ] **Step 3: Implementar**

`diccionario/src/japones.py`:
```python
"""Helpers de caracteres japoneses."""


def es_kanji(caracter: str) -> bool:
    """CJK Unified Ideographs (U+4E00–U+9FFF)."""
    return '一' <= caracter <= '鿿'


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

- [ ] **Step 4: Correr — pasa** (`OK`, 3 tests)

- [ ] **Step 5: Commit**

```bash
git add diccionario/
git commit -m "feat(diccionario): helpers es_kanji y extraer_kanjis"
```

---

### Task 3: Parser Jitendex

**Files:**
- Create: `diccionario/src/jitendex.py`
- Create: `diccionario/tests/fixtures/term_bank_1.json`
- Test: `diccionario/tests/test_jitendex.py`

**Interfaces:**
- Consumes: nada de tasks previas.
- Produces: `jitendex.Palabra` (dataclass: `termino: str, lectura: str, significados: list, tags: list, popularidad: int`); `jitendex.parsear_directorio(dir: str) -> list[Palabra]` (lee todos los `term_bank_*.json`).

**Contexto de formato** (Yomitan term bank, igual que el jitendex-parser viejo): cada entrada es un array de 8 posiciones: `[termino, lectura, definition_tags, rules, popularidad, glossary, sequence, term_tags]`. `glossary` es lista de strings planos y/o objetos `{"type": "structured-content", "content": ...}` donde las glosas están en nodos `{"tag": "li", "content": ...}` anidados.

- [ ] **Step 1: Crear fixture**

`diccionario/tests/fixtures/term_bank_1.json`:
```json
[
  ["物語", "ものがたり", "n", "", 4200,
    [{"type": "structured-content", "content": [
      {"tag": "ol", "content": [
        {"tag": "li", "content": "tale"},
        {"tag": "li", "content": [{"tag": "span", "content": "story"}, " (long)"]}
      ]}
    ]}],
    1000010, "common"],
  ["猫", "ねこ", "n", "", 9000, ["cat"], 1000020, "common animal"]
]
```

- [ ] **Step 2: Test que falla**

`diccionario/tests/test_jitendex.py`:
```python
import os
import unittest

from src import jitendex

FIXTURES = os.path.join(os.path.dirname(__file__), 'fixtures')


class TestJitendex(unittest.TestCase):
    def setUp(self):
        self.palabras = jitendex.parsear_directorio(FIXTURES)

    def test_cantidad(self):
        self.assertEqual(len(self.palabras), 2)

    def test_structured_content(self):
        monogatari = next(p for p in self.palabras if p.termino == '物語')
        self.assertEqual(monogatari.lectura, 'ものがたり')
        self.assertEqual(monogatari.significados, ['tale', 'story (long)'])
        self.assertEqual(monogatari.popularidad, 4200)
        self.assertIn('common', monogatari.tags)

    def test_glosa_plana(self):
        neko = next(p for p in self.palabras if p.termino == '猫')
        self.assertEqual(neko.significados, ['cat'])
        # tags = unión de definition_tags y term_tags, ordenados
        self.assertEqual(neko.tags, ['animal', 'common', 'n'])


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 3: Correr — falla**

Run: `cd diccionario && python -m unittest tests.test_jitendex -v`

- [ ] **Step 4: Implementar**

`diccionario/src/jitendex.py`:
```python
"""Parser de term banks de Jitendex (formato Yomitan)."""
import glob
import json
import os
from dataclasses import dataclass, field


@dataclass
class Palabra:
    termino: str
    lectura: str
    significados: list = field(default_factory=list)
    tags: list = field(default_factory=list)
    popularidad: int = 0


def _texto_plano(nodo) -> str:
    """Concatena todo el texto de un nodo structured-content."""
    if isinstance(nodo, str):
        return nodo
    if isinstance(nodo, list):
        return ''.join(_texto_plano(n) for n in nodo)
    if isinstance(nodo, dict):
        return _texto_plano(nodo.get('content', ''))
    return ''


def _buscar_li(nodo, acumulador: list) -> None:
    """Junta el texto de cada nodo <li> (una glosa por li)."""
    if isinstance(nodo, list):
        for n in nodo:
            _buscar_li(n, acumulador)
    elif isinstance(nodo, dict):
        if nodo.get('tag') == 'li':
            texto = _texto_plano(nodo.get('content', '')).strip()
            if texto:
                acumulador.append(texto)
        else:
            _buscar_li(nodo.get('content', []), acumulador)


def extraer_glosas(glossary) -> list:
    glosas = []
    for item in glossary:
        if isinstance(item, str):
            glosas.append(item)
        elif isinstance(item, dict) and item.get('type') == 'structured-content':
            _buscar_li(item.get('content', []), glosas)
    return glosas


def parsear_entrada(entrada) -> Palabra:
    termino, lectura, def_tags, _, popularidad, glossary, _, term_tags = entrada
    tags = sorted(set((def_tags or '').split() + (term_tags or '').split()))
    return Palabra(
        termino=termino,
        lectura=lectura,
        significados=extraer_glosas(glossary),
        tags=tags,
        popularidad=int(popularidad or 0),
    )


def parsear_directorio(directorio: str) -> list:
    palabras = []
    for ruta in sorted(glob.glob(os.path.join(directorio, 'term_bank_*.json'))):
        with open(ruta, encoding='utf-8') as f:
            for entrada in json.load(f):
                palabras.append(parsear_entrada(entrada))
    return palabras
```

- [ ] **Step 5: Correr — pasa** (`OK`, 3 tests)

- [ ] **Step 6: Commit**

```bash
git add diccionario/
git commit -m "feat(diccionario): parser Jitendex con structured-content"
```

---

### Task 4: Parser KANJIDIC2

**Files:**
- Create: `diccionario/src/kanjidic.py`
- Create: `diccionario/tests/fixtures/kanjidic2_min.xml`
- Test: `diccionario/tests/test_kanjidic.py`

**Interfaces:**
- Produces: `kanjidic.Kanji` (dataclass: `kanji: str, significados: list, on_yomi: list, kun_yomi: list, jlpt: int|None, strokes: int|None`); `kanjidic.parsear_kanjidic(ruta: str) -> list[Kanji]`.

**Contexto de formato**: KANJIDIC2 es XML con un `<character>` por kanji. Campos: `<literal>`, `<misc><stroke_count><jlpt>`, `<reading r_type="ja_on|ja_kun">`, `<meaning>` (sin atributo `m_lang` = inglés; con `m_lang` = otro idioma, se descarta).

- [ ] **Step 1: Crear fixture**

`diccionario/tests/fixtures/kanjidic2_min.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<kanjidic2>
<character>
<literal>語</literal>
<misc><grade>2</grade><stroke_count>14</stroke_count><jlpt>4</jlpt></misc>
<reading_meaning><rmgroup>
<reading r_type="ja_on">ゴ</reading>
<reading r_type="ja_kun">かた.る</reading>
<reading r_type="ja_kun">かた.らう</reading>
<meaning>word</meaning>
<meaning>speech</meaning>
<meaning m_lang="es">palabra</meaning>
</rmgroup></reading_meaning>
</character>
<character>
<literal>物</literal>
<misc><stroke_count>8</stroke_count></misc>
<reading_meaning><rmgroup>
<reading r_type="ja_on">ブツ</reading>
<reading r_type="ja_kun">もの</reading>
<meaning>thing</meaning>
</rmgroup></reading_meaning>
</character>
</kanjidic2>
```

- [ ] **Step 2: Test que falla**

`diccionario/tests/test_kanjidic.py`:
```python
import os
import unittest

from src import kanjidic

FIXTURE = os.path.join(os.path.dirname(__file__), 'fixtures', 'kanjidic2_min.xml')


class TestKanjidic(unittest.TestCase):
    def setUp(self):
        self.kanjis = {k.kanji: k for k in kanjidic.parsear_kanjidic(FIXTURE)}

    def test_cantidad(self):
        self.assertEqual(len(self.kanjis), 2)

    def test_kanji_completo(self):
        go = self.kanjis['語']
        self.assertEqual(go.on_yomi, ['ゴ'])
        self.assertEqual(go.kun_yomi, ['かた.る', 'かた.らう'])
        self.assertEqual(go.significados, ['word', 'speech'])  # sin m_lang="es"
        self.assertEqual(go.jlpt, 4)
        self.assertEqual(go.strokes, 14)

    def test_kanji_sin_jlpt(self):
        mono = self.kanjis['物']
        self.assertIsNone(mono.jlpt)
        self.assertEqual(mono.strokes, 8)


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 3: Correr — falla**

Run: `cd diccionario && python -m unittest tests.test_kanjidic -v`

- [ ] **Step 4: Implementar**

`diccionario/src/kanjidic.py`:
```python
"""Parser de KANJIDIC2 (kanjidic2.xml, descomprimido)."""
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field


@dataclass
class Kanji:
    kanji: str
    significados: list = field(default_factory=list)
    on_yomi: list = field(default_factory=list)
    kun_yomi: list = field(default_factory=list)
    jlpt: int = None
    strokes: int = None


def parsear_kanjidic(ruta: str) -> list:
    kanjis = []
    # iterparse: el archivo real pesa ~15 MB, evita cargar el árbol entero
    for _, elem in ET.iterparse(ruta, events=('end',)):
        if elem.tag != 'character':
            continue
        misc = elem.find('misc')
        jlpt = misc.findtext('jlpt') if misc is not None else None
        strokes = misc.findtext('stroke_count') if misc is not None else None
        on_yomi, kun_yomi, significados = [], [], []
        for r in elem.iter('reading'):
            if r.get('r_type') == 'ja_on' and r.text:
                on_yomi.append(r.text)
            elif r.get('r_type') == 'ja_kun' and r.text:
                kun_yomi.append(r.text)
        for m in elem.iter('meaning'):
            if m.get('m_lang') is None and m.text:
                significados.append(m.text)
        kanjis.append(Kanji(
            kanji=elem.findtext('literal'),
            significados=significados,
            on_yomi=on_yomi,
            kun_yomi=kun_yomi,
            jlpt=int(jlpt) if jlpt else None,
            strokes=int(strokes) if strokes else None,
        ))
        elem.clear()
    return kanjis
```

- [ ] **Step 5: Correr — pasa** (`OK`, 3 tests)

- [ ] **Step 6: Commit**

```bash
git add diccionario/
git commit -m "feat(diccionario): parser KANJIDIC2"
```

---

### Task 5: Parser Tatoeba

**Files:**
- Create: `diccionario/src/tatoeba.py`
- Create: `diccionario/tests/fixtures/pares_min.tsv`
- Test: `diccionario/tests/test_tatoeba.py`

**Interfaces:**
- Produces: `tatoeba.Oracion` (dataclass: `id: int, japones: str, ingles: str`); `tatoeba.parsear_pares(ruta: str) -> list[Oracion]`.

**Contexto de formato**: export "Sentence pairs" de tatoeba.org (jpn→eng), TSV de 4 columnas: `id_jpn \t texto_jpn \t id_eng \t texto_eng`. Una oración japonesa puede aparecer en varias filas (varias traducciones): se queda la primera.

- [ ] **Step 1: Crear fixture**

`diccionario/tests/fixtures/pares_min.tsv` (separador = TAB real):
```
1	これは物語です。	101	This is a tale.
1	これは物語です。	102	This is a story.
2	猫が好きです。	103	I like cats.
3	むかしむかし、おじいさんがいました。	104	Once upon a time, there was an old man.
```

- [ ] **Step 2: Test que falla**

`diccionario/tests/test_tatoeba.py`:
```python
import os
import unittest

from src import tatoeba

FIXTURE = os.path.join(os.path.dirname(__file__), 'fixtures', 'pares_min.tsv')


class TestTatoeba(unittest.TestCase):
    def setUp(self):
        self.oraciones = tatoeba.parsear_pares(FIXTURE)

    def test_dedup_por_id_japones(self):
        # id 1 aparece dos veces: queda la primera traducción
        self.assertEqual(len(self.oraciones), 3)
        primera = self.oraciones[0]
        self.assertEqual(primera.id, 1)
        self.assertEqual(primera.japones, 'これは物語です。')
        self.assertEqual(primera.ingles, 'This is a tale.')

    def test_linea_malformada_se_ignora(self):
        import tempfile
        with tempfile.NamedTemporaryFile(
                'w', suffix='.tsv', delete=False, encoding='utf-8') as f:
            f.write('99\tsolo dos campos\n')
            ruta = f.name
        self.assertEqual(tatoeba.parsear_pares(ruta), [])
        os.unlink(ruta)


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 3: Correr — falla**

Run: `cd diccionario && python -m unittest tests.test_tatoeba -v`

- [ ] **Step 4: Implementar**

`diccionario/src/tatoeba.py`:
```python
"""Parser del export "Sentence pairs" jpn→eng de Tatoeba."""
from dataclasses import dataclass


@dataclass
class Oracion:
    id: int
    japones: str
    ingles: str


def parsear_pares(ruta: str) -> list:
    oraciones = []
    vistos = set()
    with open(ruta, encoding='utf-8') as f:
        for linea in f:
            campos = linea.rstrip('\n').split('\t')
            if len(campos) < 4:
                continue  # línea malformada: ignorar, no frenar el build
            id_jpn, japones, _, ingles = campos[0], campos[1], campos[2], campos[3]
            if id_jpn in vistos:
                continue  # ya tenemos una traducción para esta oración
            vistos.add(id_jpn)
            oraciones.append(Oracion(int(id_jpn), japones, ingles))
    return oraciones
```

- [ ] **Step 5: Correr — pasa** (`OK`, 2 tests)

- [ ] **Step 6: Commit**

```bash
git add diccionario/
git commit -m "feat(diccionario): parser de pares Tatoeba"
```

---

### Task 6: Constructor del db + CLI

**Files:**
- Create: `diccionario/src/constructor.py`
- Create: `diccionario/parser.py`
- Test: `diccionario/tests/test_constructor.py`

**Interfaces:**
- Consumes: `esquema.DDL`, `esquema.DB_VERSION`, `japones.extraer_kanjis`, `jitendex.parsear_directorio`, `kanjidic.parsear_kanjidic`, `tatoeba.parsear_pares` (firmas de tasks 1-5).
- Produces: `constructor.construir(ruta_db: str, dir_fuentes: str) -> dict` (stats: `{'palabras': int, 'kanjis': int, 'oraciones': int, 'oracion_kanji': int, 'oracion_palabra': int}`). CLI: `python parser.py --fuentes fuentes --salida diccionario-v1.db`.

**Lógica clave:**
- Índice kanji→oraciones: por cada oración, `extraer_kanjis`; agrupar; ordenar por largo de oración ascendente; cap `MAX_ORACIONES_POR_KANJI = 50`.
- Solo se insertan en `oraciones` las referenciadas por algún kanji (kana-only se descartan).
- Índice palabra→oraciones: sobre las oraciones retenidas, enumerar substrings de largo 2-6 y matchear contra el set de términos con kanji; cap `MAX_ORACIONES_POR_PALABRA = 10`, cortas primero.

- [ ] **Step 1: Test que falla**

`diccionario/tests/test_constructor.py`:
```python
import json
import os
import sqlite3
import tempfile
import unittest

from src import constructor, esquema

FIXTURES = os.path.join(os.path.dirname(__file__), 'fixtures')


class TestConstructor(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.ruta_db = os.path.join(cls.tmp, 'test.db')
        # fixtures/ tiene term_bank_1.json, kanjidic2_min.xml y pares_min.tsv
        # con nombres que constructor espera: ver construir()
        cls.stats = constructor.construir(cls.ruta_db, FIXTURES)
        cls.conn = sqlite3.connect(cls.ruta_db)

    @classmethod
    def tearDownClass(cls):
        cls.conn.close()

    def test_stats(self):
        self.assertEqual(self.stats['palabras'], 2)
        self.assertEqual(self.stats['kanjis'], 2)
        # oración 3 es kana-only → descartada. Quedan 1 (物語) y 2 (猫... sin
        # kanji en kanjidic_min → según fixture 猫 no está en kanjis, también
        # descartada). Retenida: solo la oración 1.
        self.assertEqual(self.stats['oraciones'], 1)

    def test_version_en_metadata(self):
        valor = self.conn.execute(
            "SELECT valor FROM metadata WHERE clave='version'").fetchone()[0]
        self.assertEqual(int(valor), esquema.DB_VERSION)

    def test_significados_son_json(self):
        fila = self.conn.execute(
            "SELECT significados FROM palabras WHERE termino='物語'").fetchone()
        self.assertEqual(json.loads(fila[0]), ['tale', 'story (long)'])

    def test_oracion_kanji(self):
        ids = [f[0] for f in self.conn.execute(
            "SELECT id_oracion FROM oracion_kanji WHERE kanji='語'")]
        self.assertEqual(ids, [1])

    def test_oracion_palabra(self):
        ids = [f[0] for f in self.conn.execute(
            "SELECT id_oracion FROM oracion_palabra WHERE termino='物語'")]
        self.assertEqual(ids, [1])

    def test_integridad_fk(self):
        errores = self.conn.execute('PRAGMA foreign_key_check').fetchall()
        self.assertEqual(errores, [])


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 2: Correr — falla**

Run: `cd diccionario && python -m unittest tests.test_constructor -v`

- [ ] **Step 3: Implementar constructor**

`diccionario/src/constructor.py`:
```python
"""Orquesta el build de diccionario.db a partir de las fuentes."""
import json
import os
import sqlite3
from collections import defaultdict

from . import esquema, japones, jitendex, kanjidic, tatoeba

MAX_ORACIONES_POR_KANJI = 50
MAX_ORACIONES_POR_PALABRA = 10
LARGO_MIN_TERMINO = 2
LARGO_MAX_TERMINO = 6

ARCHIVO_KANJIDIC = 'kanjidic2_min.xml'  # en fuentes reales: kanjidic2.xml
ARCHIVO_TATOEBA = 'pares_min.tsv'       # en fuentes reales: pares_jpn_eng.tsv


def _json(lista) -> str:
    return json.dumps(lista, ensure_ascii=False)


def _resolver(dir_fuentes: str, preferido: str, alternativo: str) -> str:
    """Usa el nombre real si existe, si no el de fixture (para tests)."""
    ruta = os.path.join(dir_fuentes, preferido)
    return ruta if os.path.exists(ruta) else os.path.join(dir_fuentes, alternativo)


def construir(ruta_db: str, dir_fuentes: str) -> dict:
    palabras = jitendex.parsear_directorio(dir_fuentes)
    kanjis = kanjidic.parsear_kanjidic(
        _resolver(dir_fuentes, 'kanjidic2.xml', ARCHIVO_KANJIDIC))
    oraciones = tatoeba.parsear_pares(
        _resolver(dir_fuentes, 'pares_jpn_eng.tsv', ARCHIVO_TATOEBA))

    kanjis_conocidos = {k.kanji for k in kanjis}

    # kanji → oraciones que lo usan, cortas primero, con cap
    por_kanji = defaultdict(list)
    for o in oraciones:
        for k in japones.extraer_kanjis(o.japones):
            if k in kanjis_conocidos:
                por_kanji[k].append(o)
    for k in por_kanji:
        por_kanji[k].sort(key=lambda o: len(o.japones))
        por_kanji[k] = por_kanji[k][:MAX_ORACIONES_POR_KANJI]

    # solo se guardan oraciones referenciadas por algún kanji
    retenidas = {o.id: o for lista in por_kanji.values() for o in lista}

    # palabra → oraciones retenidas que la contienen (substring), con cap
    terminos_con_kanji = {
        p.termino for p in palabras
        if LARGO_MIN_TERMINO <= len(p.termino) <= LARGO_MAX_TERMINO
        and any(japones.es_kanji(c) for c in p.termino)
    }
    por_palabra = defaultdict(list)
    for o in sorted(retenidas.values(), key=lambda o: len(o.japones)):
        texto = o.japones
        encontrados = set()
        for i in range(len(texto)):
            for largo in range(LARGO_MIN_TERMINO, LARGO_MAX_TERMINO + 1):
                sub = texto[i:i + largo]
                if sub in terminos_con_kanji:
                    encontrados.add(sub)
        for termino in encontrados:
            if len(por_palabra[termino]) < MAX_ORACIONES_POR_PALABRA:
                por_palabra[termino].append(o.id)

    if os.path.exists(ruta_db):
        os.remove(ruta_db)
    conn = sqlite3.connect(ruta_db)
    conn.executescript(esquema.DDL)
    conn.execute("INSERT INTO metadata VALUES ('version', ?)",
                 (str(esquema.DB_VERSION),))
    conn.executemany(
        'INSERT INTO palabras (termino, lectura, significados, tags, popularidad)'
        ' VALUES (?, ?, ?, ?, ?)',
        [(p.termino, p.lectura, _json(p.significados), _json(p.tags),
          p.popularidad) for p in palabras])
    conn.executemany(
        'INSERT OR IGNORE INTO kanjis VALUES (?, ?, ?, ?, ?, ?)',
        [(k.kanji, _json(k.significados), _json(k.on_yomi), _json(k.kun_yomi),
          k.jlpt, k.strokes) for k in kanjis])
    conn.executemany(
        'INSERT INTO oraciones VALUES (?, ?, ?)',
        [(o.id, o.japones, o.ingles) for o in retenidas.values()])
    conn.executemany(
        'INSERT INTO oracion_kanji VALUES (?, ?)',
        [(k, o.id) for k, lista in por_kanji.items() for o in lista])
    conn.executemany(
        'INSERT INTO oracion_palabra VALUES (?, ?)',
        [(t, id_o) for t, ids in por_palabra.items() for id_o in ids])
    conn.commit()

    stats = {
        'palabras': len(palabras),
        'kanjis': len(kanjis),
        'oraciones': len(retenidas),
        'oracion_kanji': sum(len(v) for v in por_kanji.values()),
        'oracion_palabra': sum(len(v) for v in por_palabra.values()),
    }
    conn.close()
    return stats
```

- [ ] **Step 4: Correr — pasa**

Run: `cd diccionario && python -m unittest tests.test_constructor -v`
Expected: `OK` (6 tests).

- [ ] **Step 5: CLI parser.py**

`diccionario/parser.py`:
```python
"""CLI: construye diccionario.db desde las fuentes descargadas.

Uso: python parser.py [--fuentes fuentes] [--salida diccionario-v1.db]
Fuentes esperadas en --fuentes (ver README.md):
  term_bank_*.json  (Jitendex)  ·  kanjidic2.xml  ·  pares_jpn_eng.tsv
"""
import argparse

from src import constructor, esquema


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fuentes', default='fuentes')
    parser.add_argument('--salida',
                        default=f'diccionario-v{esquema.DB_VERSION}.db')
    args = parser.parse_args()

    print(f'Construyendo {args.salida} desde {args.fuentes}/ ...')
    stats = constructor.construir(args.salida, args.fuentes)
    for clave, valor in stats.items():
        print(f'  {clave}: {valor:,}')
    print('Listo. Correr verify_db.py antes de publicar.')


if __name__ == '__main__':
    main()
```

Verificación manual del CLI (usa fixtures como fuentes):
Run: `cd diccionario && python parser.py --fuentes tests/fixtures --salida /tmp/cli_test.db`
Expected: imprime stats (`palabras: 2`, etc.) sin traceback.

- [ ] **Step 6: Correr TODOS los tests**

Run: `cd diccionario && python -m unittest discover tests -v`
Expected: `OK` (todos los tests de tasks 1-6).

- [ ] **Step 7: Commit**

```bash
git add diccionario/
git commit -m "feat(diccionario): constructor de db con caps e índices + CLI"
```

---

### Task 7: verify_db.py

**Files:**
- Create: `diccionario/verify_db.py`
- Test: `diccionario/tests/test_verify.py`

**Interfaces:**
- Consumes: db generado por `constructor.construir`.
- Produces: `verify_db.verificar(ruta_db: str) -> list[str]` (lista de errores; vacía = OK). CLI: `python verify_db.py diccionario-v1.db` (exit 1 si hay errores).

- [ ] **Step 1: Test que falla**

`diccionario/tests/test_verify.py`:
```python
import os
import sqlite3
import tempfile
import unittest

import verify_db
from src import constructor

FIXTURES = os.path.join(os.path.dirname(__file__), 'fixtures')


class TestVerify(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.ruta_db = os.path.join(self.tmp, 'test.db')
        constructor.construir(self.ruta_db, FIXTURES)

    def test_db_valido_sin_errores(self):
        self.assertEqual(verify_db.verificar(self.ruta_db), [])

    def test_detecta_kanji_huerfano(self):
        conn = sqlite3.connect(self.ruta_db)
        conn.execute("INSERT INTO oracion_kanji VALUES ('犬', 1)")
        conn.commit()
        conn.close()
        errores = verify_db.verificar(self.ruta_db)
        self.assertTrue(any('犬' in e or 'foreign' in e.lower() or 'FK' in e
                            for e in errores))

    def test_detecta_tabla_vacia(self):
        conn = sqlite3.connect(self.ruta_db)
        conn.execute('DELETE FROM oracion_palabra')
        conn.commit()
        conn.close()
        errores = verify_db.verificar(self.ruta_db)
        self.assertTrue(any('oracion_palabra' in e for e in errores))


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 2: Correr — falla**

Run: `cd diccionario && python -m unittest tests.test_verify -v`

- [ ] **Step 3: Implementar**

`diccionario/verify_db.py`:
```python
"""Verificación integral de un diccionario.db generado.

Uso: python verify_db.py [ruta_db]   (default: diccionario-v1.db)
Exit 0 si todo OK, exit 1 si hay errores.
"""
import sqlite3
import sys

from src import esquema

TABLAS = ['palabras', 'kanjis', 'oraciones', 'oracion_kanji', 'oracion_palabra']


def verificar(ruta_db: str) -> list:
    errores = []
    conn = sqlite3.connect(ruta_db)

    # 1. counts: ninguna tabla vacía
    for tabla in TABLAS:
        n = conn.execute(f'SELECT COUNT(*) FROM {tabla}').fetchone()[0]
        print(f'  {tabla}: {n:,}')
        if n == 0:
            errores.append(f'tabla {tabla} vacía')

    # 2. versión
    fila = conn.execute(
        "SELECT valor FROM metadata WHERE clave='version'").fetchone()
    if fila is None or int(fila[0]) != esquema.DB_VERSION:
        errores.append(f'version en metadata != {esquema.DB_VERSION}')

    # 3. integridad FK
    for fk in conn.execute('PRAGMA foreign_key_check').fetchall():
        errores.append(f'violación FK: {fk}')

    # 4. cap de oraciones por kanji
    exceso = conn.execute(
        'SELECT kanji, COUNT(*) c FROM oracion_kanji GROUP BY kanji'
        ' HAVING c > 50').fetchall()
    if exceso:
        errores.append(f'kanjis con más de 50 oraciones: {exceso[:5]}')

    conn.close()
    return errores


def _spot_checks(ruta_db: str) -> list:
    """Solo para db reales (con fuentes completas): kanjis conocidos."""
    errores = []
    conn = sqlite3.connect(ruta_db)
    for kanji in ('語', '物', '日', '人'):
        if conn.execute('SELECT 1 FROM kanjis WHERE kanji=?',
                        (kanji,)).fetchone() is None:
            errores.append(f'spot-check: falta kanji {kanji}')
    if conn.execute("SELECT 1 FROM palabras WHERE termino='物語'"
                    ).fetchone() is None:
        errores.append('spot-check: falta palabra 物語')
    conn.close()
    return errores


if __name__ == '__main__':
    ruta = sys.argv[1] if len(sys.argv) > 1 else f'diccionario-v{esquema.DB_VERSION}.db'
    print(f'Verificando {ruta} ...')
    errores = verificar(ruta) + _spot_checks(ruta)
    if errores:
        print('ERRORES:')
        for e in errores:
            print(f'  ✗ {e}')
        sys.exit(1)
    print('✓ OK')
```

- [ ] **Step 4: Correr — pasa**

Run: `cd diccionario && python -m unittest tests.test_verify -v`
Expected: `OK` (3 tests).

- [ ] **Step 5: Commit**

```bash
git add diccionario/
git commit -m "feat(diccionario): verify_db con FK, caps y spot-checks"
```

---

### Task 8: README + build real + release

**Files:**
- Create: `diccionario/README.md`

**Interfaces:**
- Consumes: CLI de Task 6 y verify de Task 7.
- Produces: `diccionario-v1.db` publicado en GitHub Releases del repo.

- [ ] **Step 1: Escribir README**

`diccionario/README.md`:
````markdown
# diccionario/ — parser de diccionario.db

Combina tres fuentes libres en un SQLite para la app Dokusho Renshū.
Python stdlib only, sin pip. Evolución de
[jitendex-parser](https://github.com/T4toh/jitendex-parser).

## Fuentes (descargar a `fuentes/`, gitignored)

| Fuente | Qué aporta | Descarga |
|--------|-----------|----------|
| Jitendex (Yomitan) | palabras JP→EN | https://github.com/stephenmk/jitendex/releases → `jitendex-yomitan.zip` → extraer `term_bank_*.json` |
| KANJIDIC2 | kanjis: lecturas, significados, JLPT | http://www.edrdg.org/kanjidic/kanjidic2.xml.gz → `gunzip` |
| Tatoeba | oraciones de ejemplo JP-EN (CC-BY) | https://tatoeba.org/es/downloads → "Sentence pairs" jpn→eng → renombrar a `pares_jpn_eng.tsv` |

```bash
mkdir -p fuentes && cd fuentes
# jitendex: bajar zip del release y extraer term_bank_*.json acá
wget http://www.edrdg.org/kanjidic/kanjidic2.xml.gz && gunzip kanjidic2.xml.gz
# tatoeba: export manual desde la web (requiere elegir jpn→eng)
```

## Uso

```bash
python parser.py                      # fuentes/ → diccionario-v1.db
python verify_db.py diccionario-v1.db # verificación (exit 1 si falla)
python -m unittest discover tests -v  # tests
```

## Release

1. `python parser.py && python verify_db.py diccionario-v1.db`
2. Chequeo manual: abrir el db, buscar 2-3 palabras conocidas
3. `gh release create db-v1 diccionario-v1.db --title "diccionario.db v1" \
    --notes "Jitendex <fecha> + KANJIDIC2 + Tatoeba <fecha>"`
4. La app (Plan 3) baja este asset a `app/src/main/assets/`

## Licencias

- Jitendex: CC BY-SA 4.0 · KANJIDIC2: CC BY-SA 4.0 (EDRDG) · Tatoeba: CC-BY 2.0 FR
- Atribución requerida en la app (pantalla "Acerca de", Plan 3)
````

- [ ] **Step 2: Descargar fuentes reales**

```bash
cd diccionario && mkdir -p fuentes && cd fuentes
wget http://www.edrdg.org/kanjidic/kanjidic2.xml.gz && gunzip kanjidic2.xml.gz
# Jitendex: bajar https://github.com/stephenmk/jitendex/releases/latest
#   asset jitendex-yomitan.zip, extraer term_bank_*.json acá
# Tatoeba: export "Sentence pairs" jpn→eng desde https://tatoeba.org/es/downloads
#   guardar como pares_jpn_eng.tsv
```

Nota: si el formato real del TSV de Tatoeba difiere de 4 columnas, ajustar `tatoeba.parsear_pares` + fixture + test ANTES de seguir (TDD: primero el test con el formato real).

- [ ] **Step 3: Build real + verificación**

Run: `cd diccionario && python parser.py && python verify_db.py diccionario-v1.db`
Expected: stats con magnitudes reales (palabras: ~200-300k, kanjis: ~13k, oraciones: decenas de miles) y `✓ OK`. Chequear tamaño: `ls -lh diccionario-v1.db` — si supera ~80 MB, bajar `MAX_ORACIONES_POR_KANJI`.

- [ ] **Step 4: Commit + release**

```bash
git add diccionario/README.md
git commit -m "docs(diccionario): README con fuentes, uso y proceso de release"
gh release create db-v1 diccionario/diccionario-v1.db \
  --title "diccionario.db v1" --notes "Jitendex + KANJIDIC2 + Tatoeba"
```

---

## Self-Review (hecho al escribir el plan)

- **Cobertura del spec**: esquema completo (Task 1), las 3 fuentes (Tasks 3-5), caps e índices precalculados (Task 6), verify extendido (Task 7), versionado + release (Tasks 1, 8). `oracion_palabra` incluida (Task 6).
- **Sin placeholders**: todo el código está inline.
- **Consistencia de tipos**: `Palabra`/`Kanji`/`Oracion` definidas en Tasks 3-5, consumidas en Task 6 con los mismos campos. `construir(ruta_db, dir_fuentes) -> dict` usada igual en Tasks 6-8.
- **Riesgo conocido**: formato exacto del TSV de Tatoeba se confirma en Task 8 Step 2 (parser tolerante + nota de ajuste TDD).
