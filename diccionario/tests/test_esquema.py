import sqlite3
import unittest

from src import esquema


class TestEsquema(unittest.TestCase):
    def setUp(self):
        self.conn = sqlite3.connect(':memory:')
        self.conn.executescript(esquema.DDL)

    def tearDown(self):
        self.conn.close()

    def test_tablas_existen(self):
        tablas = {
            fila[0] for fila in self.conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'")
        }
        esperadas = {'metadata', 'palabras', 'kanjis', 'oraciones',
                     'oracion_kanji', 'oracion_palabra'}
        self.assertTrue(esperadas.issubset(tablas))

    def test_version_es_entero_positivo(self):
        self.assertIsInstance(esquema.DB_VERSION, int)
        self.assertGreater(esquema.DB_VERSION, 0)


if __name__ == '__main__':
    unittest.main()
