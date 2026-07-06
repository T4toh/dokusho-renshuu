import os
import unittest

from src import jitendex

FIXTURES = os.path.join(os.path.dirname(__file__), 'fixtures')


class TestJitendex(unittest.TestCase):
    def setUp(self):
        self.palabras = jitendex.parsear_directorio(FIXTURES)

    def test_cantidad(self):
        self.assertEqual(len(self.palabras), 2)

    def test_structured_content(self):
        monogatari = next(p for p in self.palabras if p.termino == '物語')
        self.assertEqual(monogatari.lectura, 'ものがたり')
        self.assertEqual(monogatari.significados, ['tale', 'story (long)'])
        self.assertEqual(monogatari.popularidad, 4200)
        self.assertIn('common', monogatari.tags)

    def test_glosa_plana(self):
        neko = next(p for p in self.palabras if p.termino == '猫')
        self.assertEqual(neko.significados, ['cat'])
        # tags = unión de definition_tags y term_tags, ordenados
        self.assertEqual(neko.tags, ['animal', 'common', 'n'])


if __name__ == '__main__':
    unittest.main()
