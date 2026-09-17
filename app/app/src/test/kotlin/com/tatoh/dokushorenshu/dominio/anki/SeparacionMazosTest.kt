package com.tatoh.dokushorenshu.dominio.anki

import com.tatoh.dokushorenshu.datos.DiccionarioFake
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.Oracion
import com.tatoh.dokushorenshu.datos.OracionEjemplo
import com.tatoh.dokushorenshu.datos.Parrafo
import com.tatoh.dokushorenshu.datos.Recorte
import com.tatoh.dokushorenshu.datos.RecortesRepo
import com.tatoh.dokushorenshu.datos.progreso.PalabraTocada
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDaoFake
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** La separación entre el mazo Words (historias) y el mazo Scans (recortes) es una
 *  convención de STRING: el `idHistoria` de una palabra tocada en un recorte arranca
 *  con `recorte:`. No hay tipo, enum ni columna que lo respalde, así que si el filtro
 *  se rompe no hay error, ni crash, ni log — el vocabulario de los recortes
 *  simplemente aparece en el mazo de las historias.
 *
 *  Por eso los literales de este archivo están escritos a mano y NO derivados de la
 *  misma expresión que usa producción (`"recorte:" + id`): un test que arma su
 *  expectativa igual que el código sigue pasando cuando los dos cambian juntos.
 *  Si alguien renombra el prefijo, estos tests tienen que ponerse en rojo. */
class SeparacionMazosTest {

    private fun dirTemp(prefijo: String): File =
        File.createTempFile(prefijo, "").let { it.delete(); it.mkdirs(); it }

    /** Sin historias en disco: a este test no le importan las oraciones de ejemplo,
     *  solo qué términos entran en cada mazo. */
    private fun historiasRepoVacio() = HistoriasRepo(
        leerAsset = { null },
        listarAssetsHistorias = { emptyList() },
        dirDescargas = dirTemp("desc"),
        dirImportadas = dirTemp("imp"),
    )

    // log inyectado: android.util.Log no existe en los tests de JVM plano.
    private fun recortesRepo() = RecortesRepo(dirTemp("rec"), log = { _, _ -> })

    /** Pares (idHistoria, termino) tal como quedan en `palabras_tocadas`. Los ids de
     *  recorte se escriben con el literal completo en cada test, a propósito. */
    private suspend fun armadorCon(
        palabrasEnHistoria: List<Pair<String, String>> = emptyList(),
        palabrasEnRecorte: List<Pair<String, String>> = emptyList(),
        recortesRepo: RecortesRepo = recortesRepo(),
        diccionario: DiccionarioFake = DiccionarioFake(),
    ): ArmadorMazos {
        val dao = ProgresoDaoFake()
        var t = 0L
        for ((id, termino) in palabrasEnHistoria + palabrasEnRecorte) {
            dao.registrarPalabra(PalabraTocada(id, termino, timestamp = ++t))
        }
        return ArmadorMazos(dao, diccionario, historiasRepoVacio(), recortesRepo)
    }

    @Test
    fun `el mazo Words excluye las palabras de recortes`() = runTest {
        val armador = armadorCon(
            palabrasEnHistoria = listOf("momotaro" to "川上"),
            palabrasEnRecorte = listOf("recorte:100" to "稲妻"),
        )
        val terminos = armador.armarWords().map { it.palabra }
        assertTrue("川上 se tocó leyendo una historia y tiene que estar en Words", terminos.contains("川上"))
        assertFalse("稲妻 salió de un recorte y no va al mazo Words", terminos.contains("稲妻"))
    }

    @Test
    fun `el mazo Scans excluye las palabras de historias`() = runTest {
        val armador = armadorCon(
            palabrasEnHistoria = listOf("momotaro" to "川上"),
            palabrasEnRecorte = listOf("recorte:100" to "稲妻"),
        )
        val terminos = armador.armarScans().map { it.palabra }
        assertTrue("稲妻 se tocó en un recorte y tiene que estar en Scans", terminos.contains("稲妻"))
        assertFalse("川上 salió de una historia y no va al mazo Scans", terminos.contains("川上"))
    }

