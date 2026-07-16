# Traducciones literales en el catálogo (PR B) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emitir traducción literal al inglés por oración en el catálogo (campo `traduccion`, hoy siempre null), con fuentes versionadas en `historias/traducciones/<id>.json`, y regenerar el catálogo de las 10 historias.

**Architecture:** Las traducciones se generan one-off (LLM, estilo literal — NO inglés funcional) y viven como archivos fuente versionados; el pipeline las mergea por posición validando conteo Y texto exacto por oración (falla ruidoso ante cualquier drift de segmentación). `verify_catalogo` pasa de "traduccion siempre null" a "null o string no vacío, all-or-nothing por historia".

**Tech Stack:** Python stdlib + janome (ya instalado, 0.5.0). Tests unittest (`python3 -m unittest discover tests -v` desde `historias/`).

**Spec:** `docs/superpowers/specs/2026-07-16-backlog-feedback-uso-design.md` (sección PR B)

## Global Constraints

- Branch: `feature/traducciones-catalogo` desde `main`. NO tocar `app/` ni `diccionario/`.
- Fuentes Aozora ya presentes en `historias/fuentes/` (10 .txt); cwd para comandos = `historias/`.
- Contrato: `traduccion` = string no vacío o null; schema v2 intacto (sin bump — el campo ya existe); textos y furigana NO cambian → el progreso guardado en la app no se corre.
- Estilo de traducción: **inglés literal** (estructura japonesa preservada, glosas directas); nunca inglés natural/funcional.
- Falla ruidoso: conteo o texto despareado entre traducciones y oraciones aborta la emisión.
- Formato de `historias/traducciones/<id>.json`:
  ```json
  {"id": "<id>", "oraciones": [{"texto": "<oración exacta del catálogo>", "traduccion": "<inglés literal>"}, ...]}
  ```
  Lista plana en orden párrafo→oración (mismo recorrido que `construir_historia`).
- Comentarios/docstrings en español, estilo calcado de `historias/src/`.

---

### Task 1: Baseline de reproducibilidad del pipeline

**Files:** ninguno (verificación previa).

- [ ] **Step 1: Regenerar a un dir temporal y comparar con el catálogo commiteado**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/historias
python3 pipeline.py --salida /tmp/catalogo-baseline
diff -r /tmp/catalogo-baseline /Users/tatoh/Repos/dokusho-renshuu/catalogo && echo BASELINE-OK
```

Expected: `BASELINE-OK` (byte-idéntico). Si difiere, STOP y reportar: el entorno no reproduce el catálogo actual y el regen mezclaría cambios espurios.

- [ ] **Step 2: Suite existente verde**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/historias && python3 -m unittest discover tests -v 2>&1 | tail -3
```

Expected: OK.

---

### Task 2: `emisor.construir_historia` acepta traducciones

**Files:**
- Modify: `historias/src/emisor.py` (`construir_historia`, líneas 16-43)
- Test: `historias/tests/test_emisor.py`

**Interfaces:**
- Produces: `construir_historia(..., parrafos, traducciones=None)` — `traducciones` = lista de dicts `{'texto': str, 'traduccion': str}` plana en orden párrafo→oración, o None (comportamiento actual: `traduccion: None`). Valida conteo y texto exacto; `traduccion` debe ser string no vacío. `ValueError` ante cualquier desajuste.

- [ ] **Step 1: Tests que fallan** (en `test_emisor.py`, estilo unittest del archivo; el helper `_historia()` existente arma parrafos de 2 oraciones)

