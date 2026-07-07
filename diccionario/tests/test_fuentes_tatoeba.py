import os
import shutil
import tempfile
import unittest

import fuentes_tatoeba


class TestFuentesTatoeba(unittest.TestCase):
    """Prueba el join jpn_sentences + eng_sentences + jpn-eng_links ->
    pares_jpn_eng.tsv, con fixtures inline mínimas (no requiere descargas)."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)
        self._escribir('jpn_sentences.tsv', [
            '1297\tjpn\tこれは物語です。',
            '4702\tjpn\t走れ。',
        ])
        self._escribir('eng_sentences.tsv', [
            '4724\teng\tThis is a tale.',
            '1276\teng\tRun.',
        ])
        self._escribir('jpn-eng_links.tsv', [
            '1297\t4724',
            '4702\t1276',
            '4702\t9999',   # id_eng inexistente: se descarta
            '8888\t1276',   # id_jpn inexistente: se descarta
        ])

    def _escribir(self, nombre, lineas):
        with open(os.path.join(self.dir, nombre), 'w', encoding='utf-8') as f:
            f.write('\n'.join(lineas) + '\n')

    def test_genera_pares_con_las_4_columnas_esperadas(self):
        n = fuentes_tatoeba.generar_pares(self.dir)
        self.assertEqual(n, 2)
        with open(os.path.join(self.dir, 'pares_jpn_eng.tsv'),
                  encoding='utf-8') as f:
            lineas = f.read().splitlines()
        self.assertEqual(lineas[0].split('\t'),
                         ['1297', 'これは物語です。', '4724', 'This is a tale.'])
        self.assertEqual(lineas[1].split('\t'),
                         ['4702', '走れ。', '1276', 'Run.'])

    def test_salida_es_consumible_por_el_parser_de_tatoeba(self):
        fuentes_tatoeba.generar_pares(self.dir)
        from src import tatoeba
        oraciones = tatoeba.parsear_pares(
            os.path.join(self.dir, 'pares_jpn_eng.tsv'))
        self.assertEqual(len(oraciones), 2)


if __name__ == '__main__':
    unittest.main()
