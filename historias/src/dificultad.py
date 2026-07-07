"""Estimación de dificultad de una historia: facil / media / dificil.

Métrica del spec: % de ocurrencias de kanji fuera de JLPT N5-N4
+ largo promedio de oración.
"""
from . import japones
from .jlpt import KANJI_N5_N4

# umbrales iniciales; ajustar con los números reales del catálogo (Task 10)
MAX_PCT_FACIL = 0.25
MAX_LARGO_FACIL = 35
MIN_PCT_DIFICIL = 0.45
MIN_LARGO_DIFICIL = 55


def metricas(oraciones: list, kanji_conocidos=None) -> dict:
    """oraciones = textos de oración. kanji_conocidos inyectable en tests."""
    if kanji_conocidos is None:
        kanji_conocidos = KANJI_N5_N4
    total = fuera = 0
    for texto in oraciones:
        for c in texto:
            if japones.es_kanji(c):
                total += 1
                if c not in kanji_conocidos:
                    fuera += 1
    return {
        'pct_fuera': fuera / total if total else 0.0,
        'largo_promedio': (sum(len(t) for t in oraciones) / len(oraciones)
                           if oraciones else 0.0),
    }


def calcular(oraciones: list, kanji_conocidos=None) -> str:
    m = metricas(oraciones, kanji_conocidos)
    if (m['pct_fuera'] >= MIN_PCT_DIFICIL
            or m['largo_promedio'] >= MIN_LARGO_DIFICIL):
        return 'dificil'
    if (m['pct_fuera'] <= MAX_PCT_FACIL
            and m['largo_promedio'] <= MAX_LARGO_FACIL):
        return 'facil'
    return 'media'