```python
def test_construir_con_traducciones_emite_strings(self):
    parrafos = [[('桃太郎は強い。', []), ('川へ行った。', [])]]
    traducciones = [
        {'texto': '桃太郎は強い。', 'traduccion': 'Momotaro is strong.'},
        {'texto': '川へ行った。', 'traduccion': 'To the river (he) went.'},
    ]
    historia = emisor.construir_historia(
        id_='t', titulo='T', autor='A', fuente='F', licencia='L',
        dificultad='facil', parrafos=parrafos, traducciones=traducciones)
    oraciones = historia['parrafos'][0]['oraciones']
    self.assertEqual('Momotaro is strong.', oraciones[0]['traduccion'])
    self.assertEqual('To the river (he) went.', oraciones[1]['traduccion'])

def test_sin_traducciones_sigue_emitiendo_null(self):
    historia = self._historia()  # helper existente, sin traducciones
    for parrafo in historia['parrafos']:
        for oracion in parrafo['oraciones']:
            self.assertIsNone(oracion['traduccion'])

def test_conteo_despareja_aborta(self):
    parrafos = [[('桃太郎は強い。', []), ('川へ行った。', [])]]
    with self.assertRaises(ValueError):
        emisor.construir_historia(
            id_='t', titulo='T', autor='A', fuente='F', licencia='L',
            dificultad='facil', parrafos=parrafos,
            traducciones=[{'texto': '桃太郎は強い。', 'traduccion': 'x'}])

def test_texto_despareja_aborta(self):
    parrafos = [[('桃太郎は強い。', [])]]
    with self.assertRaises(ValueError):
        emisor.construir_historia(
            id_='t', titulo='T', autor='A', fuente='F', licencia='L',
            dificultad='facil', parrafos=parrafos,
            traducciones=[{'texto': 'OTRO TEXTO。', 'traduccion': 'x'}])

def test_traduccion_vacia_aborta(self):
    parrafos = [[('桃太郎は強い。', [])]]
    with self.assertRaises(ValueError):
        emisor.construir_historia(
            id_='t', titulo='T', autor='A', fuente='F', licencia='L',
            dificultad='facil', parrafos=parrafos,
            traducciones=[{'texto': '桃太郎は強い。', 'traduccion': ''}])
```

- [ ] **Step 2: RED** — `cd historias && python3 -m unittest tests.test_emisor -v` → TypeError/failures esperados.

- [ ] **Step 3: Implementar.** En `construir_historia`:

```python
def construir_historia(id_, titulo, autor, fuente, licencia,
                       dificultad, parrafos, traducciones=None) -> dict:
    """parrafos = [[(texto, furigana), ...] por párrafo] → dict del spec.

    `traducciones` (opcional, backlog feedback de uso 2026-07-13): lista
    plana de {'texto', 'traduccion'} en orden párrafo→oración — el mismo
    recorrido de este constructor. Se valida conteo Y texto exacto por
    posición (falla ruidoso ante cualquier drift de segmentación entre el
    archivo de traducciones y la fuente); `traduccion` debe ser string no
    vacío. Sin traducciones, `traduccion` queda null (comportamiento v1).

    Incluye `kanjis_unicos` y `oraciones` en el dict en memoria (insumo
    para el catálogo v2); no forman parte del schema del JSON de historia
    en disco — ver `_CLAVES_HISTORIA_EN_DISCO`.
    """
    texto_completo = ''.join(
        texto for oraciones in parrafos for texto, _ in oraciones)
    planas = [texto for oraciones in parrafos for texto, _ in oraciones]
    por_oracion = _validar_traducciones(id_, planas, traducciones)
    contador = iter(range(len(planas)))
    return {
        # ... claves id/titulo/.../oraciones idénticas ...
        'parrafos': [
            {'oraciones': [
                {'texto': texto, 'furigana': furigana,
                 'traduccion': por_oracion[next(contador)]}
                for texto, furigana in oraciones
            ]}
            for oraciones in parrafos
        ],
    }


def _validar_traducciones(id_, planas, traducciones):
    """None → lista de None (v1). Con traducciones: conteo y texto exactos."""
    if traducciones is None:
        return [None] * len(planas)
    if len(traducciones) != len(planas):
        raise ValueError(
            f'{id_}: {len(traducciones)} traducciones para '
            f'{len(planas)} oraciones')
    resultado = []
    for i, (texto, entrada) in enumerate(zip(planas, traducciones)):
        if entrada.get('texto') != texto:
            raise ValueError(
                f'{id_} oración {i}: texto de traducción no coincide '
                f'({entrada.get("texto", "")[:20]!r} != {texto[:20]!r})')
        traduccion = entrada.get('traduccion')
        if not isinstance(traduccion, str) or not traduccion:
            raise ValueError(
                f'{id_} oración {i}: traduccion inválida {traduccion!r}')
        resultado.append(traduccion)
    return resultado
```

