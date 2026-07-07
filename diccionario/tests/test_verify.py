import os
import shutil
import sqlite3
import tempfile
import unittest

import verify_db
from src import constructor

FIXTURES = os.path.join(os.path.dirname(__file__), 'fixtures')


class TestVerify(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.ruta_db = os.path.join(self.tmp, 'test.db')
        # constructor espera nombres de producción: se copian los fixtures
        # a un dir "fuentes" temporal con esos nombres
        self.fuentes = os.path.join(self.tmp, 'fuentes')
        os.makedirs(self.fuentes)
        shutil.copy(os.path.join(FIXTURES, 'term_bank_1.json'),
                    os.path.join(self.fuentes, 'term_bank_1.json'))
        shutil.copy(os.path.join(FIXTURES, 'kanjidic2_min.xml'),
                    os.path.join(self.fuentes, 'kanjidic2.xml'))
        shutil.copy(os.path.join(FIXTURES, 'pares_min.tsv'),
                    os.path.join(self.fuentes, 'pares_jpn_eng.tsv'))
        constructor.construir(self.ruta_db, self.fuentes)

    def test_db_valido_sin_errores(self):
        self.assertEqual(verify_db.verificar(self.ruta_db), [])

    def test_detecta_kanji_huerfano(self):
        conn = sqlite3.connect(self.ruta_db)
        conn.execute("INSERT INTO oracion_kanji VALUES ('犬', 1)")
        conn.commit()
        conn.close()
        errores = verify_db.verificar(self.ruta_db)
        self.assertTrue(any('犬' in e or 'foreign' in e.lower() or 'FK' in e
                            for e in errores))

    def test_detecta_tabla_vacia(self):
        conn = sqlite3.connect(self.ruta_db)
        conn.execute('DELETE FROM oracion_palabra')
        conn.commit()
        conn.close()
        errores = verify_db.verificar(self.ruta_db)
        self.assertTrue(any('oracion_palabra' in e for e in errores))


if __name__ == '__main__':
    unittest.main()
