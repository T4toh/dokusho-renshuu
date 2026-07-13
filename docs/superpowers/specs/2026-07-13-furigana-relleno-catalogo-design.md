# Relleno de furigana faltante en el catálogo — diseño

Fecha: 2026-07-13
Estado: aprobado (brainstorming con Tatoh)

## Problema

Las fuentes de Aozora Bunko traen ruby parcial: solo algunos kanji vienen
anotados. La cobertura de furigana por historia en el catálogo actual varía
entre 11.5% (tebukuro_wo_kaini) y 92.9% (issunboshi); momotaro (88.4%) deja
sin lectura kanji comunes como 山 y 川 (percibido en uso como "falta siempre
antes de へ": coincidencia de las construcciones 山へ/川へ del cuento).

El lector muestra únicamente las ternas del catálogo, así que esos kanji
quedan sin lectura. Feedback de uso real (beta.1, leyendo momotaro).

No es bug del alineador (`historias/src/aozora.py`): el ruby que existe en la
fuente se alinea bien. Caso `水《みいず》`: es intencional en el original (la
canción alarga vocales: かあらいぞ/ああまいぞ) y se conserva tal cual.

## Decisiones tomadas

- **Dónde**: pipeline Python (no en la app). El catálogo se sirve raw desde
  main → el fix llega a usuarios sin release ni build Android.
- **Política**: rellenar TODOS los huecos. Riesgo aceptado: el analizador
  morfológico puede errar lecturas raras (mismo límite conocido que el
  import 4b con Kuromoji).
- **Schema**: furigana generada indistinguible de la original
  (`[inicio, fin, lectura]`, schema v2 intacto, app sin cambios).
- **Tokenizador**: `janome` — puro Python, diccionario IPADIC embebido, el
  mismo diccionario que Kuromoji en la app → lecturas consistentes entre
  catálogo y texto importado. Velocidad irrelevante (one-shot sobre 10
  cuentos).

## Arquitectura

Módulo nuevo `historias/src/relleno_furigana.py`:

- `completar(texto: str, furigana: list) -> list` — por oración. Recibe el
  texto y las ternas Aozora existentes; devuelve la lista completa (Aozora +
  generadas), ordenada por inicio, spans disjuntos.
- Tokenizer janome a nivel de módulo (lazy) para no re-crearlo por oración.
- Offsets de token: janome no los expone; se acumulan longitudes de
  superficie (los tokens cubren el input de forma contigua).

Por token:

1. Sin kanji en la superficie, o lectura desconocida (`*`/vacía) → skip.
2. Lectura katakana → hiragana (mismo shift de rango ァ..ヶ que
   `Tokenizador.katakanaAHiragana` en la app).
3. Trim de okurigana portado de `GeneradorFurigana.ternaDelToken`
   (`app/.../dominio/GeneradorFurigana.kt`): recortar prefijo/sufijo donde
   superficie-en-hiragana y lectura coinciden, sin cruzar kanji; si el
   recorte degenera, la terna cubre el token completo (degradación segura).
4. **Precedencia Aozora**: si el span resultante toca cualquier índice ya
   cubierto por una terna existente → skip del token entero. Nunca se
   rellena medio token ni se pisa ruby original.

Hook en `historias/pipeline.py::procesar_obra`: tras
`segmentador.segmentar_parrafo`, mapear cada `(texto, furigana)` de oración
por `relleno_furigana.completar`. `aozora.py`, `segmentador.py` y
`emisor.py` no se tocan.

## Manejo de errores

- Token sin lectura → hueco se queda como está (comportamiento actual).
- Todo lo demás mantiene el contrato "falla ruidoso" del pipeline.
- `verify_catalogo.py` sigue validando spans disjuntos post-relleno.

## Testing

Pytest en `historias/tests/` usando janome real (determinista, puro Python):

- Rellena hueco simple (山へ → やま sobre 山).
- Respeta ruby Aozora existente (no lo pisa, no lo duplica).
- Trim de okurigana (行きました → い sobre 行).
- Runs de katakana / oraciones sin kanji → sin ternas nuevas.
- Token parcialmente cubierto por Aozora → se salta entero.
- Salida ordenada y disjunta.

End-to-end: correr `pipeline.py`, `verify_catalogo.py`, medir cobertura
(esperado >95% en todas las historias), spot-check manual de momotaro y
tebukuro_wo_kaini.

## Efectos y no-objetivos

- Catálogo regenerado: solo cambian arrays `furigana` (textos idénticos →
  el progreso guardado no se corre; version sigue 2).
- El invariante byte-idéntico de tanda 1 (Plan 4c) deja de valer — esperado.
- Dependencia nueva `janome` documentada en `historias/README.md` (chequear
  supply-chain y que la versión tenga >7 días publicada al instalar).
- No-objetivo: marcar furigana generada en el schema, tocar la app,
  "corregir" みいず de la canción.