(El implementer integra el cuerpo con las claves existentes tal cual están; solo cambian `parrafos` y la firma/docstring.)

- [ ] **Step 4: GREEN** — mismo comando, tests nuevos + viejos (incluido `test_estructura_del_spec`, que sigue valiendo sin traducciones).

- [ ] **Step 5: Commit** — `feat(historias): construir_historia mergea traducciones literales por oración`

---

### Task 3: `pipeline.py` carga `traducciones/<id>.json`

**Files:**
- Modify: `historias/pipeline.py`
- Test: `historias/tests/test_pipeline.py` (agregar caso; calcar el patrón de fixtures existente del archivo)

**Interfaces:**
- Consumes: `construir_historia(..., traducciones=...)` (Task 2)
- Produces: `procesar_obra(ruta_txt, declaracion, dir_traducciones=None)`; CLI `--traducciones` default `traducciones`. Historia sin archivo → sin traducciones (null, comportamiento actual).

- [ ] **Step 1: Test que falla** (en `test_pipeline.py`; usar el fixture Aozora mínimo que ya usa el archivo, escribir un `traducciones/<id>.json` en un tempdir con los textos EXACTOS que emite el fixture — obtenerlos procesando primero sin traducciones):

```python
def test_procesar_obra_con_traducciones(self):
    # primero sin traducciones para conocer las oraciones emitidas
    base = pipeline.procesar_obra(self.ruta_txt, self.declaracion)
    planas = [o['texto'] for p in base['parrafos'] for o in p['oraciones']]
    dir_trad = tempfile.mkdtemp()
    self.addCleanup(shutil.rmtree, dir_trad)
    with open(os.path.join(dir_trad, f"{self.declaracion['id']}.json"),
              'w', encoding='utf-8') as f:
        json.dump({'id': self.declaracion['id'],
                   'oraciones': [{'texto': t, 'traduccion': f'lit {i}'}
                                 for i, t in enumerate(planas)]},
                  f, ensure_ascii=False)
    con_trad = pipeline.procesar_obra(
        self.ruta_txt, self.declaracion, dir_traducciones=dir_trad)
    emitidas = [o['traduccion']
                for p in con_trad['parrafos'] for o in p['oraciones']]
    self.assertEqual([f'lit {i}' for i in range(len(planas))], emitidas)

def test_procesar_obra_sin_archivo_de_traducciones_emite_null(self):
    dir_trad = tempfile.mkdtemp()
    self.addCleanup(shutil.rmtree, dir_trad)
    historia = pipeline.procesar_obra(
        self.ruta_txt, self.declaracion, dir_traducciones=dir_trad)
    self.assertTrue(all(o['traduccion'] is None
                        for p in historia['parrafos']
                        for o in p['oraciones']))
```

(Adaptar `self.ruta_txt`/`self.declaracion` a los nombres reales del setUp existente.)

- [ ] **Step 2: RED**, **Step 3: Implementar:**

```python
def cargar_traducciones(dir_traducciones: str, id_: str):
    """Lee traducciones/<id>.json si existe; None si la historia no tiene
    (emite traduccion: null, comportamiento v1). El contenido se valida en
    emisor.construir_historia (conteo + texto exacto, falla ruidoso)."""
    if dir_traducciones is None:
        return None
    ruta = os.path.join(dir_traducciones, f'{id_}.json')
    if not os.path.exists(ruta):
        return None
    with open(ruta, encoding='utf-8') as f:
        return json.load(f)['oraciones']
```

`procesar_obra(ruta_txt, declaracion, dir_traducciones=None)` pasa `traducciones=cargar_traducciones(dir_traducciones, declaracion['id'])` a `construir_historia`. En `main()`: `parser.add_argument('--traducciones', default='traducciones')` y `procesar_obra(..., dir_traducciones=args.traducciones)`.

- [ ] **Step 4: GREEN** (suite completa), **Step 5: Commit** — `feat(historias): pipeline carga traducciones/<id>.json y las mergea al emitir`

---

### Task 4: `verify_catalogo` acepta traducciones + README

