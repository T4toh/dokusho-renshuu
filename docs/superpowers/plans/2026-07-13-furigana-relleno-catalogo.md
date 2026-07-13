# Relleno de furigana faltante en el catálogo — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Completar los huecos de furigana del catálogo (fuentes Aozora traen ruby parcial: cobertura actual 11.5%–92.9% según historia) generando lecturas con janome/IPADIC en el pipeline Python.

**Architecture:** Módulo nuevo `historias/src/relleno_furigana.py` con `completar(texto, furigana)` por oración; hook en `pipeline.procesar_obra` tras la segmentación. Ruby Aozora siempre gana (token que toca un span existente se salta entero). Trim de okurigana portado de `GeneradorFurigana.kt` de la app. Schema v2 intacto; el fix llega a usuarios regenerando `catalogo/` y pusheando (se sirve raw desde main).

**Tech Stack:** Python 3 (repo usa unittest, no pytest), janome 0.5.0 (puro Python, IPADIC — mismo diccionario que Kuromoji en la app).

**Spec:** `docs/superpowers/specs/2026-07-13-furigana-relleno-catalogo-design.md`

## Global Constraints

- Contrato furigana: ternas `[inicio, fin, lectura]`, fin exclusivo, índices sobre el texto de la oración, spans disjuntos (verify_catalogo lo chequea).
- Ruby Aozora original nunca se pisa ni se rellena a medias: overlap → skip del token entero.
- `水《みいず》` de la canción de momotaro es intencional (fuente original) — NO "corregirlo".
- Schema del catálogo sigue en version 2; la app no se toca.
- Tests: `python3 -m unittest discover tests -v` desde `historias/`.
- Pipeline "falla ruidoso": nada de try/except silenciosos.
- Dependencia nueva janome==0.5.0: verificar al instalar que la versión tiene >7 días publicada (regla org; 0.5.0 es de 2023, cumple de sobra).

---

### Task 1: Módulo `relleno_furigana` + dependencia janome

**Files:**
- Create: `historias/src/relleno_furigana.py`
- Create: `historias/tests/test_relleno_furigana.py`
- Create: `historias/requirements.txt`
- Modify: `historias/README.md` (línea 5: "Python stdlib only, sin pip." ya no vale; y bloque de comandos ~línea 39)

**Interfaces:**
- Consumes: `japones.es_kanji(c: str) -> bool` (`historias/src/japones.py`).
- Produces: `relleno_furigana.completar(texto: str, furigana: list) -> list` — recibe texto de oración + ternas existentes `[[inicio, fin, lectura], ...]`; devuelve lista completa (originales intactas + generadas), ordenada por inicio, disjunta. Task 2 la consume.

- [ ] **Step 1: Instalar janome**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/historias
python3 -m pip install --user janome==0.5.0
python3 -c "from janome.tokenizer import Tokenizer; print('ok')"
```

Expected: `ok`. Si pip da error `externally-managed-environment` (PEP 668): `python3 -m venv .venv && .venv/bin/pip install janome==0.5.0` y usar `.venv/bin/python3` en todos los comandos siguientes (no commitear `.venv/`).

- [ ] **Step 2: Crear `historias/requirements.txt`**

```
janome==0.5.0
```

- [ ] **Step 3: Escribir tests que fallan**

`historias/tests/test_relleno_furigana.py`:

```python
import unittest

from src import relleno_furigana


class TestCompletar(unittest.TestCase):
    def test_rellena_hueco_simple(self):
        resultado = relleno_furigana.completar('山へ行く', [])
        self.assertEqual(resultado, [[0, 1, 'やま'], [2, 3, 'い']])

    def test_respeta_ruby_existente(self):
        # la terna original queda intacta (aunque difiera de IPADIC)
        resultado = relleno_furigana.completar('山へ行く', [[0, 1, 'てすと']])
        self.assertEqual(resultado, [[0, 1, 'てすと'], [2, 3, 'い']])

    def test_token_parcialmente_cubierto_se_salta(self):
        # el span existente cubre solo 洗: el token 洗濯 se salta entero
        resultado = relleno_furigana.completar('洗濯に行く', [[0, 1, 'せん']])
        self.assertEqual(resultado, [[0, 1, 'せん'], [3, 4, 'い']])

    def test_sin_kanji_no_agrega(self):
        self.assertEqual(
            relleno_furigana.completar('カタカナとひらがな。', []), [])

    def test_trim_okurigana(self):
        resultado = relleno_furigana.completar('走った', [])
        self.assertEqual(resultado, [[0, 1, 'はし']])

    def test_oracion_real_ordenada_y_disjunta(self):
        texto = 'おばあさんは川へ洗濯に行きました。'
        resultado = relleno_furigana.completar(texto, [[8, 10, 'せんたく']])
        self.assertEqual(
            resultado,
            [[6, 7, 'かわ'], [8, 10, 'せんたく'], [11, 12, 'い']])
        for (_, fin_a, _), (ini_b, _, _) in zip(resultado, resultado[1:]):
            self.assertLessEqual(fin_a, ini_b)


