import os
import tempfile
import unittest

import genera_jlpt

FIXTURE = os.path.join(os.path.dirname(__file__), 'fixtures',
                       'kanjidic2_min.xml')


class TestGeneraJlpt(unittest.TestCase):
    def test_extraer_n5_n4(self):
        # 語 tiene jlpt 4 (≈N5); 物 no tiene jlpt → queda afuera
        self.assertEqual(genera_jlpt.extraer_n5_n4(FIXTURE), ['語'])

    def test_modulo_generado_es_importable(self):
        with tempfile.TemporaryDirectory() as tmp:
            ruta = os.path.join(tmp, 'jlpt.py')
            n = genera_jlpt.generar(FIXTURE, ruta)
            self.assertEqual(n, 1)
            ns = {}
            with open(ruta, encoding='utf-8') as f:
                exec(f.read(), ns)
            self.assertIn('語', ns['KANJI_N5_N4'])
            self.assertNotIn('物', ns['KANJI_N5_N4'])


if __name__ == '__main__':
    unittest.main()
