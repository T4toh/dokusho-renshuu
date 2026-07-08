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

    def test_descarta_xref_forms_y_attribution(self):
        # Reproduce el bug real con 洗濯 (term_bank_36.json, review final
        # 2026-07-08 sobre diccionario-v2.db): un sense-group trae un xref
        # embebido ("See also" + palabra referenciada + su glosa) y, además
        # del <ul> de sense-groups, hay un <li> HERMANO con data.content
        # 'forms' (variantes de escritura/lectura) y un <div> 'attribution'
        # (créditos JMdict/Tatoeba) fuera de cualquier <li>.
        # Sin el fix: ['washing', 'laundry',
        #   'See also命の洗濯casting off the drudgery...',
        #   'relaxation', 'rejuvenation', 'melting away (...)',
        #   'forms洗濯せんたくせんだく']
        # Con el fix, el xref y el <li> 'forms' desaparecen (attribution no
        # está dentro de ningún <li> así que ya no ensucia ninguna glosa,
        # pero se descarta igual por si en otras entradas sí lo está).
        glossary = [{
            "type": "structured-content",
            "content": [
                {"tag": "ul", "data": {"content": "sense-groups"}, "content": [
                    {"tag": "li", "data": {"content": "sense-group"}, "content": [
                        {"tag": "span", "data": {"content": "part-of-speech-info"},
                         "content": "noun"},
                        {"tag": "div", "data": {"content": "sense"}, "content": [
                            {"tag": "ul", "data": {"content": "glossary"}, "content": [
                                {"tag": "li", "content": "washing"},
                                {"tag": "li", "content": "laundry"},
                            ]},
                        ]},
                    ]},
                    {"tag": "li", "data": {"content": "sense-group"}, "content": [
                        {"tag": "div", "data": {"content": "sense"}, "content": [
                            {"tag": "ul", "data": {"content": "glossary"}, "content": [
                                {"tag": "li", "content": "relaxation"},
                                {"tag": "li", "content": "rejuvenation"},
                            ]},
                            {"tag": "div", "data": {"content": "extra-info"}, "content": [
                                {"tag": "div", "data": {"content": "xref"}, "content": [
                                    {"tag": "div", "data": {"content": "xref-content"}, "content": [
                                        {"tag": "span", "data": {"content": "reference-label"},
                                         "content": "See also"},
                                        {"tag": "a", "content": [
                                            {"tag": "ruby", "content": [
                                                "命", {"tag": "rt", "content": "いのち"}]},
                                            "の",
                                            {"tag": "ruby", "content": [
                                                "洗", {"tag": "rt", "content": "せん"}]},
                                            {"tag": "ruby", "content": [
                                                "濯", {"tag": "rt", "content": "たく"}]},
                                        ]},
                                    ]},
                                    {"tag": "div", "data": {"content": "xref-glossary"},
                                     "content": "casting off the drudgery of everyday life"},
                                ]},
                            ]},
                        ]},
                    ]},
                    {"tag": "li", "data": {"content": "forms"}, "content": [
                        {"tag": "span", "data": {"content": "forms-label"},
                         "content": "forms"},
                        {"tag": "table", "content": [
                            {"tag": "tr", "content": [
                                {"tag": "th", "content": "せんたく"},
                                {"tag": "td", "content": "洗濯"},
                            ]},
                        ]},
                    ]},
                ]},
                {"tag": "div", "data": {"content": "attribution"}, "content": [
                    {"tag": "a", "content": "JMdict"},
                ]},
            ],
        }]
        self.assertEqual(
            jitendex.extraer_glosas(glossary),
            ['washing', 'laundry', 'relaxation', 'rejuvenation'])

    def test_mantiene_info_gloss_pero_descarta_su_rotulo_y_sense_note(self):
        # Reproduce el caso real de のか (partícula, sin entrada en JMdict con
        # glosario plano): cada sentido NO trae un <ul data-content=glossary>
        # -toda la definición vive en un bloque 'info-gloss' ("Explanation: ...")-
        # y además trae un 'sense-note' ("Note: sentence ending particle").
        # info-gloss NO puede descartarse en bloque: para ~78 sentidos reales
        # (p.ej. ヾ, ゝ, 々, のか) es la ÚNICA fuente de definición y
        # descartarlo entero dejaría la glosa vacía. Sí hay que descartar su
        # rótulo fijo ('info-gloss-label' = "Explanation") para no concatenar
        # "Explanationendorsing and questioning...". sense-note nunca aparece
        # solo (siempre junto a glossary o info-gloss reales, verificado
        # contra las ~350k fuentes reales) así que se descarta entero sin
        # riesgo de vaciar la glosa.
        info_gloss = {"tag": "div", "data": {"content": "info-gloss"}, "content": [
            {"tag": "div", "data": {"content": "info-gloss-label"},
             "content": "Explanation"},
            {"tag": "div", "data": {"content": "info-gloss-content"},
             "content": "endorsing and questioning the preceding statement"},
        ]}
        sense_note = {"tag": "div", "data": {"content": "sense-note"}, "content": [
            {"tag": "div", "data": {"content": "sense-note-label"}, "content": "Note"},
            {"tag": "div", "data": {"content": "sense-note-content"},
             "content": "sentence ending particle"},
        ]}
        sense_li = {"tag": "li", "data": {"content": "sense"}, "content": {
            "tag": "div", "data": {"content": "extra-info"}, "content": [
                info_gloss,
                sense_note,
            ],
        }}
        sense_group_li = {"tag": "li", "data": {"content": "sense-group"}, "content": [
            {"tag": "span", "data": {"content": "part-of-speech-info"},
             "content": "particle"},
            {"tag": "ol", "content": [sense_li]},
        ]}
        glossary = [{
            "type": "structured-content",
            "content": [
                {"tag": "ul", "data": {"content": "sense-groups"},
                 "content": sense_group_li},
            ],
        }]
        self.assertEqual(
            jitendex.extraer_glosas(glossary),
            ['endorsing and questioning the preceding statement'])

    def test_descarta_lang_source_misc_dialect_y_graphic(self):
        # lang-source (etimología, p.ej. "Language of Origin: Portuguese:
        # espada") no es marca hipotetizada por el plan original: apareció en
        # la investigación empírica de esta task (10496 hits en fuentes
        # reales) y nunca es la única fuente de una glosa (siempre acompaña a
        # glossary o info-gloss reales). misc-info/dialect-info son tags de
        # una palabra (como field-info, ya descartado) y graphic envuelve una
        # imagen con su crédito.
        glossary = [{
            "type": "structured-content",
            "content": [
                {"tag": "ol", "content": [
                    {"tag": "li", "content": [
                        {"tag": "span", "data": {"content": "misc-info"}, "content": "abbr."},
                        {"tag": "span", "data": {"content": "dialect-info"}, "content": "Kansai"},
                        {"tag": "ul", "data": {"content": "glossary"},
                         "content": {"tag": "li", "content": "espada (sword)"}},
                        {"tag": "div", "data": {"content": "extra-info"}, "content": [
                            {"tag": "div", "data": {"content": "lang-source"}, "content": [
                                {"tag": "div", "data": {"content": "lang-source-label"},
                                 "content": "Language of Origin"},
                                {"tag": "div", "data": {"content": "lang-source-content"},
                                 "content": "Portuguese: \"espada\""},
                            ]},
                            {"tag": "div", "data": {"content": "graphic"}, "content": [
                                {"tag": "img", "path": "jitendex/graphics/x.avif"},
                                {"tag": "div", "data": {"content": "graphic-attribution"},
                                 "content": [{"tag": "a", "content": "Wikimedia Commons"}]},
                            ]},
                        ]},
                    ]},
                ]},
            ],
        }]
        self.assertEqual(jitendex.extraer_glosas(glossary), ['espada (sword)'])

    def test_descarta_contenedor_descartable_que_envuelve_li_anidado(self):
        # Hardening: si un nodo descartable (p.ej. xref-glossary) envuelve un
        # <li> anidado, ese li completo debe descartarse. Sin el fix, _buscar_li
        # solo chequea _es_descartable en la rama 'tag == li', así que al
        # recursar por el else-branch (nodo no es li), entra en el contenedor
        # descartable sin chequear su marca, y extrae el <li> hijo como glosa.
        #
        # Scenario: un sense-group trae un xref a otra palabra, cuya definición
        # (xref-glossary) envuelve un <li>. Esperado: ese li se descarta
        # completamente, no se filtra como glosa legítima.
        glossary = [{
            "type": "structured-content",
            "content": [
                {"tag": "ol", "content": [
                    {"tag": "li", "content": [
                        {"tag": "ul", "data": {"content": "glossary"}, "content": [
                            {"tag": "li", "content": "main gloss"},
                        ]},
                        # Contenedor descartable que envuelve un <li> hijo
                        {"tag": "div", "data": {"content": "xref-glossary"}, "content": [
                            {"tag": "li", "content": "leaked gloss (should be discarded)"},
                        ]},
                    ]},
                ]},
            ],
        }]
        # Sin fix: ['main gloss', 'leaked gloss (should be discarded)']
        # Con fix: ['main gloss']
        self.assertEqual(jitendex.extraer_glosas(glossary), ['main gloss'])


if __name__ == '__main__':
    unittest.main()
