import unittest

from src import japones


class TestJapones(unittest.TestCase):
    def test_es_kanji(self):
        self.assertTrue(japones.es_kanji('語'))
        self.assertTrue(japones.es_kanji('物'))
        self.assertFalse(japones.es_kanji('た'))   # hiragana
        self.assertFalse(japones.es_kanji('タ'))   # katakana
        self.assertFalse(japones.es_kanji('a'))
        self.assertFalse(japones.es_kanji('。'))
        self.assertFalse(japones.es_kanji('々'))   # marca de repetición, no kanji

    def test_extraer_kanjis_unicos_en_orden(self):
        self.assertEqual(
            japones.extraer_kanjis('物語は物語です。'),
            ['物', '語'])

    def test_extraer_kanjis_sin_kanji(self):
        self.assertEqual(japones.extraer_kanjis('ひらがなだけ'), [])


if __name__ == '__main__':
    unittest.main()
