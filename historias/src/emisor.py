"""Emisión de los JSON de historias y del catálogo. Escritura atómica."""
import json
import os

from . import japones

VERSION_CATALOGO = 2
VERSION_HISTORIA = 2

# claves que sí van al JSON de historia en disco (schema sin cambios de v1)
_CLAVES_HISTORIA_EN_DISCO = (
    'id', 'titulo', 'autor', 'fuente', 'licencia', 'dificultad', 'version',
    'parrafos')


def construir_historia(id_, titulo, autor, fuente, licencia,
                       dificultad, parrafos, traducciones=None) -> dict:
    """parrafos = [[(texto, furigana), ...] por párrafo] → dict del spec.

    `traducciones` (opcional, backlog feedback de uso 2026-07-13): lista
    plana de {'texto', 'traduccion'} en orden párrafo→oración — el mismo
    recorrido de este constructor. Se valida conteo Y texto exacto por
    posición (falla ruidoso ante cualquier drift de segmentación entre el
    archivo de traducciones y la fuente); `traduccion` debe ser string no
    vacío. Sin traducciones, `traduccion` queda null (comportamiento v1).

    Incluye `kanjis_unicos` y `oraciones` en el dict en memoria (insumo
    para el catálogo v2); no forman parte del schema del JSON de historia
    en disco — ver `_CLAVES_HISTORIA_EN_DISCO`.
    """
    texto_completo = ''.join(
        texto for oraciones in parrafos for texto, _ in oraciones)
    planas = [texto for oraciones in parrafos for texto, _ in oraciones]
    por_oracion = _validar_traducciones(id_, planas, traducciones)
    contador = iter(range(len(planas)))
    return {
        'id': id_,
        'titulo': titulo,
        'autor': autor,
        'fuente': fuente,
        'licencia': licencia,
        'dificultad': dificultad,
        'version': VERSION_HISTORIA,
        'kanjis_unicos': len(japones.extraer_kanjis(texto_completo)),
        'oraciones': sum(len(oraciones) for oraciones in parrafos),
        'parrafos': [
            {'oraciones': [
                {'texto': texto, 'furigana': furigana,
                 'traduccion': por_oracion[next(contador)]}
                for texto, furigana in oraciones
            ]}
            for oraciones in parrafos
        ],
    }


def _validar_traducciones(id_, planas, traducciones):
    """None → lista de None (v1). Con traducciones: conteo y texto exactos."""
    if traducciones is None:
        return [None] * len(planas)
    if len(traducciones) != len(planas):
        raise ValueError(
            f'{id_}: {len(traducciones)} traducciones para '
            f'{len(planas)} oraciones')
    resultado = []
    for i, (texto, entrada) in enumerate(zip(planas, traducciones)):
        if entrada.get('texto') != texto:
            raise ValueError(
                f'{id_} oración {i}: texto de traducción no coincide '
                f'({entrada.get("texto", "")[:20]!r} != {texto[:20]!r})')
        traduccion = entrada.get('traduccion')
        if not isinstance(traduccion, str) or not traduccion:
            raise ValueError(
                f'{id_} oración {i}: traduccion inválida {traduccion!r}')
        resultado.append(traduccion)
    return resultado


def _escribir_json(ruta: str, datos) -> None:
    """Atómico: tmp + replace. Nunca deja un JSON a medias."""
    tmp = ruta + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(datos, f, ensure_ascii=False, indent=1)
        f.write('\n')
    os.replace(tmp, ruta)


def emitir(historias: list, dir_catalogo: str, metadata: dict) -> dict:
    """Escribe historias/<id>.json + catalogo.json. Devuelve stats.

    `metadata` = {id: {'titulo_lectura': str, 'titulo_en': str|None}},
    curado a mano en la config del pipeline (ver `obras.json`).
    """
    dir_historias = os.path.join(dir_catalogo, 'historias')
    os.makedirs(dir_historias, exist_ok=True)
    entradas = []
    for historia in historias:
        ruta = os.path.join(dir_historias, f"{historia['id']}.json")
        datos_historia = {clave: historia[clave]
                          for clave in _CLAVES_HISTORIA_EN_DISCO}
        _escribir_json(ruta, datos_historia)
        meta = metadata[historia['id']]
        entradas.append({
            'id': historia['id'],
            'titulo': historia['titulo'],
            'titulo_lectura': meta['titulo_lectura'],
            'titulo_en': meta['titulo_en'],
            'autor': historia['autor'],
            'dificultad': historia['dificultad'],
            'tamaño': os.path.getsize(ruta),
            'version': historia['version'],
            'kanjis_unicos': historia['kanjis_unicos'],
            'oraciones': historia['oraciones'],
        })
    _escribir_json(os.path.join(dir_catalogo, 'catalogo.json'),
                   {'version': VERSION_CATALOGO, 'historias': entradas})
    return {'historias': len(entradas)}
