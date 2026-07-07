import json
import os
import sqlite3
import tempfile
import unittest

from src import constructor, esquema

FIXTURES = os.path.join(os.path.dirname(__file__), 'fixtures')


class TestConstructor(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.ruta_db = os.path.join(cls.tmp, 'test.db')
        # fixtures/ tiene term_bank_1.json, kanjidic2_min.xml y pares_min.tsv
        # con nombres que constructor espera: ver construir()
        cls.stats = constructor.construir(cls.ruta_db, FIXTURES)
        cls.conn = sqlite3.connect(cls.ruta_db)

    @classmethod
    def tearDownClass(cls):
        cls.conn.close()

    def test_stats(self):
        self.assertEqual(self.stats['palabras'], 2)
        self.assertEqual(self.stats['kanjis'], 2)
        # oración 3 es kana-only → descartada. Quedan 1 (物語) y 2 (猫... sin
        # kanji en kanjidic_min → según fixture 猫 no está en kanjis, también
        # descartada). Retenida: solo la oración 1.
        self.assertEqual(self.stats['oraciones'], 1)

    def test_version_en_metadata(self):
        valor = self.conn.execute(
            "SELECT valor FROM metadata WHERE clave='version'").fetchone()[0]
        self.assertEqual(int(valor), esquema.DB_VERSION)

    def test_significados_son_json(self):
        fila = self.conn.execute(
            "SELECT significados FROM palabras WHERE termino='物語'").fetchone()
        self.assertEqual(json.loads(fila[0]), ['tale', 'story (long)'])

    def test_oracion_kanji(self):
        ids = [f[0] for f in self.conn.execute(
            "SELECT id_oracion FROM oracion_kanji WHERE kanji='語'")]
        self.assertEqual(ids, [1])

    def test_oracion_palabra(self):
        ids = [f[0] for f in self.conn.execute(
            "SELECT id_oracion FROM oracion_palabra WHERE termino='物語'")]
        self.assertEqual(ids, [1])

    def test_integridad_fk(self):
        errores = self.conn.execute('PRAGMA foreign_key_check').fetchall()
        self.assertEqual(errores, [])


if __name__ == '__main__':
    unittest.main()
