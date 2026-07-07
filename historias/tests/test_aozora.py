import unittest

from src import aozora


class TestLimpiarLinea(unittest.TestCase):
    def test_ruby_kanji_simple(self):
        texto, furigana = aozora.limpiar_linea('しば刈《か》りに行く')
        self.assertEqual(texto, 'しば刈りに行く')
        self.assertEqual(furigana, [[2, 3, 'か']])

    def test_ruby_multi_kanji(self):
        texto, furigana = aozora.limpiar_linea('洗濯《せんたく》に')
        self.assertEqual(texto, '洗濯に')
        self.assertEqual(furigana, [[0, 2, 'せんたく']])

    def test_ruby_con_marca_de_repeticion(self):
        texto, furigana = aozora.limpiar_linea('昔々《むかしむかし》、')
        self.assertEqual(texto, '昔々、')
        self.assertEqual(furigana, [[0, 2, 'むかしむかし']])

    def test_barra_marca_base_explicita(self):
        # sin ｜ la base sería solo 土産; con ｜ incluye el お
        texto, furigana = aozora.limpiar_linea('これは｜お土産《おみやげ》だ')
        self.assertEqual(texto, 'これはお土産だ')
        self.assertEqual(furigana, [[3, 6, 'おみやげ']])

    def test_multiples_rubies_en_una_linea(self):
        texto, furigana = aozora.limpiar_linea('山《やま》と川《かわ》')
        self.assertEqual(texto, '山と川')
        self.assertEqual(furigana, [[0, 1, 'やま'], [2, 3, 'かわ']])

    def test_anotacion_se_elimina(self):
        texto, furigana = aozora.limpiar_linea(
            '［＃５字下げ］一［＃「一」は中見出し］')
        self.assertEqual(texto, '一')
        self.assertEqual(furigana, [])

    def test_sangria_fullwidth_se_elimina(self):
        texto, _ = aozora.limpiar_linea('　むかし、むかし。')
        self.assertEqual(texto, 'むかし、むかし。')

    def test_apertura_sin_cierre_queda_literal(self):
        texto, furigana = aozora.limpiar_linea('壊れた《ルビ')
        self.assertEqual(texto, '壊れた《ルビ')
        self.assertEqual(furigana, [])

    def test_ruby_sin_base_se_ignora(self):
        texto, furigana = aozora.limpiar_linea('《よみ》')
        self.assertEqual(texto, '')
        self.assertEqual(furigana, [])


if __name__ == '__main__':
    unittest.main()
