# Fix UI Export — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** La lista de historias del Export scrollea (hoy desborda con 10 y superpone los ticks) y cada fila muestra `autor · dificultad`; counts/botones fijos arriba y el bloque "Exported… + Share" siempre visible abajo.

**Architecture:** Dos campos nuevos en `HistoriaResumen` (dominio, ya parsea la Historia completa — cero I/O extra) + relayout de `ExportScreen` (LazyColumn con `weight(1f, fill = false)` entre header fijo y bloque de resultado fijo). Spec: `docs/superpowers/specs/2026-07-11-fix-ui-export-design.md`.

**Tech Stack:** Kotlin + Compose (material3), JUnit4. Gradle desde `app/` con JDK 17+ (probado JDK 21).

## Global Constraints

- Branch `feature/fix-ui-export` desde main.
- UI copy en inglés; código/comentarios en español (convención del repo).
- Dificultad en datos SIEMPRE en schema (`facil|media|dificil`); a UI solo en display (`Easy|Medium|Hard`).
- El detalle de fila se arma SOLO con partes no vacías — autor vacío (historias importadas) ⇒ solo la dificultad, nunca `" · Medium"` colgante.
- Convención del repo: helpers privados chicos se duplican entre módulos/screens a propósito (no acoplar `ExportScreen` a `BibliotecaScreen`).
- Suite completa verde antes del PR; smoke en tablet es gate (device `a989173e`, lo corre el controller).
- Fuera de alcance: toggle de checkboxes durante `Generando`; separador colgante de la tarjeta de biblioteca; cualquier cambio de dominio más allá de los 2 campos.

---

### Task 1: HistoriaResumen con autor y dificultad

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazos.kt:36` (data class) y `:53` (`resumenHistorias()`)
- Test: `app/app/src/test/kotlin/com/tatoh/dokushorenshu/dominio/anki/ArmadorMazosTest.kt` (ampliar el test existente de `resumenHistorias`, línea ~275)

**Interfaces:**
- Produces: `data class HistoriaResumen(val id: String, val titulo: String, val autor: String, val dificultad: String)` — `dificultad` en schema (`facil|media|dificil`). Task 2 lo consume en la UI.
- `HistoriaResumen` solo se construye en `ArmadorMazos.kt:53` (verificado por grep) — ningún test lo instancia directo; no hay más call sites que tocar.

- [ ] **Step 1: Ampliar el test existente para que falle**

En `ArmadorMazosTest.kt`, el test `resumenHistorias devuelve id y titulo de las locales` (~línea 275) usa un armador con historias reales del fixture (helper `armadorDos()`). Renombrarlo y ampliar los asserts (leer el test actual y mantener su estructura; los asserts nuevos son estos):

```kotlin
    @Test
    fun `resumenHistorias devuelve id titulo autor y dificultad de las locales`() {
        val resumen = armadorDos().resumenHistorias()
        assertTrue(resumen.isNotEmpty())
        assertTrue(resumen.all { it.id.isNotBlank() && it.titulo.isNotBlank() })
        // autor y dificultad vienen de la Historia parseada (fixture momotaro: 楠山正雄 / facil)
        val momotaro = resumen.first { it.id == "momotaro" }
        assertEquals("楠山正雄", momotaro.autor)
        assertEquals("facil", momotaro.dificultad)
    }
```

(Si el fixture del helper usa otro id/autor, ajustar los valores esperados a los del fixture REAL — leerlo antes; el patrón del assert no cambia.)

- [ ] **Step 2: Correr y ver que falla**

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/app
./gradlew :app:testDebugUnitTest --tests '*ArmadorMazosTest*'
```
Esperado: FAIL de compilación (`autor` no existe en `HistoriaResumen`).

- [ ] **Step 3: Implementación**

En `ArmadorMazos.kt`:

```kotlin
data class HistoriaResumen(val id: String, val titulo: String, val autor: String, val dificultad: String)
```

```kotlin
    fun resumenHistorias(): List<HistoriaResumen> =
        historiasRepo.historiasLocales().map {
            HistoriaResumen(it.id, it.titulo, it.autor, it.dificultad)
        }
```

(Conservar el KDoc existente de `resumenHistorias` tal cual está.)

- [ ] **Step 4: Correr y ver que pasa**

```bash
./gradlew :app:testDebugUnitTest --tests '*ArmadorMazosTest*' --tests '*ExportViewModelTest*'
```
Esperado: PASS (ExportViewModelTest solo consume `HistoriaResumen`, no lo construye — debe compilar sin cambios; si algún assert compara instancias completas, ajustar solo ese assert).

- [ ] **Step 5: Commit**

