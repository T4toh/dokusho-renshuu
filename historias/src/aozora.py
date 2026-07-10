"""Limpieza de marcado Aozora Bunko y extracción de furigana (ruby)."""
import re

from . import japones

_ANOTACION = re.compile(r'［＃.*?］')
_DELIMITADOR = re.compile(r'^-{10,}\s*$')


def limpiar_linea(linea: str) -> tuple:
    """Una línea del cuerpo → (texto_limpio, furigana).

    furigana = [[inicio, fin, lectura], ...] con fin exclusivo,
    índices sobre texto_limpio.
    """
    linea = _ANOTACION.sub('', linea)
    linea = linea.strip().lstrip('　')  # sangría Aozora
    texto = []
    furigana = []
    inicio_marcado = None  # posición fijada por ｜
    i = 0
    while i < len(linea):
        c = linea[i]
        if c == '｜':
            inicio_marcado = len(texto)
            i += 1
        elif c == '《':
            cierre = linea.find('》', i + 1)
            if cierre == -1:
                texto.append(c)  # 《 sin cerrar: literal
                i += 1
                continue
            lectura = linea[i + 1:cierre]
            if inicio_marcado is not None:
                inicio = inicio_marcado
            else:
                inicio = len(texto)
                limite = furigana[-1][1] if furigana else 0
                while inicio > limite and japones.es_base_ruby(texto[inicio - 1]):
                    inicio -= 1
            if lectura and inicio < len(texto):
                furigana.append([inicio, len(texto), lectura])
            inicio_marcado = None
            i = cierre + 1
        else:
            texto.append(c)
            i += 1
    return ''.join(texto), furigana


def extraer_cuerpo(texto_completo: str) -> tuple:
    """Un .txt de Aozora → (titulo, autor, lineas_cuerpo).

    Título en línea 1, autor en línea 2; el cuerpo arranca tras el segundo
    delimitador de guiones (si existe) y termina antes del colofón (底本：).
    """
    lineas = texto_completo.splitlines()
    if len(lineas) < 3:
        raise ValueError('archivo demasiado corto para ser una obra de Aozora')
    titulo = lineas[0].strip()
    autor = lineas[1].strip()

    delimitadores = [i for i, l in enumerate(lineas) if _DELIMITADOR.match(l)]
    inicio = delimitadores[1] + 1 if len(delimitadores) >= 2 else 2

    # colofón: desde la línea que empieza con 底本
    fin = None
    for i in range(inicio, len(lineas)):
        if lineas[i].startswith('底本：') or lineas[i].startswith('底本:'):
            fin = i
            break
    if len(delimitadores) < 2 and fin is None:
        raise ValueError(
            'sin bloque de notación ni colofón: no parece una obra de Aozora')
    if fin is None:
        fin = len(lineas)
    return titulo, autor, lineas[inicio:fin]


def parsear(texto_completo: str) -> dict:
    """Obra completa → {titulo, autor, parrafos: [(texto, furigana)]}."""
    titulo, autor, lineas = extraer_cuerpo(texto_completo)
    parrafos = []
    for linea in lineas:
        texto, furigana = limpiar_linea(linea)
        if texto:
            parrafos.append((texto, furigana))
    if not parrafos:
        raise ValueError(f'"{titulo}": cuerpo vacío tras la limpieza')
    return {'titulo': titulo, 'autor': autor, 'parrafos': parrafos}
