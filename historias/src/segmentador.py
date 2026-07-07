"""Segmentación de párrafos en oraciones, preservando furigana alineada.

Este algoritmo se porta a Kotlin en Plan 3 — mantener simple.
"""

FIN_ORACION = '。！？'
APERTURA = '「『（'
CIERRE = '」』）'


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
    return spans


def segmentar_parrafo(texto: str, furigana: list) -> list:
    """[(texto_oracion, furigana_oracion)] con índices reajustados."""
    oraciones = []
    for inicio, fin in segmentar(texto):
        f_oracion = [
            [f_ini - inicio, f_fin - inicio, lectura]
            for f_ini, f_fin, lectura in furigana
            if f_ini >= inicio and f_fin <= fin
        ]
        oraciones.append((texto[inicio:fin], f_oracion))
    return oraciones