```bash
git add -A app/app/src
git commit -m "feat(app): HistoriaResumen con autor y dificultad para la lista de export"
```

---

### Task 2: ExportScreen — lista scrolleable + fila con detalle

**Files:**
- Modify: `app/app/src/main/kotlin/com/tatoh/dokushorenshu/ui/export/ExportScreen.kt` (bloque de la lista, líneas ~103-116, + imports + helper al final del archivo)

**Interfaces:**
- Consumes: `HistoriaResumen(id, titulo, autor, dificultad)` de Task 1.

- [ ] **Step 1: Reemplazar el bloque de la lista**

El bloque actual (`if (historiasStories.isNotEmpty()) { … Column { for … } }`, líneas ~103-116) pasa a:

```kotlin
            if (historiasStories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // weight(1f, fill = false): la lista scrollea en el espacio del medio
                // sin empujar el bloque Exported/Share fuera de pantalla, y con pocas
                // historias no se estira (el bloque queda pegado a la lista).
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(historiasStories, key = { it.id }) { historia ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = historia.id in seleccionadas,
                                onCheckedChange = { vm.toggleHistoria(historia.id) },
                            )
                            Column {
                                Text(historia.titulo, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    detalleHistoria(historia.autor, historia.dificultad),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
```

Imports nuevos: `androidx.compose.foundation.lazy.LazyColumn`, `androidx.compose.foundation.lazy.items`.

- [ ] **Step 2: Helper de detalle al final del archivo** (junto a `compartirMazo`)

```kotlin
// Mapea la dificultad cruda a display en inglés (duplicado a propósito del helper
// privado de BibliotecaScreen — convención del repo: no acoplar screens entre sí).
private fun dificultadDisplay(dificultad: String): String = when (dificultad) {
    "facil" -> "Easy"
    "media" -> "Medium"
    "dificil" -> "Hard"
    else -> dificultad.replaceFirstChar { it.uppercase() }
}

/** Línea secundaria de la fila: solo partes no vacías — una importada sin autor
 *  muestra "Medium", nunca " · Medium" con separador colgante. */
private fun detalleHistoria(autor: String, dificultad: String): String =
    listOf(autor, dificultadDisplay(dificultad))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
```

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
Esperado: BUILD SUCCESSFUL + 187/187 (0 failures — el count exacto puede ser 187 o el vigente en main; reportar el número real).

- [ ] **Step 4: Commit**

```bash
git add -A app/app/src
git commit -m "fix(app): lista de historias del export scrolleable y con autor · dificultad"
```

---

### Task 3: Smoke en tablet + PR (controller-driven)

**Files:** ninguno (validación + PR).

- [ ] **Step 1: Build e instalar en tablet** (device `a989173e`)

```bash
cd /Users/tatoh/Repos/dokusho-renshuu/app
./gradlew :app:assembleDebug -q --console=plain
adb -s a989173e install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Smoke** (los 3 puntos del spec):
  1. Export con 10 historias: la lista scrollea, ticks sin superponerse, counts y botones no se mueven durante el scroll.
  2. Cada fila muestra `autor · Difficulty` (ej. `楠山正雄 · Easy`); si hay una importada sin autor, muestra solo `Medium` sin separador.
  3. Exportar Stories → "Exported N stories…" + botón Share visibles SIN scrollear, con la lista larga presente.

- [ ] **Step 3: Push + PR**

```bash
git push -u origin feature/fix-ui-export
gh pr create --title "Fix UI export: lista scrolleable + detalles por obra" --body "..."
```
Body: bug del overflow (ticks superpuestos con 10 historias) + layout nuevo (lista weight/LazyColumn, Share siempre visible) + detalle `autor · dificultad` sin separador colgante + suite + smoke. Cerrar con la línea de atribución de Claude Code.

- [ ] **Step 4: ESTADO.md** — en "Backlog UI Export": marcar resueltos los 2 ítems (o borrar la sección si queda vacía); actualizar con el # de PR al abrir. Commit `docs(ESTADO): fix UI export (PR #N)` y push a la branch antes del merge.

---

## Self-Review (hecho al escribir)

- **Cobertura del spec**: cambios §1 → Task 1; §2 (layout + fila + helper) → Task 2; testing → Tasks 1-2 Steps + Task 3 smoke; fuera de alcance → constraints. ✔
- **Placeholders**: valores del assert de Task 1 condicionados al fixture real con instrucción explícita de leerlo (el fixture usa momotaro real — 楠山正雄/facil verificados contra catalogo actual). ✔
- **Consistencia**: `HistoriaResumen(id, titulo, autor, dificultad)` idéntico en Task 1 Produces y Task 2 Consumes; `detalleHistoria`/`dificultadDisplay` definidos donde se usan. ✔
