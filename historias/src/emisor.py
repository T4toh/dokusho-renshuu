"""Emisión de los JSON de historias y del catálogo. Escritura atómica."""
import json
import os

VERSION_CATALOGO = 1


def construir_historia(id_, titulo, autor, fuente, licencia,
                       dificultad, parrafos) -> dict:
    """parrafos = [[(texto, furigana), ...] por párrafo] → dict del spec."""
    return {
        'id': id_,
        'titulo': titulo,
        'autor': autor,
        'fuente': fuente,
        'licencia': licencia,
        'dificultad': dificultad,
        'version': 1,
        'parrafos': [
            {'oraciones': [
                {'texto': texto, 'furigana': furigana, 'traduccion': None}
                for texto, furigana in oraciones
            ]}
            for oraciones in parrafos
        ],
    }


def _escribir_json(ruta: str, datos) -> None:
    """Atómico: tmp + replace. Nunca deja un JSON a medias."""
    tmp = ruta + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(datos, f, ensure_ascii=False, indent=1)
        f.write('\n')
    os.replace(tmp, ruta)


def emitir(historias: list, dir_catalogo: str) -> dict:
    """Escribe historias/<id>.json + catalogo.json. Devuelve stats."""
    dir_historias = os.path.join(dir_catalogo, 'historias')
    os.makedirs(dir_historias, exist_ok=True)
    entradas = []
    for historia in historias:
        ruta = os.path.join(dir_historias, f"{historia['id']}.json")
        _escribir_json(ruta, historia)
        entradas.append({
            'id': historia['id'],
            'titulo': historia['titulo'],
            'autor': historia['autor'],
            'dificultad': historia['dificultad'],
            'tamaño': os.path.getsize(ruta),
            'version': historia['version'],
        })
    _escribir_json(os.path.join(dir_catalogo, 'catalogo.json'),
                   {'version': VERSION_CATALOGO, 'historias': entradas})
    return {'historias': len(entradas)}
