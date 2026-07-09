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

    def test_version_es_2(self):
        # Plan 3.5 Frente A: bump de esquema por el fix de glosas de Jitendex
        # (Task 1) — la app exige version==2 (VERSION_ESPERADA, fuera de esta
        # scope) y el nombre del asset publicado es diccionario-v2.db.
        self.assertEqual(esquema.DB_VERSION, 2)

    def test_indices_existen(self):
        indices = {
            fila[0] for fila in self.conn.execute(
                "SELECT name FROM sqlite_master WHERE type='index'")
        }
        self.assertIn('idx_palabras_termino', indices)
        self.assertIn('idx_palabras_lectura', indices)


if __name__ == '__main__':
    unittest.main()
