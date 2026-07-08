"""Parser de term banks de Jitendex (formato Yomitan)."""
import glob
import json
import os
from dataclasses import dataclass, field


@dataclass
class Palabra:
    termino: str
    lectura: str
    significados: list = field(default_factory=list)
    tags: list = field(default_factory=list)
    popularidad: int = 0


# Marcas 'data.content' que Jitendex usa para envolver contenido que NO es
# texto de glosa: info gramatical por-sentido (más específica que los tags
# planos def_tags/term_tags) y oraciones de ejemplo embebidas (los
# ejemplos de la app ya vienen de Tatoeba por otra vía, vía
# oracion_palabra/oracion_kanji).
# Marcas verificadas contra term_bank_*.json reales (Jitendex yomitan release
# descargada desde github.com/stephenmk/stephenmk.github.io): la info
# gramatical usa 'part-of-speech-info' (varios <span> hermanos, uno por tag);
# las oraciones de ejemplo cuelgan de un 'extra-info' pero la marca puntual
# 'example-sentence' ya alcanza para descartarlas (incluye a su vez
# example-sentence-a/b y attribution-footnote).
_MARCAS_DESCARTABLES = {'part-of-speech-info', 'example-sentence'}


def _es_descartable(nodo: dict) -> bool:
    return nodo.get('data', {}).get('content') in _MARCAS_DESCARTABLES


def _texto_glosa(nodo) -> str:
    """Concatena el texto propio de un <li> para usarlo como glosa:
    descarta furigana (<rt>), bloques marcados como descartables (PoS
    embebido, oraciones de ejemplo) y no baja a listas anidadas
    (<ol>/<ul>) — esas se procesan aparte, cada <li> hijo es su propia
    glosa (ver _buscar_li)."""
    if isinstance(nodo, str):
        return nodo
    if isinstance(nodo, list):
        return ''.join(_texto_glosa(n) for n in nodo)
    if isinstance(nodo, dict):
        tag = nodo.get('tag')
        if tag == 'rt' or tag in ('ol', 'ul'):
            return ''
        if _es_descartable(nodo):
            return ''
        return _texto_glosa(nodo.get('content', ''))
    return ''


def _buscar_li(nodo, acumulador: list) -> None:
    """Junta el texto de cada nodo <li> (una glosa por li). Un <li> puede
    traer, además de su propio texto, una lista anidada de <li> hijos
    (p.ej. un sentido con sub-glosas): cada hijo se agrega como glosa
    propia, en vez de aplanarse en el texto del padre."""
    if isinstance(nodo, list):
        for n in nodo:
            _buscar_li(n, acumulador)
    elif isinstance(nodo, dict):
        if nodo.get('tag') == 'li':
            contenido = nodo.get('content', '')
            texto = _texto_glosa(contenido).strip()
            if texto:
                acumulador.append(texto)
            _buscar_li(contenido, acumulador)
        else:
            _buscar_li(nodo.get('content', []), acumulador)


def _texto_sin_furigana(nodo) -> str:
    """Concatena texto ignorando lecturas <rt> (furigana dentro de <ruby>)."""
    if isinstance(nodo, str):
        return nodo
    if isinstance(nodo, list):
        return ''.join(_texto_sin_furigana(n) for n in nodo)
    if isinstance(nodo, dict):
        if nodo.get('tag') == 'rt':
            return ''
        return _texto_sin_furigana(nodo.get('content', ''))
    return ''


def _buscar_nodo_a(nodo):
    """Busca el primer nodo <a> (link del redirect) en un árbol structured-content."""
    if isinstance(nodo, list):
        for n in nodo:
            encontrado = _buscar_nodo_a(n)
            if encontrado is not None:
                return encontrado
        return None
    if isinstance(nodo, dict):
        if nodo.get('tag') == 'a':
            return nodo
        return _buscar_nodo_a(nodo.get('content', []))
    return None


def _es_redirect(contenido) -> bool:
    """True si la raíz de un structured-content es una entrada redirect
    (forma vieja de kanji que Jitendex reemplaza por una nueva)."""
    return (isinstance(contenido, dict)
            and contenido.get('data', {}).get('content') == 'redirect-glossary')


def _agregar_glosa_target(glosas: list, target: str) -> None:
    target = (target or '').strip()
    if target:
        glosa = '→ ' + target
        if glosa not in glosas:
            glosas.append(glosa)


def extraer_glosas(glossary) -> list:
    glosas = []
    redirects = []
    for item in glossary:
        if isinstance(item, str):
            glosas.append(item)
        elif isinstance(item, list):
            # entradas redirect: ["forma nueva", ["redirected from forma vieja"]]
            if item and isinstance(item[0], str):
                _agregar_glosa_target(glosas, item[0])
        elif isinstance(item, dict) and item.get('type') == 'structured-content':
            contenido = item.get('content', [])
            _buscar_li(contenido, glosas)
            if _es_redirect(contenido):
                redirects.append(contenido)
    # fallback: entradas redirect que solo traen el structured-content (sin
    # el item de lista plana acompañante) — el target sale del nodo <a>
    if not glosas:
        for contenido in redirects:
            nodo_a = _buscar_nodo_a(contenido)
            if nodo_a is not None:
                _agregar_glosa_target(
                    glosas, _texto_sin_furigana(nodo_a.get('content', '')))
    return glosas


def parsear_entrada(entrada) -> Palabra:
    termino, lectura, def_tags, _, popularidad, glossary, _, term_tags = entrada
    tags = sorted(set((def_tags or '').split() + (term_tags or '').split()))
    return Palabra(
        termino=termino,
        lectura=lectura,
        significados=extraer_glosas(glossary),
        tags=tags,
        popularidad=int(popularidad or 0),
    )


def parsear_directorio(directorio: str) -> list:
    palabras = []
    for ruta in sorted(glob.glob(os.path.join(directorio, 'term_bank_*.json'))):
        with open(ruta, encoding='utf-8') as f:
            for entrada in json.load(f):
                palabras.append(parsear_entrada(entrada))
    return palabras
