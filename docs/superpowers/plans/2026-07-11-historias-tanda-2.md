# Tanda 2 de historias base — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sumar 6 historias nuevas al catálogo (4 → ~10) usando el pipeline existente de `historias/`, sin código nuevo.

**Architecture:** Tanda de contenido: verificar cards en Aozora → bajar zips ruby a `fuentes/` → 6 entradas en `obras.json` → `pipeline.py` regenera `catalogo/` → sanity manual → rebuild de la app (assets) → docs → PR. Spec: `docs/superpowers/specs/2026-07-11-historias-tanda-2-design.md`.

**Tech Stack:** Python 3 stdlib (pipeline Plan 2), curl/unzip, gradle (solo rebuild final).

## Global Constraints

- Branch `feature/historias-tanda-2` desde main.
- **Invariante crítico**: los 4 JSON existentes en `catalogo/historias/` quedan **byte-idénticos** tras regenerar (`git diff` no debe tocarlos). Si difieren: STOP, no commitear, reportar BLOCKED — algo cambió en fuentes o pipeline.
- Ids exactos del spec: `hanasaka_jijii`, `shitakiri_suzume`, `kintaro`, `gongitsune`, `tebukuro_wo_kaini`, `kumo_no_ito` (suplentes: `saru_kani_gassen`, `chumon_no_oi_ryoriten`).
- Regla de suplentes: obra sin versión ruby / formato que el pipeline no maneja / sanity fallido → reemplazar por S1, luego S2; mínimo 4 nuevas para cerrar la tanda.
- La dificultad la calcula el pipeline — NO editarla a mano en los JSON.
- `fuentes/` es gitignored: los `.txt` NO se commitean; `obras.json` + `catalogo/` sí.
- Comandos Python desde `historias/`; gradle desde `app/` (JDK 21).

---

### Task 1: Fuentes — localizar cards en Aozora y bajar los zips

**Files:**
- Create (gitignored, no se commitean): `historias/fuentes/{hanasaka_jijii,shitakiri_suzume,kintaro,gongitsune,tebukuro_wo_kaini,kumo_no_ito}.txt`
- Create: `.superpowers/sdd/tanda2-cards.md` (tabla de trabajo: obra → card → URL zip, para Task 2)

**Interfaces:**
- Produces: los 6 `.txt` en `historias/fuentes/` (Shift_JIS, ruby `《》`) + `tanda2-cards.md` con columnas `id | card | url_zip` que Task 2 copia a `obras.json`.

- [ ] **Step 1: Crear branch**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu
git checkout -b feature/historias-tanda-2
```

- [ ] **Step 2: Localizar las cards por autor en Aozora**

Índices por autor (verificados: las 4 obras actuales viven en `cards/000329/` = 楠山正雄):

```bash
# 楠山正雄 (person329): 花咲かじじい, したきりすずめ, 金太郎 (+S1 猿かに合戦)
curl -s https://www.aozora.gr.jp/index_pages/person329.html | iconv -f utf-8 -t utf-8 -c | grep -oE 'card[0-9]+\.html">[^<]+' | sort -u
# 新美南吉: buscar su person page — está enlazada desde cualquier card suya;
# encontrarla vía búsqueda del índice general:
curl -s "https://www.aozora.gr.jp/index_pages/sakuhin_ko1.html" | grep -B2 -A2 "ごん狐" | head -20
# (alternativa: la página de autor de 新美南吉 es index_pages/person121.html — verificar
# que el título ごん狐 aparezca antes de usarla; si no, navegar desde la búsqueda)
# 芥川龍之介 (person879): 蜘蛛の糸
curl -s https://www.aozora.gr.jp/index_pages/person879.html | grep -oE 'card[0-9]+\.html">[^<]+' | grep 蜘蛛
```

Nota: los numbers de person page de 新美/芥川 son hipótesis razonables — VERIFICAR contra el contenido real (el título de la obra tiene que aparecer). Si un grep no matchea, abrir el HTML y buscar a mano. No inventar card IDs.

- [ ] **Step 3: De cada card, extraer la URL del zip ruby**

Para cada card encontrada (ejemplo con una card hipotética `cards/000329/cardNNNN.html`):

```bash
curl -s https://www.aozora.gr.jp/cards/000329/cardNNNN.html | grep -oE 'files/[0-9]+_ruby_[0-9]+\.zip'
```

Debe existir un `*_ruby_*.zip` (versión con furigana). Si la card solo tiene `*_txt_*.zip` (sin ruby), la obra NO sirve → suplente (constraint global).

- [ ] **Step 4: Registrar la tabla de trabajo**

Escribir `.superpowers/sdd/tanda2-cards.md`:

```markdown
| id | card | url_zip |
|----|------|---------|
| hanasaka_jijii | https://www.aozora.gr.jp/cards/000329/cardNNNN.html | https://www.aozora.gr.jp/cards/000329/files/NNNN_ruby_XXXXX.zip |
| ... (6 filas) |
```

- [ ] **Step 5: Bajar y renombrar**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/historias
mkdir -p fuentes && cd fuentes
# por cada obra (ejemplo hanasaka_jijii):
curl -sO <url_zip>
unzip -o <nombre>.zip && rm <nombre>.zip
mv <txt_extraido>.txt hanasaka_jijii.txt
```

