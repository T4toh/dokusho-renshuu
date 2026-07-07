import os
import unittest

from src import jitendex

FIXTURES = os.path.join(os.path.dirname(__file__), 'fixtures')


class TestJitendex(unittest.TestCase):
    def setUp(self):
        self.palabras = jitendex.parsear_directorio(FIXTURES)

    def test_cantidad(self):
        self.assertEqual(len(self.palabras), 3)

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

    def test_redirect_extrae_significado_del_target(self):
        # entradas redirect (formas viejas de kanji) no deben perder el
        # significado: se guarda una glosa "→ forma nueva"
        akudoi = next(p for p in self.palabras if p.termino == '惡どい')
        self.assertEqual(akudoi.significados, ['→ 悪どい'])

    def test_redirect_sin_lista_plana_usa_fallback_del_nodo_a(self):
        # algunas entradas redirect (~1000 en las fuentes reales) solo traen
        # el nodo structured-content, sin el item de lista plana acompañante
        glossary = [{
            "type": "structured-content",
            "content": {
                "tag": "div", "lang": "ja",
                "data": {"content": "redirect-glossary"},
                "content": ["⟶", {
                    "tag": "a",
                    "href": "?query=%E4%BC%8A%E9%81%94%E3%83%A1%E3%82%AC%E3%83%8D",
                    "content": [
                        {"tag": "ruby", "content": ["伊達", {"tag": "rt", "content": "だて"}]},
                        "メガネ"
                    ]
                }]
            }
        }]
        self.assertEqual(jitendex.extraer_glosas(glossary), ['→ 伊達メガネ'])


if __name__ == '__main__':
    unittest.main()
