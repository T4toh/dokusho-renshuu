import os
import shutil
import tempfile
import unittest

import pipeline
import verify_catalogo
from src import emisor

FIXTURE = os.path.join(os.path.dirname(__file__), 'fixtures',
                       'fragmento_aozora.txt')


class TestLeerFuente(unittest.TestCase):
    def test_decodifica_shift_jis(self):
        with open(FIXTURE, encoding='utf-8') as f:
            contenido = f.read()
        with tempfile.NamedTemporaryFile('w', suffix='.txt', delete=False,
                                         encoding='cp932') as f:
            f.write(contenido)
            ruta = f.name
        self.addCleanup(os.unlink, ruta)
        self.assertEqual(pipeline.leer_fuente(ruta), contenido)


class TestProcesarObra(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)
        with open(FIXTURE, encoding='utf-8') as f:
            contenido = f.read()
        self.ruta_txt = os.path.join(self.tmp, 'momotaro.txt')
        with open(self.ruta_txt, 'w', encoding='cp932') as f:
            f.write(contenido)

    def test_end_to_end_pasa_verify(self):
        historia = pipeline.procesar_obra(
            self.ruta_txt, {'id': 'momotaro', 'fuente': 'aozora:18376'})
        self.assertEqual(historia['titulo'], '桃太郎')
        self.assertEqual(historia['autor'], '楠山正雄')
        self.assertIn(historia['dificultad'], {'facil', 'media', 'dificil'})
        # el diálogo del fixture queda como una sola oración
        oraciones_p2 = historia['parrafos'][1]['oraciones']
        self.assertEqual(len(oraciones_p2), 1)
        dir_catalogo = os.path.join(self.tmp, 'catalogo')
        emisor.emitir([historia], dir_catalogo)
        self.assertEqual(verify_catalogo.verificar(dir_catalogo), [])

    def test_fuente_inexistente_falla_ruidoso(self):
        with self.assertRaises(FileNotFoundError):
            pipeline.procesar_obra(
                os.path.join(self.tmp, 'nada.txt'),
                {'id': 'x', 'fuente': 'aozora:0'})


if __name__ == '__main__':
    unittest.main()
