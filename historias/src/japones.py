"""Helpers de caracteres japoneses (duplicado autocontenido de diccionario/)."""

# caracteres que pueden integrar la base de un ruby además de kanji
_EXTRAS_BASE = '々〆ヵヶ'


def es_kanji(caracter: str) -> bool:
    """CJK Unified Ideographs (U+4E00–U+9FFF)."""
    return '一' <= caracter <= '鿿'


def es_base_ruby(caracter: str) -> bool:
    """Caracteres que forman la base implícita de un ruby 《》."""
    return es_kanji(caracter) or caracter in _EXTRAS_BASE


def extraer_kanjis(texto: str) -> list:
    """Kanjis únicos en orden de aparición."""
    vistos = set()
    resultado = []
    for c in texto:
        if es_kanji(c) and c not in vistos:
            vistos.add(c)
            resultado.append(c)
    return resultado
