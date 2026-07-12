# Export Grid Adaptivo — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** La lista de historias del Export aprovecha el ancho en tablet: grid adaptivo (~2 columnas portrait, ~3 landscape) en vez de una sola columna que deja la pantalla vacía en landscape.

**Architecture:** Swap puro de contenedor en `ExportScreen`: `LazyColumn` → `LazyVerticalGrid(GridCells.Adaptive(300.dp))`, mismo `weight(1f, fill = false)`, misma fila. Spec: addendum 2026-07-12 en `docs/superpowers/specs/2026-07-11-fix-ui-export-design.md`. Va al PR #11 existente (branch `feature/fix-ui-export`).

**Tech Stack:** Kotlin + Compose (material3). Gradle desde `app/` con JDK 17+ (probado JDK 21).

## Global Constraints

- Branch `feature/fix-ui-export` (ya existe, PR #11 abierto) — commits nuevos encima.
- UI copy en inglés; código/comentarios en español (convención del repo).
- `300.dp` de minSize — mismo valor que `BibliotecaScreen.kt:119` (consistencia).
- Fila interna del item NO cambia (Checkbox + título `bodyMedium` + detalle `bodySmall`/`onSurfaceVariant`).
- Header (counts/botones) y bloque Listo/Share NO cambian.
- Suite completa verde antes de pushear; smoke en tablet es gate (device `a989173e`, lo corre el controller).
- Fuera de alcance: cambios de dominio; two-pane; unit tests de screen (convención repo).

---

### Task 1: ExportScreen — LazyColumn → LazyVerticalGrid adaptivo

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportScreen.kt` (imports líneas 16-17 y bloque de lista líneas 105-128)

**Interfaces:**
- Consumes: `HistoriaResumen(id, titulo, autor, dificultad)` y helpers `detalleHistoria`/`dificultadDisplay` ya presentes en el archivo. Nada nuevo producido.

- [ ] **Step 1: Reemplazar imports**

Quitar:

```kotlin
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
```

Agregar (en orden alfabético dentro del bloque de imports):

```kotlin
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
```

- [ ] **Step 2: Reemplazar el contenedor de la lista**

El bloque actual (líneas 105-128):

```kotlin
            if (historiasStories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // weight(1f, fill = false): la lista scrollea en el espacio del medio
                // sin empujar el bloque Exported/Share fuera de pantalla, y con pocas
                // historias no se estira (el bloque queda pegado a la lista).
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(historiasStories, key = { it.id }) { historia ->
```

pasa a:

```kotlin
            if (historiasStories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // weight(1f, fill = false): la lista scrollea en el espacio del medio
                // sin empujar el bloque Exported/Share fuera de pantalla, y con pocas
                // historias no se estira (el bloque queda pegado a la lista).
                // Adaptive(300.dp): mismo minSize que la grilla de biblioteca — en
                // tablet rinde ~2 columnas portrait / ~3 landscape; en teléfono 1.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 300.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    items(historiasStories, key = { it.id }) { historia ->
```

El cuerpo del item (Row/Checkbox/Column/Texts, líneas 112-125) queda EXACTAMENTE igual. Cierra con las mismas llaves (el bloque completo queda balanceado — verificar con el compile del Step 3).

- [ ] **Step 3: Compilar + suite completa**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/app
./gradlew :app:compileDebugKotlin && ./gradlew test -q --console=plain
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=0
for x in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    r=ET.parse(x).getroot(); t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors'))
print(f'suite: {t} tests, {f} failures, {e} errors')"
```

Esperado: BUILD SUCCESSFUL + 187 tests, 0 failures, 0 errors (reportar el número real).

- [ ] **Step 4: Commit**

```bash
git add -A app/app/src
git commit -m "feat(app): lista de historias del export en grid adaptivo (tablet)"
```

---

### Task 2: Smoke en tablet + push al PR #11 (controller-driven)

**Files:** ninguno (validación + push).

- [ ] **Step 1: Build e instalar en tablet** (device `a989173e`)

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/app
./gradlew :app:assembleDebug -q --console=plain
adb -s a989173e install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Smoke** (ambas orientaciones):
  1. Portrait: la lista muestra ~2 columnas con las 10 historias; ticks sin superponerse.
  2. Landscape: ~3 columnas; si excede el espacio, scrollea con counts/botones fijos.
  3. Exportar Stories → "Exported N stories…" + Share visibles SIN scrollear en ambas orientaciones.
  4. Cada celda sigue mostrando `autor · Difficulty`.

- [ ] **Step 3: Push al PR #11**

```bash
git push
```

Comentar en el PR el cambio nuevo (o editar el body) mencionando el grid adaptivo.

- [ ] **Step 4: Ledger** — anotar tasks completas y resultado del smoke en `.superpowers/sdd/progress.md`.

---

## Self-Review (hecho al escribir)

- **Cobertura del spec (addendum)**: swap de contenedor → Task 1 Step 2; minSize 300 → constraint + código; testing → Step 3 + Task 2 smoke; fuera de alcance → constraints. ✔
- **Placeholders**: ninguno — código completo copiado del archivo real (líneas verificadas contra HEAD 23486c9). ✔
- **Consistencia**: imports de Step 1 son exactamente los que usa el código de Step 2; fila interna referenciada sin cambios. ✔
