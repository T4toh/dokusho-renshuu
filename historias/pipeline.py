"""CLI: construye el catálogo desde las obras de Aozora en fuentes/.

Uso: python pipeline.py [--fuentes fuentes] [--obras obras.json]
                        [--salida ../catalogo]
Las obras se declaran en obras.json; los .txt se descargan a mano
(ver README.md). Falla ruidoso: cualquier error aborta sin emitir.
"""
import argparse
import json
import os

from src import aozora, dificultad, emisor, relleno_furigana, segmentador

LICENCIA_DEFAULT = 'dominio público'


def leer_fuente(ruta: str) -> str:
    """Lee un .txt de Aozora (Shift_JIS; algunos archivos nuevos son UTF-8)."""
    with open(ruta, 'rb') as f:
        crudo = f.read()
    try:
        return crudo.decode('cp932')
    except UnicodeDecodeError:
        return crudo.decode('utf-8')


def procesar_obra(ruta_txt: str, declaracion: dict) -> dict:
    """Un .txt de Aozora → dict de historia listo para emitir."""
    obra = aozora.parsear(leer_fuente(ruta_txt))
    parrafos = [
        [(t, relleno_furigana.completar(t, f)) for t, f in oraciones]
        for texto, furigana in obra['parrafos']
        if (oraciones := segmentador.segmentar_parrafo(texto, furigana))
    ]
    textos = [t for oraciones in parrafos for t, _ in oraciones]
    return emisor.construir_historia(
        id_=declaracion['id'],
        titulo=declaracion.get('titulo', obra['titulo']),
        autor=declaracion.get('autor', obra['autor']),
        fuente=declaracion['fuente'],
        licencia=declaracion.get('licencia', LICENCIA_DEFAULT),
        dificultad=dificultad.calcular(textos),
        parrafos=parrafos,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fuentes', default='fuentes')
    parser.add_argument('--obras', default='obras.json')
    parser.add_argument('--salida', default='../catalogo')
    args = parser.parse_args()

    with open(args.obras, encoding='utf-8') as f:
        declaraciones = json.load(f)

    historias = []
    metadata = {}
    for decl in declaraciones:
        historia = procesar_obra(
            os.path.join(args.fuentes, decl['archivo']), decl)
        n_oraciones = sum(len(p['oraciones'])
                          for p in historia['parrafos'])
        print(f"  {historia['id']}: {len(historia['parrafos'])} párrafos, "
              f"{n_oraciones} oraciones, dificultad {historia['dificultad']}")
        historias.append(historia)
        metadata[historia['id']] = {
            'titulo_lectura': decl['titulo_lectura'],
            'titulo_en': decl.get('titulo_en'),
        }

    emisor.emitir(historias, args.salida, metadata)
    print(f'✓ {len(historias)} historias emitidas en {args.salida}/')
    print('Correr verify_catalogo.py antes de commitear.')


if __name__ == '__main__':
    main()
