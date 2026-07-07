import json
import os
import shutil
import tempfile
import unittest

import verify_catalogo
from src import emisor


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
        emisor.emitir([_historia_valida()], self.tmp)

    def _corromper(self, mutador):
        """Reescribe momotaro.json mutado (además desactualiza el tamaño)."""
        ruta = os.path.join(self.tmp, 'historias', 'momotaro.json')
        with open(ruta, encoding='utf-8') as f:
            historia = json.load(f)
        mutador(historia)
        with open(ruta, 'w', encoding='utf-8') as f:
            json.dump(historia, f, ensure_ascii=False)

    def test_catalogo_valido_sin_errores(self):
        self.assertEqual(verify_catalogo.verificar(self.tmp), [])

    def test_detecta_marcado_residual(self):
        self._corromper(lambda h: h['parrafos'][0]['oraciones'][0]
                        .update(texto='むかし《むかし》。'))
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('residual' in e for e in errores))

    def test_detecta_furigana_fuera_de_rango(self):
        self._corromper(lambda h: h['parrafos'][0]['oraciones'][1]
                        .update(furigana=[[2, 99, 'か']]))
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('furigana' in e for e in errores))

    def test_detecta_tamano_desactualizado(self):
        self._corromper(lambda h: None)  # reescritura compacta cambia bytes
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('tamaño' in e for e in errores))

    def test_furigana_malformada_se_reporta_sin_excepcion(self):
        def mutar(h):
            h['parrafos'][0]['oraciones'][1]['furigana'] = [[2, 99]]
        self._corromper(mutar)
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('furigana' in e for e in errores))

    def test_furigana_tipos_invalidos_se_reporta_sin_excepcion(self):
        def mutar(h):
            h['parrafos'][0]['oraciones'][1]['furigana'] = [['2', 3, 'か']]
        self._corromper(mutar)
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('furigana' in e for e in errores))

    def test_detecta_historia_faltante(self):
        os.unlink(os.path.join(self.tmp, 'historias', 'momotaro.json'))
        errores = verify_catalogo.verificar(self.tmp)
        self.assertTrue(any('momotaro' in e for e in errores))


if __name__ == '__main__':
    unittest.main()
