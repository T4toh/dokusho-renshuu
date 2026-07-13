import unittest

from src import relleno_furigana


class TestCompletar(unittest.TestCase):
    def test_rellena_hueco_simple(self):
        resultado = relleno_furigana.completar('山へ行く', [])
        self.assertEqual(resultado, [[0, 1, 'やま'], [2, 3, 'い']])

    def test_respeta_ruby_existente(self):
        # la terna original queda intacta (aunque difiera de IPADIC)
        resultado = relleno_furigana.completar('山へ行く', [[0, 1, 'てすと']])
        self.assertEqual(resultado, [[0, 1, 'てすと'], [2, 3, 'い']])

    def test_token_parcialmente_cubierto_se_salta(self):
        # el span existente cubre solo 洗: el token 洗濯 se salta entero
        resultado = relleno_furigana.completar('洗濯に行く', [[0, 1, 'せん']])
        self.assertEqual(resultado, [[0, 1, 'せん'], [3, 4, 'い']])

    def test_sin_kanji_no_agrega(self):
        self.assertEqual(
            relleno_furigana.completar('カタカナとひらがな。', []), [])

    def test_trim_okurigana(self):
        resultado = relleno_furigana.completar('走った', [])
        self.assertEqual(resultado, [[0, 1, 'はし']])

    def test_oracion_real_ordenada_y_disjunta(self):
        texto = 'おばあさんは川へ洗濯に行きました。'
        resultado = relleno_furigana.completar(texto, [[8, 10, 'せんたく']])
        self.assertEqual(
            resultado,
            [[6, 7, 'かわ'], [8, 10, 'せんたく'], [11, 12, 'い']])
        for (_, fin_a, _), (ini_b, _, _) in zip(resultado, resultado[1:]):
            self.assertLessEqual(fin_a, ini_b)


if __name__ == '__main__':
    unittest.main()
