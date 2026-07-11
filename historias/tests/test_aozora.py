import os
import unittest

from src import aozora


FIXTURE = os.path.join(os.path.dirname(__file__), 'fixtures',
                       'fragmento_aozora.txt')


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

    def test_rubies_adyacentes_no_solapan(self):
        # caso real momotaro: dos kanjis sueltos consecutivos, cada uno
        # con su propio ruby y sin ｜ entre ellos — el segundo ruby no
        # debe "robarle" el carácter base al primero.
        texto, furigana = aozora.limpiar_linea(
            '「おばあさん、今《いま》帰《かえ》ったよ。」')
        self.assertEqual(texto, '「おばあさん、今帰ったよ。」')
        self.assertEqual(furigana, [[7, 8, 'いま'], [8, 9, 'かえ']])

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

    def test_gaiji_conocido_se_resuelve(self):
        # string verificado con iconv contra fuentes/kumo_no_ito.txt: es el
        # gaiji real de 犍陀多 (Kandata), no uno inventado.
        texto, furigana = aozora.limpiar_linea(
            '※［＃「特のへん＋廴＋聿」、第3水準1-87-71］陀多')
        self.assertEqual(texto, '犍陀多')
        self.assertNotIn('※', texto)
        self.assertEqual(furigana, [])

    def test_gaiji_desconocido_deja_marca_residual(self):
        # anotación inventada, no está en _GAIJI_CONOCIDOS: el ※ debe
        # sobrevivir para que verify_catalogo.py lo detecte.
        texto, furigana = aozora.limpiar_linea(
            '※［＃「へん＋つくり」、第4水準9-99-99］伊呂波')
        self.assertEqual(texto, '※伊呂波')
        self.assertIn('※', texto)
        self.assertEqual(furigana, [])

    def test_anotacion_sin_marca_se_elimina_sin_dejar_residuo(self):
        # regresión: una anotación no-gaiji (sin ※) se sigue eliminando
        # por completo, como antes de agregar la tabla de gaiji.
        texto, furigana = aozora.limpiar_linea('［＃５字下げ］一')
        self.assertEqual(texto, '一')
        self.assertEqual(furigana, [])


class TestParsear(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(FIXTURE, encoding='utf-8') as f:
            cls.obra = aozora.parsear(f.read())

    def test_titulo_y_autor(self):
        self.assertEqual(self.obra['titulo'], '桃太郎')
        self.assertEqual(self.obra['autor'], '楠山正雄')

    def test_solo_el_cuerpo(self):
        # el bloque de notación y el colofón quedan afuera
        self.assertEqual(len(self.obra['parrafos']), 2)
        for texto, _ in self.obra['parrafos']:
            self.assertNotIn('底本', texto)
            self.assertNotIn('《', texto)
            self.assertNotIn('｜', texto)

    def test_furigana_alineada(self):
        texto, furigana = self.obra['parrafos'][0]
        inicio = texto.index('刈')
        self.assertIn([inicio, inicio + 1, 'か'], furigana)
        inicio = texto.index('洗濯')
        self.assertIn([inicio, inicio + 2, 'せんたく'], furigana)

    def test_archivo_invalido_falla_ruidoso(self):
        with self.assertRaises(ValueError):
            aozora.parsear('una línea\n')

    def test_texto_plano_sin_estructura_aozora_falla(self):
        with self.assertRaises(ValueError):
            aozora.parsear('línea uno\nlínea dos\nlínea tres\nlínea cuatro\n')


if __name__ == '__main__':
    unittest.main()
