package com.tatoh.dokushorenshu.datos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecortesRepoTest {

    @get:Rule val carpeta = TemporaryFolder()

    // log no-op: android.util.Log no existe en tests de JVM plano (mismo patrón que
    // ImportViewModelTest/ExportViewModelTest).
    private fun repo() = RecortesRepo(carpeta.root, log = { _, _ -> })

    private fun recorte(id: String, ts: Long, texto: String = "あ") = Recorte(
        id = id,
        texto = texto,
        parrafos = listOf(Parrafo(listOf(Oracion(texto, emptyList())))),
        timestamp = ts,
        tieneImagen = false,
    )

    @Test
    fun `guardar y cargar devuelve el mismo recorte`() {
        val r = repo()
        r.guardar(recorte("100", 100L))
        assertEquals(recorte("100", 100L), r.cargar("100"))
    }

    @Test
    fun `cargar un id inexistente devuelve null`() {
        assertNull(repo().cargar("noexiste"))
    }

    @Test
    fun `listar ordena por timestamp descendente`() {
        val r = repo()
        r.guardar(recorte("100", 100L))
        r.guardar(recorte("300", 300L))
        r.guardar(recorte("200", 200L))
        assertEquals(listOf("300", "200", "100"), r.listar().map { it.id })
    }

    @Test
    fun `listar saltea un archivo corrupto sin tumbar la lista`() {
        val r = repo()
        r.guardar(recorte("100", 100L))
        File(carpeta.root, "999.json").writeText("{roto")
        assertEquals(listOf("100"), r.listar().map { it.id })
    }

    @Test
    fun `guardar con imagen pendiente la mueve y marca tieneImagen`() {
        val r = repo()
        val pendiente = carpeta.newFile("captura-pendiente.jpg")
        pendiente.writeBytes(byteArrayOf(1, 2, 3))
        val guardado = r.guardar(recorte("100", 100L), pendiente)
        assertTrue(guardado.tieneImagen)
        assertFalse("el pendiente se mueve, no se copia", pendiente.exists())
        assertEquals(listOf<Byte>(1, 2, 3), r.archivoImagen("100")!!.readBytes().toList())
    }

    @Test
    fun `guardar sin imagen deja tieneImagen en false y archivoImagen en null`() {
        val r = repo()
        val guardado = r.guardar(recorte("100", 100L), null)
        assertFalse(guardado.tieneImagen)
        assertNull(r.archivoImagen("100"))
    }

    @Test
    fun `quitarImagen borra la imagen y conserva el recorte`() {
        val r = repo()
        val pendiente = carpeta.newFile("captura-pendiente.jpg").apply { writeBytes(byteArrayOf(1)) }
        r.guardar(recorte("100", 100L), pendiente)
        assertTrue(r.quitarImagen("100"))
        assertNull(r.archivoImagen("100"))
        assertFalse("el recorte sobrevive", r.cargar("100")!!.tieneImagen)
    }

    @Test
    fun `borrar elimina el json y la imagen`() {
        val r = repo()
        val pendiente = carpeta.newFile("captura-pendiente.jpg").apply { writeBytes(byteArrayOf(1)) }
        r.guardar(recorte("100", 100L), pendiente)
        assertTrue(r.borrar("100"))
        assertNull(r.cargar("100"))
        assertNull(r.archivoImagen("100"))
    }

    @Test
    fun `idLibre incrementa si el timestamp ya esta tomado`() {
        val r = repo()
        r.guardar(recorte("100", 100L))
        assertNotEquals("100", r.idLibre(100L))
        assertEquals("101", r.idLibre(100L))
    }

    @Test
    fun `tieneImagen true con el archivo ausente no rompe archivoImagen`() {
        val r = repo()
        val pendiente = carpeta.newFile("captura-pendiente.jpg").apply { writeBytes(byteArrayOf(1)) }
        r.guardar(recorte("100", 100L), pendiente)
        r.archivoImagen("100")!!.delete()  // borrado externo
        assertNull(r.archivoImagen("100"))
        assertTrue("el recorte sigue cargando", r.cargar("100") != null)
    }
}
