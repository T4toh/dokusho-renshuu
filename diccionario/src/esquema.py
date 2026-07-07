"""Esquema SQLite de diccionario.db. Ver spec: docs/superpowers/specs/."""

DB_VERSION = 1

DDL = """
CREATE TABLE metadata (
    clave TEXT PRIMARY KEY,
    valor TEXT NOT NULL
);

CREATE TABLE palabras (
    id INTEGER PRIMARY KEY,
    termino TEXT NOT NULL,
    lectura TEXT,
    significados TEXT NOT NULL,  -- JSON array de strings (inglés)
    tags TEXT,                   -- JSON array de strings
    popularidad INTEGER          -- score de Jitendex, para ordenar resultados
);

CREATE TABLE kanjis (
    kanji TEXT PRIMARY KEY,
    significados TEXT NOT NULL,  -- JSON array (inglés)
    on_yomi TEXT NOT NULL,       -- JSON array
    kun_yomi TEXT NOT NULL,      -- JSON array
    jlpt INTEGER,                -- escala vieja 1-4 de KANJIDIC2; NULL si no figura
    strokes INTEGER
);

CREATE TABLE oraciones (
    id INTEGER PRIMARY KEY,      -- id original de Tatoeba
    japones TEXT NOT NULL,
    ingles TEXT NOT NULL
);

CREATE TABLE oracion_kanji (
    kanji TEXT NOT NULL REFERENCES kanjis(kanji),
    id_oracion INTEGER NOT NULL REFERENCES oraciones(id),
    PRIMARY KEY (kanji, id_oracion)
);

CREATE TABLE oracion_palabra (
    termino TEXT NOT NULL,
    id_oracion INTEGER NOT NULL REFERENCES oraciones(id),
    PRIMARY KEY (termino, id_oracion)
);

CREATE INDEX idx_palabras_termino ON palabras(termino);
CREATE INDEX idx_palabras_lectura ON palabras(lectura);
"""
