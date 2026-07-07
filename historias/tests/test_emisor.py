import json
import os
import shutil
import tempfile
import unittest

from src import emisor


def _historia():
    return emisor.construir_historia(
        id_='momotaro', titulo='桃太郎', autor='楠山正雄',
        fuente='aozora:18376', licencia='dominio público',
        dificultad='facil',
        parrafos=[[('むかし、むかし。', []),
                   ('しば刈りに。', [[2, 3, 'か']])]])


class TestEmisor(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)

    def test_estructura_del_spec(self):
        historia = _historia()
        self.assertEqual(historia['id'], 'momotaro')
        self.assertEqual(historia['version'], 1)
        oracion = historia['parrafos'][0]['oraciones'][1]
        self.assertEqual(oracion['texto'], 'しば刈りに。')
        self.assertEqual(oracion['furigana'], [[2, 3, 'か']])
        self.assertIsNone(oracion['traduccion'])

    def test_emitir_escribe_historia_y_catalogo(self):
        emisor.emitir([_historia()], self.tmp)
        ruta = os.path.join(self.tmp, 'historias', 'momotaro.json')
        with open(os.path.join(self.tmp, 'catalogo.json'),
                  encoding='utf-8') as f:
            catalogo = json.load(f)
        self.assertEqual(catalogo['version'], emisor.VERSION_CATALOGO)
        entrada = catalogo['historias'][0]
        self.assertEqual(entrada['id'], 'momotaro')
        self.assertEqual(entrada['titulo'], '桃太郎')
        self.assertEqual(entrada['dificultad'], 'facil')
        self.assertEqual(entrada['version'], 1)
        self.assertEqual(entrada['tamaño'], os.path.getsize(ruta))

    def test_sin_archivos_temporales_residuales(self):
        emisor.emitir([_historia()], self.tmp)
        for raiz, _, archivos in os.walk(self.tmp):
            for nombre in archivos:
                self.assertFalse(nombre.endswith('.tmp'),
                                 f'residuo: {raiz}/{nombre}')

    def test_json_sin_escapes_ascii(self):
        emisor.emitir([_historia()], self.tmp)
        ruta = os.path.join(self.tmp, 'historias', 'momotaro.json')
        with open(ruta, encoding='utf-8') as f:
            self.assertIn('桃太郎', f.read())  # ensure_ascii=False


if __name__ == '__main__':
    unittest.main()