if __name__ == '__main__':
    unittest.main()
```

- [ ] **Step 4: Correr y verificar que falla**

Run: `python3 -m unittest tests.test_relleno_furigana -v` (desde `historias/`)
Expected: FAIL — `ImportError`/`ModuleNotFoundError: src.relleno_furigana`

- [ ] **Step 5: Implementar el módulo**

`historias/src/relleno_furigana.py`:

```python
"""Relleno de furigana faltante con análisis morfológico (janome, IPADIC).

Las fuentes Aozora traen ruby parcial; este módulo completa los huecos por
oración. El ruby original siempre gana: un token que toque un span existente
se salta entero. Trim de okurigana portado de GeneradorFurigana.kt (app);
janome usa IPADIC, el mismo diccionario que Kuromoji en la app, así las
lecturas generadas coinciden con las del texto importado.
"""
from janome.tokenizer import Tokenizer

from . import japones

_tokenizer = None  # lazy: cargar IPADIC una sola vez por proceso


def _obtener_tokenizer():
    global _tokenizer
    if _tokenizer is None:
        _tokenizer = Tokenizer()
    return _tokenizer


def _katakana_a_hiragana(texto: str) -> str:
    # mismo rango ァ..ヶ que Tokenizador.katakanaAHiragana (app)
    return ''.join(chr(ord(c) - 0x60) if 'ァ' <= c <= 'ヶ' else c
                   for c in texto)


def _terna_del_token(superficie: str, lectura: str, inicio: int) -> list:
    """[inicio, fin, lectura] con la okurigana recortada (fin exclusivo).

    Recorta prefijo/sufijo donde superficie (en hiragana) y lectura
    coinciden, sin cruzar un kanji. Si el recorte degenera, la terna cubre
    el token completo (degradación segura, mismo contrato que la app).
    """
    superficie_hira = _katakana_a_hiragana(superficie)
    pre = 0
    while (pre < len(superficie_hira) and pre < len(lectura)
           and not japones.es_kanji(superficie[pre])
           and superficie_hira[pre] == lectura[pre]):
        pre += 1
    post = 0
    while (post < len(superficie_hira) - pre
           and post < len(lectura) - pre
           and not japones.es_kanji(superficie[-1 - post])
           and superficie_hira[-1 - post] == lectura[-1 - post]):
        post += 1
    nucleo = superficie[pre:len(superficie) - post]
    lectura_nucleo = lectura[pre:len(lectura) - post]
    if any(japones.es_kanji(c) for c in nucleo) and lectura_nucleo:
        return [inicio + pre, inicio + len(superficie) - post,
                lectura_nucleo]
    return [inicio, inicio + len(superficie), lectura]


def completar(texto: str, furigana: list) -> list:
    """Ternas Aozora + generadas para los huecos, ordenadas y disjuntas."""
    cubiertos = set()
    for inicio, fin, _ in furigana:
        cubiertos.update(range(inicio, fin))
    completas = list(furigana)
    pos = 0
    for token in _obtener_tokenizer().tokenize(texto):
        superficie = token.surface
        inicio = texto.index(superficie, pos)  # janome saltea espacios
        pos = inicio + len(superficie)
        if not any(japones.es_kanji(c) for c in superficie):
            continue
        if not token.reading or token.reading == '*':
            continue  # lectura desconocida: el hueco queda como está
        if any(i in cubiertos for i in range(inicio, pos)):
            continue  # ruby Aozora gana; nunca se rellena medio token
        lectura = _katakana_a_hiragana(token.reading)
        completas.append(_terna_del_token(superficie, lectura, inicio))
    return sorted(completas, key=lambda t: t[0])
