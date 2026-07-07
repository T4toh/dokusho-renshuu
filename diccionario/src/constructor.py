"""Orquesta el build de diccionario.db a partir de las fuentes."""
import json
import os
import sqlite3
from collections import defaultdict

from . import esquema, japones, jitendex, kanjidic, tatoeba

MAX_ORACIONES_POR_KANJI = 50
MAX_ORACIONES_POR_PALABRA = 10
LARGO_MIN_TERMINO = 2
LARGO_MAX_TERMINO = 6

ARCHIVO_KANJIDIC = 'kanjidic2_min.xml'  # en fuentes reales: kanjidic2.xml
ARCHIVO_TATOEBA = 'pares_min.tsv'       # en fuentes reales: pares_jpn_eng.tsv


def _json(lista) -> str:
    return json.dumps(lista, ensure_ascii=False)


def _resolver(dir_fuentes: str, preferido: str, alternativo: str) -> str:
    """Usa el nombre real si existe, si no el de fixture (para tests)."""
    ruta = os.path.join(dir_fuentes, preferido)
    return ruta if os.path.exists(ruta) else os.path.join(dir_fuentes, alternativo)


def construir(ruta_db: str, dir_fuentes: str) -> dict:
    palabras = jitendex.parsear_directorio(dir_fuentes)
    kanjis = kanjidic.parsear_kanjidic(
        _resolver(dir_fuentes, 'kanjidic2.xml', ARCHIVO_KANJIDIC))
    oraciones = tatoeba.parsear_pares(
        _resolver(dir_fuentes, 'pares_jpn_eng.tsv', ARCHIVO_TATOEBA))

    kanjis_conocidos = {k.kanji for k in kanjis}

    # kanji → oraciones que lo usan, cortas primero, con cap
    por_kanji = defaultdict(list)
    for o in oraciones:
        for k in japones.extraer_kanjis(o.japones):
            if k in kanjis_conocidos:
                por_kanji[k].append(o)
    for k in por_kanji:
        por_kanji[k].sort(key=lambda o: len(o.japones))
        por_kanji[k] = por_kanji[k][:MAX_ORACIONES_POR_KANJI]

    # solo se guardan oraciones referenciadas por algún kanji
    retenidas = {o.id: o for lista in por_kanji.values() for o in lista}

    # palabra → oraciones retenidas que la contienen (substring), con cap
    terminos_con_kanji = {
        p.termino for p in palabras
        if LARGO_MIN_TERMINO <= len(p.termino) <= LARGO_MAX_TERMINO
        and any(japones.es_kanji(c) for c in p.termino)
    }
    por_palabra = defaultdict(list)
    for o in sorted(retenidas.values(), key=lambda o: len(o.japones)):
        texto = o.japones
        encontrados = set()
        for i in range(len(texto)):
            for largo in range(LARGO_MIN_TERMINO, LARGO_MAX_TERMINO + 1):
                sub = texto[i:i + largo]
                if sub in terminos_con_kanji:
                    encontrados.add(sub)
        for termino in encontrados:
            if len(por_palabra[termino]) < MAX_ORACIONES_POR_PALABRA:
                por_palabra[termino].append(o.id)

    if os.path.exists(ruta_db):
        os.remove(ruta_db)
    conn = sqlite3.connect(ruta_db)
    conn.executescript(esquema.DDL)
    conn.execute("INSERT INTO metadata VALUES ('version', ?)",
                 (str(esquema.DB_VERSION),))
    conn.executemany(
        'INSERT INTO palabras (termino, lectura, significados, tags, popularidad)'
        ' VALUES (?, ?, ?, ?, ?)',
        [(p.termino, p.lectura, _json(p.significados), _json(p.tags),
          p.popularidad) for p in palabras])
    conn.executemany(
        'INSERT OR IGNORE INTO kanjis VALUES (?, ?, ?, ?, ?, ?)',
        [(k.kanji, _json(k.significados), _json(k.on_yomi), _json(k.kun_yomi),
          k.jlpt, k.strokes) for k in kanjis])
    conn.executemany(
        'INSERT INTO oraciones VALUES (?, ?, ?)',
        [(o.id, o.japones, o.ingles) for o in retenidas.values()])
    conn.executemany(
        'INSERT INTO oracion_kanji VALUES (?, ?)',
        [(k, o.id) for k, lista in por_kanji.items() for o in lista])
    conn.executemany(
        'INSERT INTO oracion_palabra VALUES (?, ?)',
        [(t, id_o) for t, ids in por_palabra.items() for id_o in ids])
    conn.commit()

    stats = {
        'palabras': len(palabras),
        'kanjis': len(kanjis),
        'oraciones': len(retenidas),
        'oracion_kanji': sum(len(v) for v in por_kanji.values()),
        'oracion_palabra': sum(len(v) for v in por_palabra.values()),
    }
    conn.close()
    return stats