Cada zip trae UN `.txt` (Shift_JIS). Verificar los 6:

```bash
ls -la fuentes/*.txt | wc -l   # las 4 viejas + 6 nuevas = 10
file fuentes/hanasaka_jijii.txt  # ISO-8859 / Non-ISO extended-ASCII (Shift_JIS no se autodetecta — OK)
head -c 200 fuentes/hanasaka_jijii.txt | iconv -f cp932 -t utf-8  # título legible, sin basura
```

Esperado: el `iconv` de cada archivo muestra el título de la obra en la primera línea.

- [ ] **Step 6: Commit** (solo la tabla de trabajo NO va al repo; `fuentes/` es gitignored — no hay nada que commitear en esta task; verificar con `git status --short` que esté limpio)

```bash
git status --short   # vacío (fuentes/ ignorado, tanda2-cards.md en .superpowers/ ignorado)
```

---

### Task 2: obras.json + pipeline + verificación

**Files:**
- Modify: `historias/obras.json` (+6 entradas)
- Regenerated: `catalogo/catalogo.json`, `catalogo/historias/*.json` (+6 nuevos, 4 viejos byte-idénticos)

**Interfaces:**
- Consumes: `historias/fuentes/*.txt` y `.superpowers/sdd/tanda2-cards.md` (Task 1).
- Produces: catálogo regenerado y verificado que consumen Tasks 3-4.

- [ ] **Step 1: Agregar las 6 entradas a `obras.json`**

Formato exacto (mismo de las entradas existentes), con `fuente` = `aozora:<card>` y `url` de la tabla de trabajo:

```json
{
 "id": "hanasaka_jijii",
 "archivo": "hanasaka_jijii.txt",
 "fuente": "aozora:<card>",
 "titulo_lectura": "はなさかじじい",
 "titulo_en": "The Old Man Who Made Flowers Bloom",
 "url": "<url_zip>"
}
```

Las 6 (id → titulo_lectura → titulo_en):
- `hanasaka_jijii` → はなさかじじい → "The Old Man Who Made Flowers Bloom"
- `shitakiri_suzume` → したきりすずめ → "The Tongue-Cut Sparrow"
- `kintaro` → きんたろう → "Kintarō"
- `gongitsune` → ごんぎつね → "Gon, the Little Fox"
- `tebukuro_wo_kaini` → てぶくろをかいに → "Buying Mittens"
- `kumo_no_ito` → くものいと → "The Spider's Thread"

(Suplentes si hicieron falta: `saru_kani_gassen` → さるかにがっせん → "The Monkey and the Crab"; `chumon_no_oi_ryoriten` → ちゅうもんのおおいりょうりてん → "The Restaurant of Many Orders".)

