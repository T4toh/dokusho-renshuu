import unittest

from src import japones


class TestJapones(unittest.TestCase):
    def test_es_kanji(self):
        self.assertTrue(japones.es_kanji('語'))
        self.assertFalse(japones.es_kanji('た'))   # hiragana
        self.assertFalse(japones.es_kanji('タ'))   # katakana
        self.assertFalse(japones.es_kanji('。'))
        self.assertFalse(japones.es_kanji('々'))   # marca de repetición

    def test_es_base_ruby(self):
        # la base implícita de un ruby incluye kanji y marcas como 々
        self.assertTrue(japones.es_base_ruby('語'))
        self.assertTrue(japones.es_base_ruby('々'))
        self.assertFalse(japones.es_base_ruby('た'))
        self.assertFalse(japones.es_base_ruby('。'))

    def test_extraer_kanjis_unicos_en_orden(self):
        self.assertEqual(japones.extraer_kanjis('物語は物語です。'), ['物', '語'])

    def test_extraer_kanjis_sin_kanji(self):
        self.assertEqual(japones.extraer_kanjis('ひらがなだけ'), [])


if __name__ == '__main__':
    unittest.main()
