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


def _texto_plano(nodo) -> str:
    """Concatena todo el texto de un nodo structured-content."""
    if isinstance(nodo, str):
        return nodo
    if isinstance(nodo, list):
        return ''.join(_texto_plano(n) for n in nodo)
    if isinstance(nodo, dict):
        return _texto_plano(nodo.get('content', ''))
    return ''


def _buscar_li(nodo, acumulador: list) -> None:
    """Junta el texto de cada nodo <li> (una glosa por li)."""
    if isinstance(nodo, list):
        for n in nodo:
            _buscar_li(n, acumulador)
    elif isinstance(nodo, dict):
        if nodo.get('tag') == 'li':
            texto = _texto_plano(nodo.get('content', '')).strip()
            if texto:
                acumulador.append(texto)
        else:
            _buscar_li(nodo.get('content', []), acumulador)


def extraer_glosas(glossary) -> list:
    glosas = []
    for item in glossary:
        if isinstance(item, str):
            glosas.append(item)
        elif isinstance(item, dict) and item.get('type') == 'structured-content':
            _buscar_li(item.get('content', []), glosas)
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
