# Tanda 2 de historias base (diseño)

> Brainstorming 2026-07-11. Resuelve el ítem de backlog del Plan 4b: "agregar más historias base al catálogo (pipeline Plan 2)".

## Objetivo

Duplicar el catálogo: sumar 6 historias nuevas (hoy hay 4) vía el pipeline existente de `historias/`, sin código nuevo — es una tanda de contenido. Mezcla de autores y dificultades para dar progresión real de lectura.

## Decisiones (validadas con el usuario)

1. **Cantidad**: 6 titulares (+2 suplentes si alguna falla). Catálogo final: ~10.
2. **Perfil**: mezcla — más 楠山正雄 (fáciles, formato ya probado), 新美南吉 (clásicos escolares, puente de dificultad) y 1 pieza corta difícil (芥川).
3. **Selección**: propuesta por Claude, verificada contra Aozora en la implementación.
4. **Assets**: todas embebidas en el APK — `copiarHistorias` ya empaqueta `catalogo/` entero, sin cambios de código. Los JSON son chicos (~100-300 KB total).

## Obras

| # | Obra | Autor | `id` | Dificultad esperada* | Rol |
|---|------|-------|------|----------------------|-----|
| 1 | 花咲かじじい | 楠山正雄 | `hanasaka_jijii` | easy | titular |
| 2 | したきりすずめ | 楠山正雄 | `shitakiri_suzume` | easy | titular |
| 3 | 金太郎 | 楠山正雄 | `kintaro` | easy/medium | titular |
| 4 | ごん狐 | 新美南吉 | `gongitsune` | medium | titular |
| 5 | 手袋を買いに | 新美南吉 | `tebukuro_wo_kaini` | medium | titular |
| 6 | 蜘蛛の糸 | 芥川龍之介 | `kumo_no_ito` | hard (corta) | titular |
| S1 | 猿かに合戦 | 楠山正雄 | `saru_kani_gassen` | easy | suplente |
| S2 | 注文の多い料理店 | 宮沢賢治 | `chumon_no_oi_ryoriten` | medium/hard | suplente |

\* La dificultad REAL la calcula el clasificador JLPT del pipeline (`dificultad.py`); esta columna es estimación a priori y no se fuerza. Todos los autores tienen dominio público (楠山 †1950, 新美 †1943, 芥川 †1927, 宮沢 †1933).

**Los card IDs y URLs de Aozora NO están en este spec a propósito**: se verifican contra aozora.gr.jp durante la implementación (índice por autor → card → zip de la versión ruby). Regla de reemplazo: si una obra no tiene versión ruby, el zip trae un formato que el pipeline no maneja, o el texto sale con problemas en el sanity check, entra el suplente siguiente (S1, luego S2); si se agotan, la tanda cierra con las que pasaron (mínimo 4).

## Proceso

Todo con el tooling existente de `historias/` (Python stdlib, corre en cualquier máquina):

1. **Verificar cards en Aozora** y anotar URL del zip ruby de cada obra.
2. **Bajar zips a `fuentes/`** (gitignored) y renombrar según el campo `archivo` — proceso manual documentado en `historias/README.md`.
3. **6 entradas nuevas en `obras.json`**: `id` (romaji snake_case), `archivo`, `fuente` (`aozora:<card>`), `titulo_lectura` (kana), `titulo_en`, `url`.
4. **`python3 pipeline.py`** — regenera `../catalogo/` completo.
   - **Invariante**: los 4 JSON existentes deben quedar **byte-idénticos** (fuentes intactas + pipeline determinístico). Check: `git diff --stat catalogo/` solo muestra archivos nuevos + `catalogo.json`. Así el progreso guardado de los lectores no se corre.
5. **`python3 verify_catalogo.py`** (exit 0) + **`python3 -m unittest discover tests`** verdes.
6. **Sanity manual por historia nueva**: sin mojibake (cp932 puede decodificar mal sin lanzar — riesgo conocido), primera y última oración correctas contra la fuente, spot-check de 2-3 ternas de furigana, dificultad calculada razonable, sin encabezados de sección residuales.
7. **App**: cero cambios de código. Rebuild (`copiarHistorias` embebe el catálogo nuevo), suite completa verde, smoke en tablet: biblioteca con ~10 tarjetas, abrir una historia nueva (furigana + tap de palabra), export Stories con ~10 checkboxes.
8. **Docs**: tabla de obras en `historias/README.md`; `docs/ESTADO.md` (resolver ítem de backlog 4b, actualizar línea de catálogo).
9. **PR** contra main.

## Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| Mojibake cp932 silencioso | Sanity manual por historia (paso 6) |
| Obra sin versión ruby / formato Aozora no visto | Regla de suplentes |
| Umbral navaja de dificultad (caso urashima 0.449) | La dificultad es por historia; las existentes no se recalculan distinto porque el set JLPT no cambia. Se acepta la que calcule el pipeline para las nuevas |
| Título largo rompe cards de la app | Ya hay títulos de 6+ chars en producción; smoke visual en tablet |
| Drift de los 4 JSON viejos | Invariante byte-idéntico (paso 4) |

## Fuera de alcance

- Cambios al pipeline, clasificador de dificultad o app.
- Traducciones (`traduccion` sigue null).
- Rebuild del diccionario (db-v2.1 es otro ítem de backlog).
- Release/instalación en dispositivos más allá del smoke.