- [ ] **Step 2: Correr el pipeline**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/historias
python3 pipeline.py
```

Esperado: exit 0, un JSON nuevo por obra en `../catalogo/historias/`.

- [ ] **Step 3: Verificar el invariante byte-idéntico**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu
git diff --stat catalogo/
```

Esperado: SOLO `catalogo/catalogo.json` modificado + 6 archivos nuevos. Si `momotaro.json`/`urashima_taro.json`/`issunboshi.json`/`kachikachi_yama.json` aparecen modificados → STOP, BLOCKED (constraint global).

- [ ] **Step 4: Verificación automática**

```bash
cd historias
python3 verify_catalogo.py && echo VERIFY-OK
python3 -m unittest discover tests 2>&1 | tail -3
```

Esperado: `VERIFY-OK` + `OK` de unittest (67 tests según baseline 3.7).

- [ ] **Step 5: Commit**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu
git add historias/obras.json catalogo/
git commit -m "feat(historias): tanda 2 — 6 obras nuevas al catálogo"
```

---

### Task 3: Sanity manual por historia

**Files:** ninguno nuevo — reporte a `.superpowers/sdd/tanda2-sanity.md`.

**Interfaces:**
- Consumes: `catalogo/historias/{id}.json` de las 6 nuevas + `historias/fuentes/{id}.txt`.

- [ ] **Step 1: Por cada una de las 6 historias, correr este check** (snippet completo, ajustar `ID`):

```bash
cd /Users/tatoh/Repos/dokusho-renshuu
python3 - <<'EOF'
import json
for id in ["hanasaka_jijii","shitakiri_suzume","kintaro","gongitsune","tebukuro_wo_kaini","kumo_no_ito"]:
    h = json.load(open(f"catalogo/historias/{id}.json"))
    oraciones = [o for p in h["parrafos"] for o in p["oraciones"]]
    primera, ultima = oraciones[0], oraciones[-1]
    kanjis = len({c for o in oraciones for c in o["texto"] if '一' <= c <= '鿿'})
    print(f"=== {id}: dificultad={h['dificultad']} parrafos={len(h['parrafos'])} oraciones={len(oraciones)} kanjis_unicos={kanjis}")
    print(f"  primera: {primera['texto'][:60]}")
    print(f"  ultima:  {ultima['texto'][-60:]}")
    print(f"  furigana muestra: {primera['furigana'][:3]}")
EOF
```

- [ ] **Step 2: Evaluar cada output contra la fuente**

Por historia:
1. **Sin mojibake**: primera/última oración son japonés legible y coinciden con el texto real de la obra (comparar contra `iconv -f cp932 -t utf-8 fuentes/{id}.txt | head/tail`).
2. **Sin residuos Aozora**: la primera oración NO es un numeral de sección (一, 二…) ni metadata; la última NO es parte del colofón (`底本:`…).
3. **Furigana coherente**: las 2-3 primeras ternas apuntan a kanji del texto y la lectura es plausible (verificar a ojo con la oración impresa).
4. **Dificultad razonable**: easy/media para 楠山 y 新美, media/dificil para 芥川 — si da algo absurdo (蜘蛛の糸 = "facil"), investigar antes de aceptar.
5. **Tamaño razonable**: oraciones > 30 (una obra de 5 oraciones = extracción rota).

- [ ] **Step 3: Registrar veredicto** en `.superpowers/sdd/tanda2-sanity.md` (una línea por historia: OK / problema encontrado). Si alguna falla → aplicar regla de suplentes: volver a Task 1 Step 3 para la suplente, rehacer Task 2 para esa obra, re-sanity. Reportar en el resultado qué suplentes entraron.

---

### Task 4: App — rebuild + suite + smoke en tablet

**Files:** ninguno (la app no cambia; solo assets regenerados por gradle).

- [ ] **Step 1: Rebuild + suite completa**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/app
./gradlew test -q --console=plain
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=0
for x in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    r=ET.parse(x).getroot(); t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors'))
print(f'suite: {t} tests, {f} failures, {e} errors')"
./gradlew :app:assembleDebug -q --console=plain
```

