package com.tatoh.dokushorenshu.ui.importar

import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.dominio.GeneradorFurigana
import com.tatoh.dokushorenshu.dominio.ImportadorHistoria
import com.tatoh.dokushorenshu.dominio.Tokenizador
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImportViewModelTest {
    companion object {
        private val generador = GeneradorFurigana(Tokenizador())
    }

    private val dispatcher = StandardTestDispatcher()

    @After fun despues() = Dispatchers.resetMain()

    private fun tempDir() = File.createTempFile("imp", "").let { it.delete(); it.mkdirs(); it }

    private fun vm(): ImportViewModel {
        Dispatchers.setMain(dispatcher)
        val repo = HistoriasRepo(
            leerAsset = { null }, listarAssetsHistorias = { emptyList() },
            dirDescargas = tempDir(), dirImportadas = tempDir(),
        )
        return ImportViewModel(
            importador = ImportadorHistoria(generador, repo),
            ioDispatcher = dispatcher,
            log = { _, _ -> },
        )
    }

    @Test
    fun `import feliz llega a Listo con el id`() = runTest(dispatcher) {
        val vm = vm()
        vm.setTitulo("犬の話"); vm.setTexto("犬が走った。")
        vm.importar()
        advanceUntilIdle()
        assertEquals("犬の話", (vm.estado.value as EstadoImport.Listo).id)
    }

    @Test
    fun `texto no japones pide confirmacion y forzar lo importa igual`() = runTest(dispatcher) {
        val vm = vm()
        vm.setTitulo("english"); vm.setTexto("This is English text, not Japanese at all.")
        vm.importar()
        assertEquals(EstadoImport.ConfirmarNoJapones, vm.estado.value)
        vm.importar(forzar = true)
        advanceUntilIdle()
        assertTrue(vm.estado.value is EstadoImport.Listo)
    }

    @Test
    fun `archivo no UTF-8 da error sin tocar el texto`() = runTest(dispatcher) {
        val vm = vm()
        vm.setTexto("previo")
        vm.cargarArchivo(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x40))  // no es UTF-8
        assertTrue(vm.estado.value is EstadoImport.Error)
        assertEquals("previo", vm.form.value.texto)
    }

    @Test
    fun `archivo UTF-8 valido reemplaza el texto`() = runTest(dispatcher) {
        val vm = vm()
        vm.cargarArchivo("犬が走った。".toByteArray())
        assertEquals("犬が走った。", vm.form.value.texto)
    }

    @Test
    fun `dificultad UI se mapea al schema`() = runTest(dispatcher) {
        val vm = vm()
        vm.setTitulo("た"); vm.setTexto("犬。"); vm.setDificultad("hard")
        vm.importar(forzar = true)
        advanceUntilIdle()
        assertTrue(vm.estado.value is EstadoImport.Listo)
        // dificultad persistida como "dificil" — verificado vía ImportadorHistoriaTest;
        // acá alcanza con que no lance IllegalArgumentException ("hard" crudo lanzaría).
    }
}
