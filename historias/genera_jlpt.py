"""Genera src/jlpt.py con el set de kanjis JLPT N5-N4 desde KANJIDIC2.

KANJIDIC2 usa la escala vieja 1-4: nivel 4 ≈ N5, nivel 3 ≈ N4.
Uso: python genera_jlpt.py [--kanjidic ../diccionario/fuentes/kanjidic2.xml]
El módulo generado se commitea; regenerar solo si cambia KANJIDIC2.
"""
import argparse
import xml.etree.ElementTree as ET

PLANTILLA = '''"""Kanjis JLPT N5-N4 (niveles 4 y 3 de la escala vieja de KANJIDIC2).

Generado por genera_jlpt.py — no editar a mano.
"""

KANJI_N5_N4 = frozenset(
    '{kanjis}'
)
'''


def extraer_n5_n4(ruta_kanjidic: str) -> list:
    """Kanjis con jlpt 4 o 3, ordenados (output determinístico)."""
    kanjis = []
    # iterparse: el archivo real pesa ~15 MB
    for _, elem in ET.iterparse(ruta_kanjidic, events=('end',)):
        if elem.tag != 'character':
            continue
        if elem.findtext('misc/jlpt') in ('4', '3'):
            kanjis.append(elem.findtext('literal'))
        elem.clear()
    return sorted(kanjis)


def generar(ruta_kanjidic: str, ruta_salida: str) -> int:
    kanjis = extraer_n5_n4(ruta_kanjidic)
    with open(ruta_salida, 'w', encoding='utf-8') as f:
        f.write(PLANTILLA.format(kanjis=''.join(kanjis)))
    return len(kanjis)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kanjidic',
                        default='../diccionario/fuentes/kanjidic2.xml')
    parser.add_argument('--salida', default='src/jlpt.py')
    args = parser.parse_args()
    n = generar(args.kanjidic, args.salida)
    print(f'✓ {args.salida}: {n} kanjis N5-N4')


if __name__ == '__main__':
    main()
