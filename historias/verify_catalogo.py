"""Verificación integral del catálogo emitido.

Uso: python verify_catalogo.py [dir_catalogo]   (default: ../catalogo)
Exit 0 si todo OK, exit 1 si hay errores.
"""
import json
import os
import sys

DIFICULTADES = {'facil', 'media', 'dificil'}
CLAVES_CATALOGO = {'id', 'titulo', 'autor', 'dificultad', 'tamaño', 'version'}
CLAVES_HISTORIA = {'id', 'titulo', 'autor', 'fuente', 'licencia',
                   'dificultad', 'version', 'parrafos'}
# ※ queda si hubo gaiji ［＃...］ sin resolver: también es residuo
MARCADO_RESIDUAL = ('《', '》', '｜', '［＃', '※')


def _verificar_historia(ruta: str, id_: str) -> list:
    errores = []
    with open(ruta, encoding='utf-8') as f:
        historia = json.load(f)
    faltantes = CLAVES_HISTORIA - set(historia)
    if faltantes:
        return [f'{id_}: faltan claves {sorted(faltantes)}']
    if historia['id'] != id_:
        errores.append(f"{id_}: id interno {historia['id']!r} no coincide")
    if historia['dificultad'] not in DIFICULTADES:
        errores.append(f"{id_}: dificultad {historia['dificultad']!r} inválida")
    if not historia['parrafos']:
        errores.append(f'{id_}: sin párrafos')
    for p, parrafo in enumerate(historia['parrafos']):
        if not parrafo.get('oraciones'):
            errores.append(f'{id_} p{p}: párrafo sin oraciones')
        for o, oracion in enumerate(parrafo.get('oraciones', [])):
            donde = f'{id_} p{p} o{o}'
            texto = oracion.get('texto', '')
            if not texto:
                errores.append(f'{donde}: texto vacío')
                continue
            if any(m in texto for m in MARCADO_RESIDUAL):
                errores.append(
                    f'{donde}: marcado Aozora residual: {texto[:30]}')
            if oracion.get('traduccion') is not None:
                errores.append(f'{donde}: traduccion debe ser null en v1')
            for furi in oracion.get('furigana', []):
                if not isinstance(furi, list) or len(furi) != 3:
                    errores.append(f'{donde}: furigana inválida {furi}')
                    continue
                inicio, fin, lectura = furi
                if (not isinstance(inicio, int) or not isinstance(fin, int)
                        or not isinstance(lectura, str)):
                    errores.append(f'{donde}: furigana inválida {furi}')
                    continue
                if not (0 <= inicio < fin <= len(texto)) or not lectura:
                    errores.append(f'{donde}: furigana inválida {furi}')
    return errores


def verificar(dir_catalogo: str, imprimir: bool = False) -> list:
    errores = []
    ruta_catalogo = os.path.join(dir_catalogo, 'catalogo.json')
    if not os.path.exists(ruta_catalogo):
        return [f'no existe {ruta_catalogo}']
    with open(ruta_catalogo, encoding='utf-8') as f:
        catalogo = json.load(f)
    entradas = catalogo.get('historias', [])
    if not entradas:
        errores.append('catálogo sin historias')
    for entrada in entradas:
        faltantes = CLAVES_CATALOGO - set(entrada)
        if faltantes:
            errores.append(
                f"catálogo {entrada.get('id')}: faltan {sorted(faltantes)}")
            continue
        ruta = os.path.join(dir_catalogo, 'historias',
                            f"{entrada['id']}.json")
        if not os.path.exists(ruta):
            errores.append(f"catálogo: {entrada['id']} sin archivo {ruta}")
            continue
        real = os.path.getsize(ruta)
        if imprimir:
            print(f"  {entrada['id']}: {real:,} bytes, {entrada['dificultad']}")
        if entrada['tamaño'] != real:
            errores.append(
                f"{entrada['id']}: tamaño {entrada['tamaño']} != real {real}")
        errores.extend(_verificar_historia(ruta, entrada['id']))
    return errores


if __name__ == '__main__':
    dir_catalogo = sys.argv[1] if len(sys.argv) > 1 else '../catalogo'
    print(f'Verificando {dir_catalogo} ...')
    errores = verificar(dir_catalogo, imprimir=True)
    if errores:
        print('ERRORES:')
        for e in errores:
            print(f'  ✗ {e}')
        sys.exit(1)
    print('✓ OK')
