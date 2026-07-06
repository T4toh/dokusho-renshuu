import os
import unittest

from src import kanjidic

FIXTURE = os.path.join(os.path.dirname(__file__), 'fixtures', 'kanjidic2_min.xml')


class TestKanjidic(unittest.TestCase):
    def setUp(self):
        self.kanjis = {k.kanji: k for k in kanjidic.parsear_kanjidic(FIXTURE)}

    def test_cantidad(self):
        self.assertEqual(len(self.kanjis), 2)

    def test_kanji_completo(self):
        go = self.kanjis['語']
        self.assertEqual(go.on_yomi, ['ゴ'])
        self.assertEqual(go.kun_yomi, ['かた.る', 'かた.らう'])
        self.assertEqual(go.significados, ['word', 'speech'])  # sin m_lang="es"
        self.assertEqual(go.jlpt, 4)
        self.assertEqual(go.strokes, 14)

    def test_kanji_sin_jlpt(self):
        mono = self.kanjis['物']
        self.assertIsNone(mono.jlpt)
        self.assertEqual(mono.strokes, 8)


if __name__ == '__main__':
    unittest.main()
