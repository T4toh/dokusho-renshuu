import json
import os
import shutil
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
        # constructor espera nombres de producción: se copian los fixtures
        # a un dir "fuentes" temporal con esos nombres
        cls.fuentes = os.path.join(cls.tmp, 'fuentes')
        os.makedirs(cls.fuentes)
        shutil.copy(os.path.join(FIXTURES, 'term_bank_1.json'),
                    os.path.join(cls.fuentes, 'term_bank_1.json'))
        shutil.copy(os.path.join(FIXTURES, 'kanjidic2_min.xml'),
                    os.path.join(cls.fuentes, 'kanjidic2.xml'))
        shutil.copy(os.path.join(FIXTURES, 'pares_min.tsv'),
                    os.path.join(cls.fuentes, 'pares_jpn_eng.tsv'))
        cls.stats = constructor.construir(cls.ruta_db, cls.fuentes)
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


class TestCaps(unittest.TestCase):
    """Los caps por kanji (50) y por palabra (10) retienen las más cortas."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.ruta_db = os.path.join(cls.tmp, 'caps.db')
        cls.fuentes = os.path.join(cls.tmp, 'fuentes')
        os.makedirs(cls.fuentes)
        # una sola palabra con kanji
        with open(os.path.join(cls.fuentes, 'term_bank_1.json'), 'w',
                  encoding='utf-8') as f:
            json.dump([["物語", "ものがたり", "n", "", 100, ["tale"], 1, ""]],
                      f, ensure_ascii=False)
        # kanjidic con 物 y 語
        shutil.copy(os.path.join(FIXTURES, 'kanjidic2_min.xml'),
                    os.path.join(cls.fuentes, 'kanjidic2.xml'))
        # 60 oraciones con 物語, largos estrictamente crecientes (ids 1..60)
        with open(os.path.join(cls.fuentes, 'pares_jpn_eng.tsv'), 'w',
                  encoding='utf-8') as f:
            for i in range(1, 61):
                japones = f"物語{'あ' * i}です。"
                f.write(f'{i}\t{japones}\t{1000 + i}\tx\n')
        constructor.construir(cls.ruta_db, cls.fuentes)
        cls.conn = sqlite3.connect(cls.ruta_db)

    @classmethod
    def tearDownClass(cls):
        cls.conn.close()

    def test_cap_por_kanji(self):
        cuenta = self.conn.execute(
            "SELECT COUNT(*) FROM oracion_kanji WHERE kanji='物'").fetchone()[0]
        self.assertEqual(cuenta, constructor.MAX_ORACIONES_POR_KANJI)

    def test_cap_por_kanji_retiene_las_mas_cortas(self):
        ids = {f[0] for f in self.conn.execute(
            "SELECT id_oracion FROM oracion_kanji WHERE kanji='物'")}
        self.assertEqual(ids, set(range(1, 51)))

    def test_cap_por_palabra(self):
        cuenta = self.conn.execute(
            "SELECT COUNT(*) FROM oracion_palabra WHERE termino='物語'"
        ).fetchone()[0]
        self.assertEqual(cuenta, constructor.MAX_ORACIONES_POR_PALABRA)

    def test_cap_por_palabra_retiene_las_mas_cortas(self):
        ids = {f[0] for f in self.conn.execute(
            "SELECT id_oracion FROM oracion_palabra WHERE termino='物語'")}
        self.assertEqual(ids, set(range(1, 11)))


if __name__ == '__main__':
    unittest.main()
