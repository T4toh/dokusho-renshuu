"""CLI: construye diccionario.db desde las fuentes descargadas.

Uso: python parser.py [--fuentes fuentes] [--salida diccionario-v1.db]
Fuentes esperadas en --fuentes (ver README.md):
  term_bank_*.json  (Jitendex)  ·  kanjidic2.xml  ·  pares_jpn_eng.tsv
"""
import argparse

from src import constructor, esquema


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fuentes', default='fuentes')
    parser.add_argument('--salida',
                        default=f'diccionario-v{esquema.DB_VERSION}.db')
    args = parser.parse_args()

    print(f'Construyendo {args.salida} desde {args.fuentes}/ ...')
    stats = constructor.construir(args.salida, args.fuentes)
    for clave, valor in stats.items():
        print(f'  {clave}: {valor:,}')
    print('Listo. Correr verify_db.py antes de publicar.')


if __name__ == '__main__':
    main()