**Files:**
- Modify: `historias/verify_catalogo.py` (líneas 50-51 y contadores por historia)
- Modify: `historias/README.md` (contrato `traduccion`, sección Uso, estructura de `traducciones/`)
- Test: `historias/tests/test_verify.py`

**Interfaces:**
- Produces: regla nueva — `traduccion` válida si es null o string no vacío; dentro de una historia es all-or-nothing (mezcla null/string → error, garantiza cobertura 100% donde hay archivo de traducciones).

- [ ] **Step 1: Tests que fallan** (en `test_verify.py`, patrón `_corromper_historia` existente):

```python
def test_traduccion_string_no_vacio_es_valida(self):
    self._corromper_historia(lambda h: [
        o.update(traduccion=f'lit {i}')
        for i, o in enumerate(
            o for p in h['parrafos'] for o in p['oraciones'])])
    # _corromper_historia cambia bytes: re-sincronizar tamaño en catálogo
    self._sincronizar_tamanio()
    self.assertEqual([], verify_catalogo.verificar(self.dir_catalogo))

def test_traduccion_vacia_es_error(self):
    self._corromper_historia(lambda h: [
        o.update(traduccion='')
        for o in (o for p in h['parrafos'] for o in p['oraciones'])])
    self._sincronizar_tamanio()
    errores = verify_catalogo.verificar(self.dir_catalogo)
    self.assertTrue(any('traduccion inválida' in e for e in errores), errores)

def test_mezcla_de_traduccion_y_null_es_error(self):
    def solo_la_primera(h):
        h['parrafos'][0]['oraciones'][0]['traduccion'] = 'lit 0'
    self._corromper_historia(solo_la_primera)
    self._sincronizar_tamanio()
    errores = verify_catalogo.verificar(self.dir_catalogo)
    self.assertTrue(any('cobertura parcial' in e for e in errores), errores)
```

(El implementer adapta a los helpers reales del archivo; si `_corromper_historia` no re-sincroniza `tamaño`, agregar helper local que reescriba la entrada del catálogo con `os.path.getsize` — mirar cómo lo resuelven los tests existentes de tamaño.)

- [ ] **Step 2: RED**, **Step 3: Implementar.** En `_verificar_historia`, reemplazar líneas 50-51 por contadores:

```python
    con_traduccion = 0
    sin_traduccion = 0
```
(inicializados antes del loop de párrafos) y dentro del loop de oraciones:
```python
            traduccion = oracion.get('traduccion')
            if traduccion is None:
                sin_traduccion += 1
            elif isinstance(traduccion, str) and traduccion:
                con_traduccion += 1
            else:
                errores.append(f'{donde}: traduccion inválida {traduccion!r}')
```
y al final de la función:
```python
    if con_traduccion and sin_traduccion:
        errores.append(
            f'{id_}: cobertura parcial de traducciones '
            f'({con_traduccion} con, {sin_traduccion} sin) — all-or-nothing')
```

README: actualizar línea del contrato ("`traduccion` siempre `null`…" → "null o string no vacío (inglés literal); fuentes en `traducciones/<id>.json`, all-or-nothing por historia"), agregar `traducciones/` al flujo de Uso y a "Agregar una obra" (opcional por historia).

- [ ] **Step 4: GREEN** (suite completa), **Step 5: Commit** — `feat(historias): verify_catalogo acepta traducciones (null o string, all-or-nothing)`

---

### Task 5: Generar las traducciones de las 10 historias

**Files:**
- Create: `historias/traducciones/momotaro.json` (+ los otros 9 ids: issunboshi, gongitsune, urashima_taro, kachikachi_yama, kintaro, shitakiri_suzume, tebukuro_wo_kaini, hanasaka_jijii, kumo_no_ito)

Conteos esperados (oraciones por archivo): momotaro 174, issunboshi 173, gongitsune 151, urashima_taro 148, kachikachi_yama 144, kintaro 123, shitakiri_suzume 117, tebukuro_wo_kaini 88, hanasaka_jijii 78, kumo_no_ito 53 (total 1249).

