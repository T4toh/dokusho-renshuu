import json
import os
import shutil
import tempfile
import unittest

import pipeline
import verify_catalogo
from src import emisor

FIXTURE = os.path.join(os.path.dirname(__file__), 'fixtures',
                       'fragmento_aozora.txt')
FIXTURE_CON_ENCABEZADO = os.path.join(
    os.path.dirname(__file__), 'fixtures',
    'fragmento_aozora_con_encabezado.txt')


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
        self.assertEqual(historia['version'], 2)
        self.assertIn(historia['dificultad'], {'facil', 'media', 'dificil'})
        self.assertEqual(len(historia['parrafos']), 2)
        textos = [o['texto'] for p in historia['parrafos']
                  for o in p['oraciones']]
        self.assertNotIn('一', textos)
        oraciones_p2 = historia['parrafos'][1]['oraciones']
        self.assertEqual(len(oraciones_p2), 1)
        dir_catalogo = os.path.join(self.tmp, 'catalogo')
        metadata = {'momotaro': {'titulo_lectura': 'ももたろう',
                                 'titulo_en': 'Peach Boy'}}
        emisor.emitir([historia], dir_catalogo, metadata)
        self.assertEqual(verify_catalogo.verificar(dir_catalogo), [])

    def test_fuente_inexistente_falla_ruidoso(self):
        with self.assertRaises(FileNotFoundError):
            pipeline.procesar_obra(
                os.path.join(self.tmp, 'nada.txt'),
                {'id': 'x', 'fuente': 'aozora:0'})

    def test_rellena_furigana_faltante(self):
        historia = pipeline.procesar_obra(
            self.ruta_txt, {'id': 'momotaro', 'fuente': 'aozora:18376'})
        oracion = historia['parrafos'][0]['oraciones'][1]
        self.assertIn('山へ', oracion['texto'])
        lecturas = {(t[0], t[1]): t[2] for t in oracion['furigana']}
        idx_yama = oracion['texto'].index('山')
        idx_kawa = oracion['texto'].index('川')
        idx_sen = oracion['texto'].index('洗濯')
        self.assertEqual(lecturas.get((idx_yama, idx_yama + 1)), 'やま')
        self.assertEqual(lecturas.get((idx_kawa, idx_kawa + 1)), 'かわ')
        # el ruby Aozora original sigue intacto
        self.assertEqual(lecturas.get((idx_sen, idx_sen + 2)), 'せんたく')

    def test_procesar_obra_con_traducciones(self):
        """Carga traducciones/<id>.json si existe y las mergea al emitir."""
        # Primero sin traducciones para conocer las oraciones emitidas
        base = pipeline.procesar_obra(
            self.ruta_txt, {'id': 'momotaro', 'fuente': 'aozora:18376'})
        planas = [o['texto'] for p in base['parrafos'] for o in p['oraciones']]
        dir_trad = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, dir_trad)
        with open(os.path.join(dir_trad, 'momotaro.json'),
                  'w', encoding='utf-8') as f:
            json.dump({'id': 'momotaro',
                       'oraciones': [{'texto': t, 'traduccion': f'lit {i}'}
                                     for i, t in enumerate(planas)]},
                      f, ensure_ascii=False)
        con_trad = pipeline.procesar_obra(
            self.ruta_txt, {'id': 'momotaro', 'fuente': 'aozora:18376'},
            dir_traducciones=dir_trad)
        emitidas = [o['traduccion']
                    for p in con_trad['parrafos'] for o in p['oraciones']]
        self.assertEqual([f'lit {i}' for i in range(len(planas))], emitidas)

    def test_procesar_obra_sin_archivo_de_traducciones_emite_null(self):
        """Sin archivo de traducciones, traduccion queda None."""
        dir_trad = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, dir_trad)
        historia = pipeline.procesar_obra(
            self.ruta_txt, {'id': 'momotaro', 'fuente': 'aozora:18376'},
            dir_traducciones=dir_trad)
        self.assertTrue(all(o['traduccion'] is None
                            for p in historia['parrafos']
                            for o in p['oraciones']))


class TestProcesarObraDescartaEncabezados(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)
        with open(FIXTURE_CON_ENCABEZADO, encoding='utf-8') as f:
            contenido = f.read()
        self.ruta_txt = os.path.join(self.tmp, 'momotaro.txt')
        with open(self.ruta_txt, 'w', encoding='cp932') as f:
            f.write(contenido)

    def test_encabezado_de_seccion_no_genera_parrafo(self):
        # el fixture agrega una línea "一" (encabezado de sección Aozora)
        # antes del párrafo real; no debe sobrevivir a procesar_obra
        historia = pipeline.procesar_obra(
            self.ruta_txt, {'id': 'momotaro', 'fuente': 'aozora:18376'})
        self.assertEqual(len(historia['parrafos']), 2)
        textos = [o['texto'] for p in historia['parrafos']
                  for o in p['oraciones']]
        self.assertNotIn('一', textos)
        dir_catalogo = os.path.join(self.tmp, 'catalogo')
        metadata = {'momotaro': {'titulo_lectura': 'ももたろう',
                                 'titulo_en': 'Peach Boy'}}
        emisor.emitir([historia], dir_catalogo, metadata)
        self.assertEqual(verify_catalogo.verificar(dir_catalogo), [])


if __name__ == '__main__':
    unittest.main()
