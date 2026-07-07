import unittest

from src import dificultad


class TestDificultad(unittest.TestCase):
    def test_facil_kanji_conocidos_y_oraciones_cortas(self):
        self.assertEqual(
            dificultad.calcular(['山へ行く。'], kanji_conocidos={'山', '行'}),
            'facil')

    def test_dificil_por_kanji_desconocidos(self):
        # 2 de 3 kanjis fuera del set → pct 0.67 ≥ 0.45
        self.assertEqual(
            dificultad.calcular(['鬱蒼たる森。'], kanji_conocidos={'森'}),
            'dificil')

    def test_dificil_por_oraciones_largas(self):
        larga = 'あ' * 60 + '。'
        self.assertEqual(
            dificultad.calcular([larga], kanji_conocidos=set()), 'dificil')

    def test_media(self):
        # pct 1/3 ≈ 0.33: entre 0.25 y 0.45
        self.assertEqual(
            dificultad.calcular(['山山森。'], kanji_conocidos={'山'}), 'media')

    def test_sin_kanji_es_facil(self):
        self.assertEqual(
            dificultad.calcular(['ひらがなだけ。'], kanji_conocidos=set()),
            'facil')

    def test_metricas(self):
        m = dificultad.metricas(['山山森。'], kanji_conocidos={'山'})
        self.assertAlmostEqual(m['pct_fuera'], 1 / 3)
        self.assertAlmostEqual(m['largo_promedio'], 4.0)


if __name__ == '__main__':
    unittest.main()