- [ ] **Step 1: Generación LLM (controller: un subagente por historia).** Cada subagente lee `catalogo/historias/<id>.json`, extrae las oraciones en orden párrafo→oración y escribe `historias/traducciones/<id>.json` con el formato del Global Constraints. Instrucciones de estilo (van en el prompt de cada subagente):
  - Inglés LITERAL: preservar el orden y la estructura del japonés donde el inglés lo tolere; partículas y construcciones traducidas de forma transparente (へ = "to", は marca tópico, など = "and such"). Sujetos elididos entre paréntesis: "(he) went".
  - NO naturalizar, NO parafrasear, NO embellecer. La traducción es un andamio de lectura, no literatura.
  - Onomatopeyas: transliterar + glosa breve entre paréntesis ("donburakokko (tumbling along)").
  - Nombres propios: romaji (Momotaro, Urashima Taro).
  - `texto` copiado EXACTO del catálogo (byte a byte, incluida la puntuación japonesa).
- [ ] **Step 2: Validación mecánica por archivo** (controller, script rápido): conteo == esperado, textos == catálogo en orden, traducciones todas string no vacío:

```bash
cd /Users/tatoh/Repos/dokusho-renshuu && python3 - <<'EOF'
import json
esperados = {'momotaro':174,'issunboshi':173,'gongitsune':151,'urashima_taro':148,
             'kachikachi_yama':144,'kintaro':123,'shitakiri_suzume':117,
             'tebukuro_wo_kaini':88,'hanasaka_jijii':78,'kumo_no_ito':53}
for id_, n in esperados.items():
    cat = json.load(open(f'catalogo/historias/{id_}.json'))
    planas = [o['texto'] for p in cat['parrafos'] for o in p['oraciones']]
    trad = json.load(open(f'historias/traducciones/{id_}.json'))['oraciones']
    assert len(trad) == n == len(planas), (id_, len(trad), n)
    for i, (t, e) in enumerate(zip(planas, trad)):
        assert e['texto'] == t, (id_, i, e['texto'][:20], t[:20])
        assert isinstance(e['traduccion'], str) and e['traduccion'], (id_, i)
print('TRADUCCIONES-OK')
EOF
```

- [ ] **Step 3: Spot-check de calidad** (controller o reviewer): muestrear ~5 oraciones por historia y verificar estilo literal (no funcional).
- [ ] **Step 4: Commit** — `feat(historias): traducciones literales en inglés de las 10 historias (fuentes LLM one-off)`

---

### Task 6: Regenerar catálogo + verify

**Files:**
- Modify (generados): `catalogo/catalogo.json`, `catalogo/historias/*.json`

- [ ] **Step 1: Regen + verify**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/historias
python3 pipeline.py && python3 verify_catalogo.py
```

Expected: `✓ OK`. Los `tamaño` del catálogo cambian (esperado — es lo que dispara el botón Update del PR A en apps instaladas).

- [ ] **Step 2: Sanity del diff**: `git diff --stat catalogo/` — 11 archivos; en el diff de una historia solo cambian los valores de `"traduccion"` (textos y furigana intactos → progreso guardado no se corre):

```bash
cd /Users/tatoh/Repos/dokusho-renshuu && git diff catalogo/historias/momotaro.json | grep -c '^[-+].*"texto"' # Expected: 0
```

- [ ] **Step 3: Suite completa final** (`python3 -m unittest discover tests -v`) + **Commit** — `feat(catalogo): regen con traducciones literales (tamaños nuevos → apps ven Update)`

---

### Task 7: Review final + PR + ESTADO

- [ ] Review final de branch (proceso del repo), PR contra `main` referenciando el spec y el PR #13 (el botón Update de la app es lo que hace llegar este catálogo a apps instaladas).
- [ ] Actualizar `docs/ESTADO.md` en el mismo PR: fila B en la tabla, item de decks "traducción literal" parcialmente cubierto (queda el lado app/tarjetas para PR C), datos operativos (existe `historias/traducciones/`, contrato all-or-nothing).

## Verificación global

1. Suite `historias/` verde; `verify_catalogo.py` ✓ OK.
2. Validación mecánica TRADUCCIONES-OK (Task 5 Step 2).
3. Diff de catálogo solo toca `traduccion` y `tamaño`.
4. Post-merge (con PR A instalado en tablet): las 10 historias muestran botón Update; tras actualizar una, el lector sigue en la misma posición.
