# Export Botones en Fila — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Los 3 botones de export van en fila cuando hay ancho (tablet/landscape) y bajan de línea solos en angosto, vía FlowRow sin ramas por tamaño de pantalla.

**Architecture:** Swap del contenedor de botones en `ExportScreen`: `Column(width(IntrinsicSize.Max))` + Spacers → `FlowRow(spacedBy(12.dp))`, cada `BotonExport` con `Modifier.width(IntrinsicSize.Max)` propio. Spec: addendum (2) 2026-07-12 en `docs/superpowers/specs/2026-07-11-fix-ui-export-design.md`. Va al PR #11 (branch `feature/fix-ui-export`).

**Tech Stack:** Kotlin + Compose (material3). Gradle desde `app/` con JDK 17+ (probado JDK 21).

## Global Constraints

- Branch `feature/fix-ui-export` (PR #11 abierto) — commits encima.
- UI copy en inglés; código/comentarios en español.
- Contenido de `BotonExport` (Button + spinner + hint) NO cambia — solo gana parámetro `modifier` con default.
- Counts, grid de historias y bloque Listo/Share NO cambian.
- Suite completa verde antes de pushear; smoke en tablet + POCO es gate (lo corre el controller; POCO sin taps adb — navega el usuario).
- Fuera de alcance: cambios de dominio; unit tests de screen (convención repo).

---

### Task 1: ExportScreen — botones en FlowRow

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportScreen.kt` (bloque de botones líneas 74-104, firma de `BotonExport` línea 150-158, imports)

**Interfaces:**
- Consumes: `BotonExport` existente en el mismo archivo. Nada nuevo producido.

- [ ] **Step 1: Imports**

Agregar (orden alfabético en el bloque de imports):

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
```

`IntrinsicSize` ya está importado. Si el compile del Step 4 pide opt-in para FlowRow, agregar `androidx.compose.foundation.layout.ExperimentalLayoutApi` al `@OptIn` existente de la función `ExportScreen` (queda `@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)`) — solo si lo pide.

- [ ] **Step 2: Reemplazar el contenedor de botones**

El bloque actual (líneas 74-104):

```kotlin
            // width(IntrinsicSize.Max): los tres botones toman el ancho del más
            // largo en vez de ajustarse cada uno a su texto
            val tipoGenerando = (estado as? EstadoExport.Generando)?.tipo
            Column(Modifier.width(IntrinsicSize.Max)) {
                BotonExport(
                    titulo = "Export Words deck",
                    habilitado = contadores.words > 0,
                    hint = "Read and tap words first",
                    generandoEste = tipoGenerando == TipoExport.WORDS,
                    generandoOtro = tipoGenerando != null && tipoGenerando != TipoExport.WORDS,
                    onClick = { vm.exportar(TipoExport.WORDS) },
                )
                Spacer(Modifier.height(12.dp))
                BotonExport(
                    titulo = "Export Kanji deck",
                    habilitado = contadores.kanjisTaggeados > 0,
                    hint = "Tag kanji as easy/medium/hard first",
                    generandoEste = tipoGenerando == TipoExport.KANJI,
                    generandoOtro = tipoGenerando != null && tipoGenerando != TipoExport.KANJI,
                    onClick = { vm.exportar(TipoExport.KANJI) },
                )
                Spacer(Modifier.height(12.dp))
                BotonExport(
                    titulo = "Export Stories deck",
                    habilitado = contadores.historias > 0 && seleccionadas.isNotEmpty(),
                    hint = if (contadores.historias > 0) "Select at least one story" else "No local stories",
                    generandoEste = tipoGenerando == TipoExport.STORIES,
                    generandoOtro = tipoGenerando != null && tipoGenerando != TipoExport.STORIES,
                    onClick = { vm.exportar(TipoExport.STORIES) },
                )
            }
```

pasa a:

```kotlin
            // FlowRow: los botones van en fila cuando el ancho alcanza (tablet,
            // landscape) y bajan de línea solos en angosto — sin ramas por tamaño
            // de pantalla. Cada botón toma el ancho de su propio texto.
            val tipoGenerando = (estado as? EstadoExport.Generando)?.tipo
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BotonExport(
                    titulo = "Export Words deck",
                    habilitado = contadores.words > 0,
                    hint = "Read and tap words first",
                    generandoEste = tipoGenerando == TipoExport.WORDS,
                    generandoOtro = tipoGenerando != null && tipoGenerando != TipoExport.WORDS,
                    onClick = { vm.exportar(TipoExport.WORDS) },
                    modifier = Modifier.width(IntrinsicSize.Max),
                )
                BotonExport(
                    titulo = "Export Kanji deck",
                    habilitado = contadores.kanjisTaggeados > 0,
                    hint = "Tag kanji as easy/medium/hard first",
                    generandoEste = tipoGenerando == TipoExport.KANJI,
                    generandoOtro = tipoGenerando != null && tipoGenerando != TipoExport.KANJI,
                    onClick = { vm.exportar(TipoExport.KANJI) },
                    modifier = Modifier.width(IntrinsicSize.Max),
                )
                BotonExport(
                    titulo = "Export Stories deck",
                    habilitado = contadores.historias > 0 && seleccionadas.isNotEmpty(),
                    hint = if (contadores.historias > 0) "Select at least one story" else "No local stories",
                    generandoEste = tipoGenerando == TipoExport.STORIES,
                    generandoOtro = tipoGenerando != null && tipoGenerando != TipoExport.STORIES,
                    onClick = { vm.exportar(TipoExport.STORIES) },
                    modifier = Modifier.width(IntrinsicSize.Max),
                )
            }
```

- [ ] **Step 3: `BotonExport` gana parámetro `modifier`**

Firma actual (líneas ~150-158):

```kotlin
@Composable
private fun BotonExport(
    titulo: String,
    habilitado: Boolean,
    hint: String,
    generandoEste: Boolean,
    generandoOtro: Boolean,
    onClick: () -> Unit,
) {
    Column {
```

pasa a:

```kotlin
@Composable
private fun BotonExport(
    titulo: String,
    habilitado: Boolean,
    hint: String,
    generandoEste: Boolean,
    generandoOtro: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
```

El resto de `BotonExport` (Button/Box/spinner/hint) queda EXACTAMENTE igual.

- [ ] **Step 4: Compilar + suite completa**

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

Esperado: BUILD SUCCESSFUL + 187 tests, 0 failures, 0 errors (reportar el real). Si `FlowRow` pide opt-in, aplicar la nota del Step 1 y recompilar.

- [ ] **Step 5: Commit**

```bash
git add -A app/app/src
git commit -m "feat(app): botones de export en fila con FlowRow cuando hay ancho"
```

---

### Task 2: Smoke tablet + POCO + push al PR #11 (controller-driven)

**Files:** ninguno.

- [ ] **Step 1: Build e instalar** (tablet `a989173e`; después POCO cuando el usuario lo enchufe — un device por vez)

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/app
./gradlew :app:assembleDebug -q --console=plain
adb -s a989173e install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Smoke tablet**: portrait y landscape — 3 botones en fila (hay ancho en ambas), grid intacto, Exported+Share visibles tras exportar.
- [ ] **Step 3: Smoke POCO** (portrait, 1220x2712): botones apilados o wrap limpio, sin regresión; taps adb no funcionan — el usuario navega, capturas con `adb exec-out screencap`.
- [ ] **Step 4: Push + comentario en PR #11 + ledger.**

---

## Self-Review (hecho al escribir)

- **Cobertura del addendum (2)**: FlowRow+spacedBy → Step 2; modifier en BotonExport → Step 3; OptIn condicional → Steps 1/4; smoke dual-device → Task 2. ✔
- **Placeholders**: ninguno — bloques copiados del archivo real (HEAD ab222bc). ✔
- **Consistencia**: parámetro `modifier` definido en Step 3 y usado en Step 2; `IntrinsicSize` ya importado (verificado línea 7). ✔