    @Test
    fun `sin recortes tocados el mazo Scans queda vacio`() = runTest {
        val armador = armadorCon(palabrasEnHistoria = listOf("momotaro" to "川上"))
        assertEquals(emptyList<String>(), armador.armarScans().map { it.palabra })
    }

    @Test
    fun `sin historias tocadas el mazo Words queda vacio`() = runTest {
        val armador = armadorCon(palabrasEnRecorte = listOf("recorte:100" to "稲妻"))
        assertEquals(emptyList<String>(), armador.armarWords().map { it.palabra })
    }

    /** El separador es `:` y no `-` justamente por esto: `generarId()` sanea los
     *  títulos a `[a-z0-9_]`, así que un id de historia NUNCA puede traer dos puntos,
     *  pero sí guiones. Una historia importada que se llame "recorte-123" es una
     *  historia, y su vocabulario va al mazo Words. */
    @Test
    fun `una historia cuyo id empieza con recorte guion va al mazo Words`() = runTest {
        val armador = armadorCon(palabrasEnHistoria = listOf("recorte-123" to "川上"))
        assertEquals(listOf("川上"), armador.armarWords().map { it.palabra })
        assertEquals(emptyList<String>(), armador.armarScans().map { it.palabra })
    }

    /** Una palabra tocada en los DOS lados sale en los dos mazos, pero con guid
     *  distinto: Anki matchea las notas por guid globalmente, así que con el mismo
     *  guid el import de un mazo pisaría la nota del otro y la palabra terminaría en
     *  un solo mazo — la misma contaminación, por otra puerta. */
    @Test
    fun `la misma palabra tocada en historia y en recorte tiene guid distinto en cada mazo`() = runTest {
        val armador = armadorCon(
            palabrasEnHistoria = listOf("momotaro" to "稲妻"),
            palabrasEnRecorte = listOf("recorte:100" to "稲妻"),
        )
        val deWords = armador.armarWords().single()
        val deScans = armador.armarScans().single()
        assertEquals("words:稲妻", deWords.claveGuid)
        assertEquals("scan:稲妻", deScans.claveGuid)
    }

    // --- Oraciones de ejemplo del mazo Scans ---

    private fun recorteCon(texto: String, id: String = "100"): RecortesRepo {
        val repo = recortesRepo()
        repo.guardar(
            Recorte(
                id = id,
                texto = texto,
                parrafos = listOf(Parrafo(listOf(Oracion(texto, emptyList())))),
                timestamp = 1L,
                tieneImagen = false,
            ),
        )
        return repo
    }

    @Test
    fun `la oracion de ejemplo de una nota Scans sale del propio recorte`() = runTest {
        val armador = armadorCon(
            palabrasEnRecorte = listOf("recorte:100" to "稲妻"),
            recortesRepo = recorteCon("稲妻が空を裂いた。"),
        )
        val nota = armador.armarScans().single()
        assertEquals(1, nota.oraciones.size)
        assertTrue("la oración tiene que ser la del recorte: ${nota.oraciones[0]}",
            nota.oraciones[0].contains("空を裂いた"))
    }

    /** Mismo fallback que el mazo Words: si el texto propio no aporta ninguna oración
     *  con el término (p.ej. la palabra se tocó en un recorte que después se editó),
     *  se rellena con Tatoeba en vez de dejar la nota sin ejemplos. */
    @Test
    fun `una nota Scans sin oracion en el recorte cae a Tatoeba`() = runTest {
        val diccionario = DiccionarioFake().apply {
            ejemplosPalabra["稲妻"] = listOf(OracionEjemplo("稲妻が光る。", "Lightning flashes."))
        }
        val armador = armadorCon(
            palabrasEnRecorte = listOf("recorte:100" to "稲妻"),
            recortesRepo = recorteCon("ここには何もない。"),
            diccionario = diccionario,
        )
        val nota = armador.armarScans().single()
        assertEquals(1, nota.oraciones.size)
        assertTrue("debería venir de Tatoeba, con traducción: ${nota.oraciones[0]}",
            nota.oraciones[0].contains("Lightning flashes."))
    }
}
