"""Helpers de caracteres japoneses."""


def es_kanji(caracter: str) -> bool:
    """CJK Unified Ideographs (U+4E00–U+9FFF)."""
    return '一' <= caracter <= '鿿'


def extraer_kanjis(texto: str) -> list:
    """Kanjis únicos en orden de aparición."""
    vistos = set()
    resultado = []
    for c in texto:
        if es_kanji(c) and c not in vistos:
            vistos.add(c)
            resultado.append(c)
    return resultado
