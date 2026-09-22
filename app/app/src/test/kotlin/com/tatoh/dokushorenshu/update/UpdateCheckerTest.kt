package com.tatoh.dokushorenshu.update

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tatoh.dokushorenshu.datos.progreso.PrefsRepo
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {
    private val prefs = PrefsRepo(
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgresoDb::class.java)
            .allowMainThreadQueries().build().dao()
    )

    private val beta5 = """[{"tag_name":"v0.1.0-beta.5","draft":false,"assets":[{"name":"a.apk",
        "browser_download_url":"https://github.com/T4toh/dokusho-renshuu/releases/download/v0.1.0-beta.5/a.apk",
        "digest":"sha256:ab"}]}]"""

    private fun checker(
        instalada: String = "0.1.0-beta.4",
        ahora: Long = 1_000_000L,
        fetch: suspend (String) -> String = { beta5 },
    ) = UpdateChecker(instalada, prefs, fetch, { ahora })

    @Test
    fun `hay version nueva`() = runTest {
        val info = checker().chequear()
        assertNotNull(info)
        assertEquals("0.1.0-beta.5", info!!.version.toString())
    }

    private var urlPedida: String? = null
    private val fetchQueAnota: suspend (String) -> String = { urlPedida = it; beta5 }

    @Test
    fun `pide la lista de releases, no latest`() = runTest {
        checker(fetch = fetchQueAnota).chequear()
        assertEquals("https://api.github.com/repos/T4toh/dokusho-renshuu/releases?per_page=20", urlPedida)
    }

    @Test
    fun `misma version o menor no avisa`() = runTest {
        assertNull(checker(instalada = "0.1.0-beta.5").chequear())
        prefs.setUltimoChequeoUpdate(0)
        assertNull(checker(instalada = "0.1.0").chequear()) // 0.1.0 > 0.1.0-beta.5
    }

    @Test
    fun `gate de 24 horas`() = runTest {
        assertNotNull(checker(ahora = 1_000_000L).chequear())
        assertNull(checker(ahora = 1_000_000L + UpdateChecker.INTERVALO_MS - 1).chequear())
        assertNotNull(checker(ahora = 1_000_000L + UpdateChecker.INTERVALO_MS).chequear())
    }

    @Test
    fun `una falla tambien cuenta como intento`() = runTest {
        var llamadas = 0
        val roto: suspend (String) -> String = { llamadas++; throw java.io.IOException("sin red") }
        assertNull(checker(fetch = roto).chequear())
        assertNull(checker(fetch = roto).chequear()) // dentro del gate: no vuelve a pegar
        assertEquals(1, llamadas)
        assertEquals(1_000_000L, prefs.ultimoChequeoUpdate())
    }

    @Test
    fun `json raro o version instalada invalida devuelven null`() = runTest {
        assertNull(checker(fetch = { "<html>rate limit</html>" }).chequear())
        prefs.setUltimoChequeoUpdate(0)
        assertNull(checker(instalada = "0.1.0", fetch = { "[]" }).chequear())
        prefs.setUltimoChequeoUpdate(0)
        assertNull(checker(instalada = "lo que sea").chequear())
    }

    @Test
    fun `cancellation se propaga`() = runTest {
        val cancelado: suspend (String) -> String = { throw CancellationException("cancelado") }
        try {
            checker(fetch = cancelado).chequear()
            throw AssertionError("debería haber lanzado CancellationException")
        } catch (e: CancellationException) {
            // esperado
        }
    }
}
