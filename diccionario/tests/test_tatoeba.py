import os
import unittest

from src import tatoeba

FIXTURE = os.path.join(os.path.dirname(__file__), 'fixtures', 'pares_min.tsv')


class TestTatoeba(unittest.TestCase):
    def setUp(self):
        self.oraciones = tatoeba.parsear_pares(FIXTURE)

    def test_dedup_por_id_japones(self):
        # id 1 aparece dos veces: queda la primera traducción
        self.assertEqual(len(self.oraciones), 3)
        primera = self.oraciones[0]
        self.assertEqual(primera.id, 1)
        self.assertEqual(primera.japones, 'これは物語です。')
        self.assertEqual(primera.ingles, 'This is a tale.')

    def test_linea_malformada_se_ignora(self):
        import tempfile
        with tempfile.NamedTemporaryFile(
                'w', suffix='.tsv', delete=False, encoding='utf-8') as f:
            f.write('99\tsolo dos campos\n')
            ruta = f.name
        self.assertEqual(tatoeba.parsear_pares(ruta), [])
        os.unlink(ruta)


if __name__ == '__main__':
    unittest.main()
