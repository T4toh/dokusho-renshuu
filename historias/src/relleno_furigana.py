"""Relleno de furigana faltante con análisis morfológico (janome, IPADIC).

Las fuentes Aozora traen ruby parcial; este módulo completa los huecos por
oración. El ruby original siempre gana: un token que toque un span existente
se salta entero. Trim de okurigana portado de GeneradorFurigana.kt (app);
janome usa IPADIC, el mismo diccionario que Kuromoji en la app, así las
lecturas generadas coinciden con las del texto importado.
"""
from janome.tokenizer import Tokenizer

from . import japones

_tokenizer = None  # lazy: cargar IPADIC una sola vez por proceso


def _obtener_tokenizer():
    global _tokenizer
    if _tokenizer is None:
        _tokenizer = Tokenizer()
    return _tokenizer


def _katakana_a_hiragana(texto: str) -> str:
    # mismo rango ァ..ヶ que Tokenizador.katakanaAHiragana (app)
    return ''.join(chr(ord(c) - 0x60) if 'ァ' <= c <= 'ヶ' else c
                   for c in texto)


def _terna_del_token(superficie: str, lectura: str, inicio: int) -> list:
    """[inicio, fin, lectura] con la okurigana recortada (fin exclusivo).

    Recorta prefijo/sufijo donde superficie (en hiragana) y lectura
    coinciden, sin cruzar un kanji. Si el recorte degenera, la terna cubre
    el token completo (degradación segura, mismo contrato que la app).
    """
    superficie_hira = _katakana_a_hiragana(superficie)
    pre = 0
    while (pre < len(superficie_hira) and pre < len(lectura)
           and not japones.es_kanji(superficie[pre])
           and superficie_hira[pre] == lectura[pre]):
        pre += 1
    post = 0
    while (post < len(superficie_hira) - pre
           and post < len(lectura) - pre
           and not japones.es_kanji(superficie[-1 - post])
           and superficie_hira[-1 - post] == lectura[-1 - post]):
        post += 1
    nucleo = superficie[pre:len(superficie) - post]
    lectura_nucleo = lectura[pre:len(lectura) - post]
    if any(japones.es_kanji(c) for c in nucleo) and lectura_nucleo:
        return [inicio + pre, inicio + len(superficie) - post,
                lectura_nucleo]
    return [inicio, inicio + len(superficie), lectura]


def completar(texto: str, furigana: list) -> list:
    """Ternas Aozora + generadas para los huecos, ordenadas y disjuntas."""
    cubiertos = set()
    for inicio, fin, _ in furigana:
        cubiertos.update(range(inicio, fin))
    completas = list(furigana)
    pos = 0
    for token in _obtener_tokenizer().tokenize(texto):
        superficie = token.surface
        inicio = texto.index(superficie, pos)  # janome saltea espacios
        pos = inicio + len(superficie)
        if not any(japones.es_kanji(c) for c in superficie):
            continue
        if not token.reading or token.reading == '*':
            continue  # lectura desconocida: el hueco queda como está
        if any(i in cubiertos for i in range(inicio, pos)):
            continue  # ruby Aozora gana; nunca se rellena medio token
        lectura = _katakana_a_hiragana(token.reading)
        completas.append(_terna_del_token(superficie, lectura, inicio))
    return sorted(completas, key=lambda t: t[0])
