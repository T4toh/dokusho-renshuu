"""Verificación integral de un diccionario.db generado.

Uso: python verify_db.py [ruta_db]   (default: diccionario-v1.db)
Exit 0 si todo OK, exit 1 si hay errores.
"""
import sqlite3
import sys

from src import constructor, esquema

TABLAS = ['palabras', 'kanjis', 'oraciones', 'oracion_kanji', 'oracion_palabra']
UMBRAL_SIGNIFICADOS_VACIOS = 0.01  # 1%


def verificar(ruta_db: str, imprimir: bool = False) -> list:
    errores = []
    conn = sqlite3.connect(ruta_db)

    # 1. counts: ninguna tabla vacía
    for tabla in TABLAS:
        n = conn.execute(f'SELECT COUNT(*) FROM {tabla}').fetchone()[0]
        if imprimir:
            print(f'  {tabla}: {n:,}')
        if n == 0:
            errores.append(f'tabla {tabla} vacía')

    # 2. versión
    fila = conn.execute(
        "SELECT valor FROM metadata WHERE clave='version'").fetchone()
    if fila is None or int(fila[0]) != esquema.DB_VERSION:
        errores.append(f'version en metadata != {esquema.DB_VERSION}')

    # 3. integridad FK
    for fk in conn.execute('PRAGMA foreign_key_check').fetchall():
        errores.append(f'violación FK: {fk}')

    # 4. cap de oraciones por kanji
    exceso = conn.execute(
        'SELECT kanji, COUNT(*) c FROM oracion_kanji GROUP BY kanji'
        ' HAVING c > ?', (constructor.MAX_ORACIONES_POR_KANJI,)).fetchall()
    if exceso:
        errores.append(
            f'kanjis con más de {constructor.MAX_ORACIONES_POR_KANJI}'
            f' oraciones: {exceso[:5]}')

    # 5. cap de oraciones por palabra
    exceso_palabra = conn.execute(
        'SELECT termino, COUNT(*) c FROM oracion_palabra GROUP BY termino'
        ' HAVING c > ?', (constructor.MAX_ORACIONES_POR_PALABRA,)).fetchall()
    if exceso_palabra:
        errores.append(
            f'palabras con más de {constructor.MAX_ORACIONES_POR_PALABRA}'
            f' oraciones: {exceso_palabra[:5]}')

    # 6. % de palabras con significados vacíos (detecta bugs de extracción
    # de glosas, como el de las entradas redirect de Jitendex)
    total_palabras = conn.execute('SELECT COUNT(*) FROM palabras').fetchone()[0]
    if total_palabras:
        vacias = conn.execute(
            "SELECT COUNT(*) FROM palabras WHERE significados = '[]'"
        ).fetchone()[0]
        porcentaje = vacias / total_palabras
        if imprimir:
            print(f'  palabras con significados vacíos: {vacias:,}/'
                  f'{total_palabras:,} ({porcentaje:.2%})')
        if porcentaje >= UMBRAL_SIGNIFICADOS_VACIOS:
            errores.append(
                f'{porcentaje:.2%} de las palabras tienen significados'
                f' vacíos (umbral: {UMBRAL_SIGNIFICADOS_VACIOS:.0%})')

    conn.close()
    return errores


def _spot_checks(ruta_db: str) -> list:
    """Solo para db reales (con fuentes completas): kanjis conocidos."""
    errores = []
    conn = sqlite3.connect(ruta_db)
    for kanji in ('語', '物', '日', '人'):
        if conn.execute('SELECT 1 FROM kanjis WHERE kanji=?',
                        (kanji,)).fetchone() is None:
            errores.append(f'spot-check: falta kanji {kanji}')
    if conn.execute("SELECT 1 FROM palabras WHERE termino='物語'"
                    ).fetchone() is None:
        errores.append('spot-check: falta palabra 物語')
    conn.close()
    return errores


if __name__ == '__main__':
    ruta = sys.argv[1] if len(sys.argv) > 1 else f'diccionario-v{esquema.DB_VERSION}.db'
    print(f'Verificando {ruta} ...')
    errores = verificar(ruta, imprimir=True) + _spot_checks(ruta)
    if errores:
        print('ERRORES:')
        for e in errores:
            print(f'  ✗ {e}')
        sys.exit(1)
    print('✓ OK')