```

- [ ] **Step 6: Correr tests y verificar que pasan**

Run: `python3 -m unittest tests.test_relleno_furigana -v`
Expected: PASS (6 tests). Si algún assert de lectura exacta falla, imprimir la tokenización real (`python3 -c "..."`) y ajustar el VALOR ESPERADO del test solo si la lectura de janome es correcta en japonés; si es incorrecta, es bug del módulo.

- [ ] **Step 7: Actualizar `historias/README.md`**

Línea 5, reemplazar:

```
Python stdlib only, sin pip.
```

por:

```
Python stdlib + janome (relleno de furigana faltante, `requirements.txt`):
`python3 -m pip install --user -r requirements.txt`.
```

- [ ] **Step 8: Suite completa y commit**

```bash
python3 -m unittest discover tests -v
git add src/relleno_furigana.py tests/test_relleno_furigana.py requirements.txt README.md
git commit -m "feat(historias): relleno de furigana faltante con janome/IPADIC"
```

Expected: suite completa PASS antes del commit.

---

### Task 2: Hook en el pipeline

**Files:**
- Modify: `historias/pipeline.py:12` (import) y `historias/pipeline.py:30-34` (procesar_obra)
- Test: `historias/tests/test_pipeline.py` (agregar un test a `TestProcesarObra`)

**Interfaces:**
- Consumes: `relleno_furigana.completar(texto, furigana) -> list` (Task 1).
- Produces: `procesar_obra` devuelve historias cuyas oraciones ya tienen furigana completa. Task 3 regenera el catálogo con esto.

- [ ] **Step 1: Escribir test de integración que falla**

Agregar a `TestProcesarObra` en `historias/tests/test_pipeline.py` (el fixture ya trae `山へ` y `川へ` sin ruby y `洗濯《せんたく》` con ruby):

```python
    def test_rellena_furigana_faltante(self):
        historia = pipeline.procesar_obra(
            self.ruta_txt, {'id': 'momotaro', 'fuente': 'aozora:18376'})
        oracion = historia['parrafos'][0]['oraciones'][1]
        self.assertIn('山へ', oracion['texto'])
        lecturas = {(t[0], t[1]): t[2] for t in oracion['furigana']}
        idx_yama = oracion['texto'].index('山')
        idx_kawa = oracion['texto'].index('川')
        idx_sen = oracion['texto'].index('洗濯')
        self.assertEqual(lecturas.get((idx_yama, idx_yama + 1)), 'やま')
        self.assertEqual(lecturas.get((idx_kawa, idx_kawa + 1)), 'かわ')
        # el ruby Aozora original sigue intacto
        self.assertEqual(lecturas.get((idx_sen, idx_sen + 2)), 'せんたく')
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `python3 -m unittest tests.test_pipeline.TestProcesarObra.test_rellena_furigana_faltante -v`
Expected: FAIL — `lecturas.get(...)` devuelve `None` para 山/川 (el pipeline aún no rellena).

- [ ] **Step 3: Hookear el relleno en `procesar_obra`**

En `historias/pipeline.py`, línea 12:

```python
from src import aozora, dificultad, emisor, relleno_furigana, segmentador
```

Y en `procesar_obra`, reemplazar:

```python
    parrafos = [
        oraciones
        for texto, furigana in obra['parrafos']
        if (oraciones := segmentador.segmentar_parrafo(texto, furigana))
    ]
```

por:

```python
    parrafos = [
        [(t, relleno_furigana.completar(t, f)) for t, f in oraciones]
        for texto, furigana in obra['parrafos']
        if (oraciones := segmentador.segmentar_parrafo(texto, furigana))
    ]
```

- [ ] **Step 4: Correr suite completa**

Run: `python3 -m unittest discover tests -v`
Expected: PASS todo, incluidos los tests preexistentes de `test_pipeline.py` (assertan estructura y verify, no ternas exactas).

- [ ] **Step 5: Commit**

```bash
git add pipeline.py tests/test_pipeline.py
git commit -m "feat(historias): completar furigana faltante en procesar_obra"
```

---

### Task 3: Regenerar catálogo + verificación end-to-end + ESTADO

**Files:**
- Regenerate: `catalogo/catalogo.json`, `catalogo/historias/*.json` (10 archivos)
- Modify: `docs/ESTADO.md` (backlog feedback de uso + nota en Datos operativos)

**Interfaces:**
- Consumes: pipeline con relleno (Task 2).
- Produces: catálogo en main con furigana completa; la app lo levanta del raw URL sin release.

