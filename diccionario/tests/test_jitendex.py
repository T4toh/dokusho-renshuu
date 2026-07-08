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

    def test_descarta_pos_embebido_y_ejemplo_inline(self):
        # Reproduce el bug real (docs/ESTADO.md 2026-07-08 / backlog Plan 1
        # "li-anidado en li se aplana en una glosa"): un <li> de sentido trae,
        # además de las sub-glosas, un bloque de info gramatical por-sentido
        # (más específico que def_tags/term_tags, que solo traen un set
        # plano) y una oración de ejemplo embebida con furigana. El código
        # actual concatena todo eso en un solo string; el fix debe descartar
        # el bloque de PoS y el de ejemplo, y tratar cada <li> anidado como
        # su propia glosa.
        # Marcas verificadas contra term_bank_*.json reales (Jitendex
        # yomitan release descargado desde
        # github.com/stephenmk/stephenmk.github.io): la info gramatical
        # por-sentido real usa la marca 'part-of-speech-info' (no 'pos-info'
        # como se había hipotetizado), y aparece como varios <span> hermanos
        # -uno por tag- en vez de uno solo envolviendo todo; acá se simplifica
        # a un solo <span> por brevedad, sin cambiar la marca a verificar. La
        # marca 'example-sentence' sí coincidió con la hipótesis original.
        glossary = [{
            "type": "structured-content",
            "content": [
                {"tag": "ol", "content": [
                    {"tag": "li", "content": [
                        {"tag": "span",
                         "data": {"content": "part-of-speech-info"},
                         "content": [
                            {"tag": "i", "content": "noun"},
                            {"tag": "i", "content": "suru"},
                            {"tag": "i", "content": "transitive"},
                        ]},
                        {"tag": "ul", "content": [
                            {"tag": "li", "content": "washing"},
                            {"tag": "li", "content": "laundry"},
                        ]},
                        {"tag": "div", "data": {"content": "example-sentence"},
                         "content": [
                            {"tag": "div", "lang": "ja", "content": [
                                "この",
                                {"tag": "ruby", "content": [
                                    "綿", {"tag": "rt", "content": "めん"}]},
                                "の服を洗濯した",
                            ]},
                            {"tag": "div", "lang": "en",
                             "content": "I washed this cotton clothing."},
                        ]},
                    ]},
                ]},
            ],
        }]
        self.assertEqual(jitendex.extraer_glosas(glossary),
                          ['washing', 'laundry'])

    def test_descarta_field_info_y_antonym_markers(self):
        # Reproduce el bug real con 圧縮 (term_bank_13.json): un sentido
        # (sense) trae info específica del campo (field-info, p.ej.
        # "computing") y un bloque antonym con dos marcas:
        # - antonym-content: el rótulo "Antonym" y el kanji referenciado
        # - antonym-glossary: la glosa del antónimo
        # Ambas marcas deben descartarse para que la glosa final sea limpia.
        # Sin el fix, la glosa incluiría: "computing" + "Antonym解凍②..." +
        # glosa real. Con el fix, solo la glosa real.
        glossary = [{
            "type": "structured-content",
            "content": [
                {"tag": "ol", "content": [
                    {"tag": "li", "content": [
                        {"tag": "span",
                         "title": "computing",
                         "data": {"content": "field-info"},
                         "content": "computing"},
                        {"tag": "ul", "data": {"content": "glossary"},
                         "content": {"tag": "li", "content": "compression (of data)"}},
                        {"tag": "div", "data": {"content": "extra-info"},
                         "content": [
                             {"tag": "div", "data": {"content": "antonym-content"},
                              "content": [
                                  {"tag": "span", "content": "Antonym"},
                                  {"tag": "a", "content": [
                                      {"tag": "ruby", "content": [
                                          "解", {"tag": "rt", "content": "かい"}]},
                                      {"tag": "ruby", "content": [
                                          "凍", {"tag": "rt", "content": "とう"}]},
                                  ]},
                              ]},
                             {"tag": "div", "data": {"content": "antonym-glossary"},
                              "content": "② decompression (of data); extraction; unpacking; unzipping"},
                         ]},
                    ]},
                ]},
            ],
        }]
        # La glosa debe contener solo "compression (of data)", sin marcadores.
        self.assertEqual(jitendex.extraer_glosas(glossary),
                          ['compression (of data)'])


if __name__ == '__main__':
    unittest.main()
