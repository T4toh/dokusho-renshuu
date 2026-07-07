# Anexo de diseño — Plan 3: app/ (lector Android)

**Fecha**: 2026-07-07 · **Estado**: aprobado en brainstorming · Plan: `docs/superpowers/plans/2026-07-07-plan-3-app.md`

Complementa `docs/superpowers/specs/2026-07-06-dokusho-renshuu-design.md` (Componente 3) con las decisiones cerradas en brainstorming del 2026-07-07:

- **Scope Plan 3**: Biblioteca + Lector + Detalle kanji + Acerca de. Import de texto propio y mazos → Plan 4 (resuelve la ambigüedad del spec original que ponía "importar texto" en Biblioteca).
- **Módulo único + DI manual** (contenedor en Application). Sin Hilt/multi-módulo: app personal, YAGNI. `dominio/` no importa Android → testeable JVM puro.
- **Diccionario sin Room**: esquema externo versionado por Plan 1; `SQLiteDatabase` readonly directo detrás de la interfaz `Diccionario` (mockeable en tests de dominio).
- **Room solo para estado propio**: progreso de lectura, palabras tocadas, prefs (toggle furigana).
- **db → assets por gradle task** `descargarDiccionario` (release db-v1, valida tamaño); historias empaquetadas por task `copiarHistorias` desde `catalogo/` del monorepo (fuente de verdad única, sin duplicación commiteada).
- **Furigana en UI**: render por token (Kuromoji) en FlowRow; cada token = columna [furigana chica / superficie grande tappeable]; las lecturas se toman de la furigana pre-alineada del JSON (intersección de spans token↔furigana), NO de Kuromoji (solo fallback en sheet). Aproximación v1 documentada: la furigana se centra sobre el token completo.
- **minSdk 26, target/compile 36**. Stack pineado arriba; re-verificar estables al ejecutar.
- **Registro de palabras tocadas** desde Plan 3 (tabla Room) — insumo directo del generador de mazos de Plan 4.
- **Segmentador Kotlin diferido a Plan 4**: portar de `historias/src/segmentador.py` incluyendo la regla de fusión de spans de solo puntuación (`_es_residuo`).
- **UI adaptativa** (uso en tablet): sin orientación fijada; Biblioteca con `LazyVerticalGrid(GridCells.Adaptive(300.dp))`; Lector con contenido centrado y `widthIn(max = 700.dp)` (largo de línea legible en landscape); bottom sheet y detalle de kanji funcionan igual en ambas orientaciones. Checklist de dispositivo incluye rotación y tablet.