- [ ] **Step 1: Regenerar y verificar**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/historias
python3 pipeline.py
python3 verify_catalogo.py
```

Expected: `✓ 10 historias emitidas` y verify exit 0 (valida spans disjuntos e índices en rango sobre el catálogo YA relleno).

- [ ] **Step 2: Medir cobertura antes/después**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu
python3 - <<'EOF'
import json, glob
def es_kanji(c):
    return '一' <= c <= '鿿'
for f in sorted(glob.glob('catalogo/historias/*.json')):
    data = json.load(open(f))
    total = cubiertos = 0
    for p in data['parrafos']:
        for o in p['oraciones']:
            cov = set()
            for ini, fin, _ in o['furigana']:
                cov.update(range(ini, fin))
            for i, c in enumerate(o['texto']):
                if es_kanji(c):
                    total += 1
                    cubiertos += i in cov
    print(f"{data['id']:22s} {100*cubiertos/total:5.1f}%")
EOF
```

Expected: todas las historias muy por encima de sus valores previos (momotaro 88.4%→~100%, tebukuro 11.5%→>95%). Huecos residuales solo por lecturas desconocidas de IPADIC (p. ej. gaiji 犍). Si alguna historia queda <90%, investigar antes de commitear.

- [ ] **Step 3: Spot-check manual**

```bash
python3 - <<'EOF'
import json
data = json.load(open('catalogo/historias/momotaro.json'))
o = data['parrafos'][0]['oraciones'][1]
print(o['texto'])
print(o['furigana'])
EOF
```

Expected: 山→やま, 川→かわ, 刈→か (Aozora), 洗濯→せんたく (Aozora), 行→い. Verificar también que la canción conserva `みいず` (`grep みいず catalogo/historias/momotaro.json`).

- [ ] **Step 4: Diff sanity — solo cambian arrays furigana**

```bash
git diff --stat catalogo/
python3 - <<'EOF'
import json, glob, subprocess
for f in sorted(glob.glob('catalogo/historias/*.json')):
    viejo = json.loads(subprocess.run(
        ['git', 'show', f'HEAD:{f}'], capture_output=True, text=True).stdout)
    nuevo = json.load(open(f))
    t_viejo = [o['texto'] for p in viejo['parrafos'] for o in p['oraciones']]
    t_nuevo = [o['texto'] for p in nuevo['parrafos'] for o in p['oraciones']]
    assert t_viejo == t_nuevo, f'{f}: TEXTOS CAMBIARON'
print('textos idénticos: progreso guardado no se corre')
EOF
```

Expected: `textos idénticos: progreso guardado no se corre`. Si algún texto cambió, ABORTAR y investigar (el relleno no debe tocar textos).

- [ ] **Step 5: Actualizar `docs/ESTADO.md`**

En `## Backlog feedback de uso (2026-07-13 — leyendo momotaro)`, sección App Dokusho, tachar los tres ítems de furigana:

```markdown
- ~~Furigana faltante en momotaro: por alguna razón siempre falta antes de へ (catálogo → alineador `historias/src/aozora.py`, no Kuromoji).~~ Resuelto: la fuente Aozora trae ruby parcial (no era bug del alineador); relleno con janome en el pipeline.
- ~~Faltan muchas más furiganas en general (auditar cobertura).~~ Resuelto: cobertura 11.5%–92.9% → ~100% con relleno janome/IPADIC.
- ~~Algunas furiganas están mal: p. ej. 水 = "miizu" (¿みいず?) en vez de みず — revisar origen (ruby Aozora vs `GeneradorFurigana`/Kuromoji).~~ No es bug: `水《みいず》` es la canción del cuento (alarga vocales: かあらいぞ/ああまいぞ); fiel al original.
```

En `## Datos operativos`, agregar al bullet **Catálogo**: `Furigana completa desde 2026-07-13: huecos de ruby Aozora rellenados con janome/IPADIC en el pipeline (spec docs/superpowers/specs/2026-07-13-furigana-relleno-catalogo-design.md); el invariante byte-idéntico de tanda 1 dejó de valer.`

- [ ] **Step 6: Commit final**

```bash
git add catalogo/ docs/ESTADO.md
git commit -m "feat(catalogo): furigana completa — relleno janome/IPADIC de huecos de ruby Aozora"
```

---

## Verificación end-to-end (post-merge)

1. Suite: `cd historias && python3 -m unittest discover tests -v` → verde.
2. `python3 verify_catalogo.py` → exit 0.
3. Push a main → abrir la app (beta.1 instalada), refrescar biblioteca, abrir momotaro: 山へ/川へ con furigana; canción con みいず; tebukuro_wo_kaini ya no pelado.
