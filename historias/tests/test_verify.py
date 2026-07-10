import json
import os
import shutil
import tempfile
import unittest

import verify_catalogo
from src import emisor

_METADATA = {'momotaro': {'titulo_lectura': 'ももたろう',
                          'titulo_en': 'Peach Boy'}}


def _historia_valida():
    return emisor.construir_historia(
        id_='momotaro', titulo='桃太郎', autor='楠山正雄',
        fuente='aozora:18376', licencia='dominio público',
        dificultad='facil',
        parrafos=[[('むかし、むかし。', []),
                   ('しば刈りに。', [[2, 3, 'か']])]])


class TestVerify(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)
        emisor.emitir([_historia_valida()], self.tmp, _METADATA)

    def _corromper_historia(self, mutador):
        """Reescribe momotaro.json mutado (además desactualiza el tamaño)."""
        ruta = os.path.join(self.tmp, 'historias', 'momotaro.json')
        with open(ruta, encoding='utf-8') as f:
            historia = json.load(f)
        mutador(historia)
        with open(ruta, 'w', encoding='utf-8') as f:
            json.dump(historia, f, ensure_ascii=False)

    def _corromper_catalogo(self, mutador):
        ruta = os.path.join(self.tmp, 'catalogo.json')
        with open(ruta, encoding='utf-8') as f:
            catalogo = json.load(f)
        mutador(catalogo)
        with open(ruta, 'w', encoding='utf-8') as f:
            json.dump(catalogo, f, ensure_ascii=False)

    def test_catalogo_valido_sin_errores(self):
        self.assertEqual(verify_catalogo.verificar(self.tmp), [])

    def test_detecta_marcado_residual(self):
        self._corromper_historia(
            lambda h: h['parrafos'][0]['oraciones'][0]
            .update(texto='むかし《むかし》。'))
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('residual' in e for e in errores))

    def test_detecta_furigana_fuera_de_rango(self):
        self._corromper_historia(
            lambda h: h['parrafos'][0]['oraciones'][1]
            .update(furigana=[[2, 99, 'か']]))
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('furigana' in e for e in errores))

    def test_detecta_tamano_desactualizado(self):
        self._corromper_historia(lambda h: None)  # rewrite compacto cambia bytes
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('tamaño' in e for e in errores))

    def test_furigana_malformada_se_reporta_sin_excepcion(self):
        def mutar(h):
            h['parrafos'][0]['oraciones'][1]['furigana'] = [[2, 99]]
        self._corromper_historia(mutar)
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('furigana' in e for e in errores))

    def test_furigana_tipos_invalidos_se_reporta_sin_excepcion(self):
        def mutar(h):
            h['parrafos'][0]['oraciones'][1]['furigana'] = [['2', 3, 'か']]
        self._corromper_historia(mutar)
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('furigana' in e for e in errores))

    def test_detecta_historia_faltante(self):
        os.unlink(os.path.join(self.tmp, 'historias', 'momotaro.json'))
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('momotaro' in e for e in errores))

    def test_detecta_version_de_catalogo_incorrecta(self):
        self._corromper_catalogo(lambda c: c.update(version=1))
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('version' in e for e in errores))

    def test_detecta_campo_v2_faltante_en_entrada_de_catalogo(self):
        def mutar(c):
            del c['historias'][0]['titulo_lectura']
        self._corromper_catalogo(mutar)
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('titulo_lectura' in e or 'faltan' in e
                            for e in errores))

    def test_titulo_en_nulo_no_es_error(self):
        self._corromper_catalogo(
            lambda c: c['historias'][0].update(titulo_en=None))
        self.assertEqual(verify_catalogo.verificar(self.tmp), [])

    def test_detecta_furigana_solapada_o_desordenada(self):
        def mutar(h):
            # しば刈りに。 (6 caracteres) — ambos spans son individualmente
            # válidos (0<=inicio<fin<=6) pero se solapan entre sí.
            h['parrafos'][0]['oraciones'][1]['furigana'] = (
                [[0, 3, 'a'], [2, 5, 'b']])
        self._corromper_historia(mutar)
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('solapad' in e for e in errores))

    def test_detecta_kanjis_unicos_de_tipo_invalido(self):
        self._corromper_catalogo(
            lambda c: c['historias'][0].update(kanjis_unicos='124'))
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('kanjis_unicos' in e for e in errores))


if __name__ == '__main__':
    unittest.main()