Esperado: 187/187 (los tests de la app no dependen del número de historias del catálogo real — usan fixtures propias; si algo falla por conteos hardcodeados, revisar qué test y reportar) + BUILD SUCCESSFUL.

OJO: los asserts de `ParserHistoriaTest` hardcodean stats del catálogo real (217/174 de momotaro — ledger P3.5 T9). Momotaro NO cambia (invariante byte-idéntico), así que deben seguir verdes. Si el fixture del test usa `catalogo.json` completo, el conteo de historias del catálogo SÍ cambió (4→10) — ajustar SOLO el assert del conteo si existe, nada más.

- [ ] **Step 2: Smoke en tablet** (device `a989173e`, correr por el controller — instalar `app/build/outputs/apk/debug/app-debug.apk`):
  1. Biblioteca muestra ~10 tarjetas (4 viejas + 6 nuevas + importada "inu" si sigue).
  2. Abrir una nueva (p.ej. ごん狐): portada con metadata, lector con furigana, tap de palabra abre el sheet.
  3. Export: counts dicen "~10 stories", checkboxes listan todas.

- [ ] **Step 3: Commit** (si Step 1 requirió ajustar algún assert)

```bash
git add -A app/app/src
git commit -m "test(app): ajuste de conteos del catálogo real (tanda 2)"
```

(Si no hubo ajustes, no hay commit en esta task.)

---

### Task 5: Docs + PR

**Files:**
- Modify: `historias/README.md` (tabla de obras: +6 filas con card links)
- Modify: `docs/ESTADO.md`

- [ ] **Step 1: README** — agregar las 6 obras a la tabla "Obras (declaradas en `obras.json`)" con el formato existente: `| id | Obra — Autor | [card](url card) |`.

- [ ] **Step 2: ESTADO.md**:
  - En "Datos operativos", actualizar la línea de **Catálogo**: ahora ~10 historias (listar ids nuevos), tanda 2 con autores 楠山正雄/新美南吉/芥川龍之介.
  - En "Backlog diferido (Plan 4b)": marcar resuelto (o borrar) el ítem "agregar más historias base al catálogo".
  - Fila nueva en la tabla de planes: `| 4c | catalogo/ — tanda 2 de historias (6 obras) | ✅ Completo (PR pendiente — actualizar con #N al abrir) |` (o el número que siga la convención).

- [ ] **Step 3: Commit + push + PR**

```bash
git add historias/README.md docs/ESTADO.md
git commit -m "docs: tanda 2 de historias — README y estado"
git push -u origin feature/historias-tanda-2
gh pr create --title "Historias tanda 2: 6 obras nuevas al catálogo" --body "..."
```

Body del PR: resumen real (qué obras entraron, dificultades calculadas, si hubo suplentes), invariante byte-idéntico verificado, suite + smoke. Cerrar con la línea de atribución de Claude Code.

- [ ] **Step 4: Actualizar ESTADO con el número de PR real** (segundo commit chico, mismo patrón de siempre).

---

## Self-Review (hecho al escribir)

- **Cobertura del spec**: proceso 1-2 → Task 1; 3-5 → Task 2; 6 → Task 3; 7 → Task 4; 8-9 → Task 5. Regla de suplentes → Task 1 Step 3 + Task 3 Step 3. Invariante byte-idéntico → constraint global + Task 2 Step 3. ✔
- **Placeholders**: los card IDs/URLs van como `NNNN`/`<url_zip>` A PROPÓSITO — el spec prohíbe inventarlos; la Task 1 los descubre empíricamente y la tabla de trabajo los transporta a Task 2. Los person-page numbers de 新美/芥川 están marcados como hipótesis a verificar. ✔
- **Consistencia**: ids idénticos en constraint global, Task 1 files, Task 2 entradas y Task 3 snippet. ✔
