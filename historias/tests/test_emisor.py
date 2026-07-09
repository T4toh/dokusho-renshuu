import json
import os
import shutil
import tempfile
import unittest

from src import emisor

_METADATA = {'momotaro': {'titulo_lectura': 'ももたろう',
                          'titulo_en': 'Peach Boy'}}


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
        self.assertEqual(historia['version'], emisor.VERSION_HISTORIA)
        self.assertEqual(historia['version'], 2)
        oracion = historia['parrafos'][0]['oraciones'][1]
        self.assertEqual(oracion['texto'], 'しば刈りに。')
        self.assertEqual(oracion['furigana'], [[2, 3, 'か']])
        self.assertIsNone(oracion['traduccion'])

    def test_kanjis_unicos_cuenta_unicos_del_texto_completo(self):
        # 'む' 'か' 'し' 'ば' '刈' 'り' 'に' → un solo kanji distinto: 刈
        historia = _historia()
        self.assertEqual(historia['kanjis_unicos'], 1)

    def test_oraciones_cuenta_total(self):
        historia = _historia()
        self.assertEqual(historia['oraciones'], 2)

    def test_emitir_escribe_historia_y_catalogo(self):
        emisor.emitir([_historia()], self.tmp, _METADATA)
        ruta = os.path.join(self.tmp, 'historias', 'momotaro.json')
        with open(os.path.join(self.tmp, 'catalogo.json'),
                  encoding='utf-8') as f:
            catalogo = json.load(f)
        self.assertEqual(catalogo['version'], 2)
        entrada = catalogo['historias'][0]
        self.assertEqual(entrada['id'], 'momotaro')
        self.assertEqual(entrada['titulo'], '桃太郎')
        self.assertEqual(entrada['titulo_lectura'], 'ももたろう')
        self.assertEqual(entrada['titulo_en'], 'Peach Boy')
        self.assertEqual(entrada['dificultad'], 'facil')
        self.assertEqual(entrada['version'], 2)
        self.assertEqual(entrada['kanjis_unicos'], 1)
        self.assertEqual(entrada['oraciones'], 2)
        self.assertEqual(entrada['tamaño'], os.path.getsize(ruta))

    def test_titulo_en_nulo_permitido(self):
        metadata = {'momotaro': {'titulo_lectura': 'ももたろう',
                                 'titulo_en': None}}
        emisor.emitir([_historia()], self.tmp, metadata)
        with open(os.path.join(self.tmp, 'catalogo.json'),
                  encoding='utf-8') as f:
            catalogo = json.load(f)
        self.assertIsNone(catalogo['historias'][0]['titulo_en'])

    def test_json_de_historia_no_incluye_campos_solo_de_catalogo(self):
        # el JSON de historia no cambia de schema respecto a v1: los campos
        # nuevos viven solo en catalogo.json
        emisor.emitir([_historia()], self.tmp, _METADATA)
        ruta = os.path.join(self.tmp, 'historias', 'momotaro.json')
        with open(ruta, encoding='utf-8') as f:
            historia_en_disco = json.load(f)
        self.assertNotIn('titulo_lectura', historia_en_disco)
        self.assertNotIn('titulo_en', historia_en_disco)
        self.assertNotIn('kanjis_unicos', historia_en_disco)
        self.assertNotIn('oraciones', historia_en_disco)

    def test_sin_archivos_temporales_residuales(self):
        emisor.emitir([_historia()], self.tmp, _METADATA)
        for raiz, _, archivos in os.walk(self.tmp):
            for nombre in archivos:
                self.assertFalse(nombre.endswith('.tmp'),
                                 f'residuo: {raiz}/{nombre}')

    def test_json_sin_escapes_ascii(self):
        emisor.emitir([_historia()], self.tmp, _METADATA)
        ruta = os.path.join(self.tmp, 'historias', 'momotaro.json')
        with open(ruta, encoding='utf-8') as f:
            self.assertIn('桃太郎', f.read())  # ensure_ascii=False


if __name__ == '__main__':
    unittest.main()
