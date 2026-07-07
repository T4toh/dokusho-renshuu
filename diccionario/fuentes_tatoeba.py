"""Genera fuentes/pares_jpn_eng.tsv a partir de los exports por idioma de Tatoeba.

Tatoeba ya no ofrece un export directo "Sentence pairs" jpn→eng listo para
descargar; en su lugar hay que cruzar tres archivos por idioma/par
(ver https://downloads.tatoeba.org/exports/per_language/):
  jpn_sentences.tsv     (id, lang, texto)      de per_language/jpn/
  eng_sentences.tsv     (id, lang, texto)      de per_language/eng/
  jpn-eng_links.tsv     (id_jpn, id_eng)       de per_language/jpn/

Este script los combina y escribe pares_jpn_eng.tsv con las 4 columnas que
espera src.tatoeba.parsear_pares: id_jpn, japonés, id_eng, inglés.

Uso: python fuentes_tatoeba.py [--fuentes fuentes]
"""
import argparse
import os


def _leer_oraciones(ruta: str) -> dict:
    """Lee un archivo *_sentences.tsv (id, lang, texto) -> {id: texto}."""
    oraciones = {}
    with open(ruta, encoding='utf-8') as f:
        for linea in f:
            campos = linea.rstrip('\r\n').split('\t')
            if len(campos) < 3:
                continue  # línea malformada: ignorar
            oraciones[campos[0]] = campos[2]
    return oraciones


def generar_pares(dir_fuentes: str) -> int:
    """Cruza jpn_sentences + eng_sentences vía jpn-eng_links y escribe
    dir_fuentes/pares_jpn_eng.tsv. Retorna la cantidad de pares escritos.
    """
    jpn = _leer_oraciones(os.path.join(dir_fuentes, 'jpn_sentences.tsv'))
    eng = _leer_oraciones(os.path.join(dir_fuentes, 'eng_sentences.tsv'))
    ruta_links = os.path.join(dir_fuentes, 'jpn-eng_links.tsv')
    ruta_salida = os.path.join(dir_fuentes, 'pares_jpn_eng.tsv')

    escritos = 0
    with open(ruta_links, encoding='utf-8') as f_links, \
            open(ruta_salida, 'w', encoding='utf-8') as f_out:
        for linea in f_links:
            campos = linea.rstrip('\r\n').split('\t')
            if len(campos) < 2:
                continue  # línea malformada: ignorar
            id_jpn, id_eng = campos[0], campos[1]
            texto_jpn = jpn.get(id_jpn)
            texto_eng = eng.get(id_eng)
            if texto_jpn is None or texto_eng is None:
                continue  # link sin oración correspondiente: ignorar
            f_out.write(f'{id_jpn}\t{texto_jpn}\t{id_eng}\t{texto_eng}\n')
            escritos += 1
    return escritos


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fuentes', default='fuentes')
    args = parser.parse_args()
    n = generar_pares(args.fuentes)
    print(f'{n:,} pares escritos en {args.fuentes}/pares_jpn_eng.tsv')
