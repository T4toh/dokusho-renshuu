"""Segmentación de párrafos en oraciones, preservando furigana alineada.

Este algoritmo se porta a Kotlin en Plan 3 — mantener simple.
"""

FIN_ORACION = '。！？'
APERTURA = '「『（'
CIERRE = '」』）'
_PUNTUACION = FIN_ORACION + APERTURA + CIERRE

_NUMERALES_SECCION = '一二三四五六七八九十'


def es_encabezado_seccion(texto: str) -> bool:
    """True si `texto` es (solo) un numeral japonés de encabezado.

    Aozora marca el inicio de cada sección de estos cuentos con una línea
    que es únicamente un numeral (一, 二, 三...), a veces separado por
    espacios. No es una oración del cuento: se descarta al segmentar.
    """
    despojado = texto.strip().replace('　', '').replace(' ', '')
    return bool(despojado) and all(c in _NUMERALES_SECCION for c in despojado)


def _es_residuo(fragmento: str) -> bool:
    """Span sin contenido real: solo puntuación y/o espacios."""
    return all(c in _PUNTUACION or c.isspace() for c in fragmento)


def segmentar(texto: str) -> list:
    """Spans [inicio, fin) de cada oración.

    Corta en 。！？ solo fuera de comillas/paréntesis: el diálogo
    「...。...。」 queda como una sola oración.
    """
    spans = []
    inicio = 0
    profundidad = 0
    for i, c in enumerate(texto):
        if c in APERTURA:
            profundidad += 1
        elif c in CIERRE:
            profundidad = max(0, profundidad - 1)
        elif c in FIN_ORACION and profundidad == 0:
            spans.append((inicio, i + 1))
            inicio = i + 1
    if texto[inicio:].strip():
        spans.append((inicio, len(texto)))
    # fusionar spans de solo puntuación (」 residual de diálogo multi-párrafo,
    # ？ suelto tras ！) con su vecino — regla portada igual a Kotlin en Plan 3
    fusionados = []
    for span in spans:
        if fusionados and (_es_residuo(texto[span[0]:span[1]])
                           or _es_residuo(texto[fusionados[-1][0]:fusionados[-1][1]])):
            fusionados[-1] = (fusionados[-1][0], span[1])
        else:
            fusionados.append(span)
    return fusionados


def segmentar_parrafo(texto: str, furigana: list) -> list:
    """[(texto_oracion, furigana_oracion)] con índices reajustados.

    Descarta oraciones que son encabezados de sección (ver
    `es_encabezado_seccion`): no forman parte del cuento.
    """
    oraciones = []
    for inicio, fin in segmentar(texto):
        texto_oracion = texto[inicio:fin]
        if es_encabezado_seccion(texto_oracion):
            continue
        f_oracion = [
            [f_ini - inicio, f_fin - inicio, lectura]
            for f_ini, f_fin, lectura in furigana
            if f_ini >= inicio and f_fin <= fin
        ]
        oraciones.append((texto_oracion, f_oracion))
    return oraciones
