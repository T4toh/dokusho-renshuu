import unittest

from src import segmentador


def _textos(texto):
    return [texto[i:f] for i, f in segmentador.segmentar(texto)]


class TestSegmentar(unittest.TestCase):
    def test_split_por_punto(self):
        self.assertEqual(
            _textos('むかしがありました。まいにち行きました。'),
            ['むかしがありました。', 'まいにち行きました。'])

    def test_exclamacion_e_interrogacion(self):
        self.assertEqual(
            _textos('来た！どこ？帰る。'),
            ['来た！', 'どこ？', '帰る。'])

    def test_dialogo_multi_oracion_no_se_parte(self):
        # el 。 dentro de 「」 no corta: la cita entera + coda = 1 oración
        self.assertEqual(
            _textos('「おや。これは。」と言いました。'),
            ['「おや。これは。」と言いました。'])

    def test_resto_sin_puntuacion_final(self):
        self.assertEqual(_textos('一'), ['一'])

    def test_texto_vacio(self):
        self.assertEqual(segmentador.segmentar(''), [])

    def test_cierre_residual_se_fusiona_con_la_anterior(self):
        # cierre de diálogo multi-párrafo: el 「 quedó en otro párrafo
        self.assertEqual(
            _textos('ドンブラコッコ、スッコッコ。」'),
            ['ドンブラコッコ、スッコッコ。」'])

    def test_puntuacion_consecutiva_no_genera_span_suelto(self):
        self.assertEqual(_textos('来た！？帰る。'), ['来た！？', '帰る。'])

    def test_residuo_inicial_se_fusiona_con_la_siguiente(self):
        self.assertEqual(_textos('。ほんとう。'), ['。ほんとう。'])


class TestSegmentarParrafo(unittest.TestCase):
    def test_furigana_se_reindexa_por_oracion(self):
        texto = 'しば刈りに行く。洗濯する。'
        furigana = [[2, 3, 'か'], [8, 10, 'せんたく']]
        oraciones = segmentador.segmentar_parrafo(texto, furigana)
        self.assertEqual(oraciones, [
            ('しば刈りに行く。', [[2, 3, 'か']]),
            ('洗濯する。', [[0, 2, 'せんたく']]),
        ])


class TestEsEncabezadoSeccion(unittest.TestCase):
    def test_numeral_simple_es_encabezado(self):
        self.assertTrue(segmentador.es_encabezado_seccion('一'))

    def test_numeral_con_espacios_es_encabezado(self):
        self.assertTrue(segmentador.es_encabezado_seccion('　二　'))
        self.assertTrue(segmentador.es_encabezado_seccion(' 三 '))

    def test_numeral_compuesto_es_encabezado(self):
        self.assertTrue(segmentador.es_encabezado_seccion('十一'))

    def test_oracion_real_no_es_encabezado(self):
        self.assertFalse(segmentador.es_encabezado_seccion('一つ、二つ。'))

    def test_texto_vacio_o_solo_espacios_no_es_encabezado(self):
        self.assertFalse(segmentador.es_encabezado_seccion(''))
        self.assertFalse(segmentador.es_encabezado_seccion('　'))


class TestSegmentarParrafoDescartaEncabezados(unittest.TestCase):
    def test_parrafo_de_solo_encabezado_queda_vacio(self):
        self.assertEqual(segmentador.segmentar_parrafo('一', []), [])

    def test_encabezado_no_deja_furigana_huerfana(self):
        # un encabezado nunca trae furigana real, pero si la tuviera
        # (dato corrupto) el descarte no debe romper el reindexado
        self.assertEqual(segmentador.segmentar_parrafo('二', []), [])


if __name__ == '__main__':
    unittest.main()
