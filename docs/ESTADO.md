# Estado del proyecto

> Contexto portable entre máquinas/sesiones. Actualizar al cerrar cada plan.
> Spec: `docs/superpowers/specs/2026-07-06-dokusho-renshuu-design.md`

## Dónde estamos (2026-07-07)

| Plan | Subsistema                                       | Estado                                                                                                               |
| ---- | ------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------- |
| 1    | `diccionario/` — parser → diccionario.db         | ✅ Completo (PR #1 mergeado, release [db-v1](https://github.com/T4toh/dokusho-renshuu/releases/tag/db-v1) publicado) |
| 2    | `historias/` — pipeline Aozora → JSON + catálogo | ✅ Completo (PR pendiente de merge)                                                                                  |
| 3    | `app/` — lector Android (Kotlin + Compose)       | ⏳ Siguiente. Plan a escribir (writing-plans)                                                                        |
| 4    | `app/` — mazos .apkg + import de texto           | Pendiente de Plan 3                                                                                                  |

## Datos operativos

- **Release db**: `db-v1` = `diccionario-v1.db` (79 MB). La app lo baja de GitHub Releases a `app/src/main/assets/`.
- **Tamaño db**: 79 MB, a ~1 MB del umbral de 80 del spec. Palanca acordada si se pasa: bajar `MAX_ORACIONES_POR_KANJI` (verify_db lo chequea automático).
- **Fuentes** (URLs vigentes en `diccionario/README.md`): Jitendex ya NO distribuye por GitHub release assets; Tatoeba discontinuó el export directo de pares → `diccionario/fuentes_tatoeba.py` los arma desde exports por-idioma.
- **Contrato para la app (Plan 3)**: `oracion_palabra` solo indexa términos de 2-6 chars; palabras de 1 kanji → fallback a `oracion_kanji`. Listas en el db = JSON arrays (`ensure_ascii=False`). Versión de esquema en tabla `metadata`.
- **Entorno**: builds JVM (gradle/Android Studio, Planes 3-4) van en la PC secundaria — la principal tiene un bug de CPU que cuelga con Java. Python (Plan 2) anda en cualquiera.
- **Catálogo**: `catalogo/catalogo.json` commiteado (4 cuentos de 楠山正雄: momotaro, urashima_taro, issunboshi, kachikachi_yama), URL raw `https://raw.githubusercontent.com/T4toh/dokusho-renshuu/main/catalogo/catalogo.json`, formato `{"version": 1, "historias": [...]}`.
- **Contrato furigana**: `[inicio, fin, lectura]` con fin exclusivo sobre el texto de la oración; diálogo `「…」` = 1 oración (portar igual en Kotlin, Plan 3).
- `historias/src/jlpt.py` es generado (regenerar con `genera_jlpt.py` solo si cambia KANJIDIC2).

## Backlog diferido (review final Plan 1 — no bloqueante)

- `jitendex.py`: heurística "item lista plana = redirect" — si un release futuro de Jitendex usa listas para otra cosa, glosas con `→` espurio. El check de vacíos de verify_db no lo detectaría.
- `verify_db.py` CLI: correr sobre ruta inexistente crea un db vacío antes de fallar (falta guard `os.path.exists`).
- `tatoeba.py` / `verify_db.py`: `int()` sin guarda — línea con id no numérico aborta el build con ValueError sin contexto.
- `fuentes_tatoeba.py`: carga `eng_sentences.tsv` entero en memoria (OK como one-shot; si molesta, leer links primero y cargar solo ids necesarios).
- `jitendex.py`: sin test multi-archivo `term_bank_*`; li-anidado-en-li se aplana en una glosa.
- Recomendación pendiente: guardar procedencia de fuentes (fechas) en tabla `metadata` del db.

## Proceso de trabajo usado

Brainstorming → spec → plan por subsistema (`docs/superpowers/plans/`) → ejecución subagent-driven (implementer + reviewer por tarea, review final de branch) → PR. Repetir por plan.
