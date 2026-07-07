"""Limpieza de marcado Aozora Bunko y extracción de furigana (ruby)."""
import re

from . import japones

_ANOTACION = re.compile(r'［＃.*?］')


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
                while inicio > 0 and japones.es_base_ruby(texto[inicio - 1]):
                    inicio -= 1
            if lectura and inicio < len(texto):
                furigana.append([inicio, len(texto), lectura])
            inicio_marcado = None
            i = cierre + 1
        else:
            texto.append(c)
            i += 1
    return ''.join(texto), furigana
