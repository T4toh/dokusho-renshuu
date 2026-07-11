package com.tatoh.dokushorenshu.datos

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

private class HttpFake(var respuestas: MutableMap<String, String> = mutableMapOf()) : ClienteHttp {
    override fun get(url: String): String =
        respuestas[url] ?: throw IOException("sin red (fake)")
}

class HistoriasRepoTest {
    private val momotaroJson =
        javaClass.classLoader!!.getResourceAsStream("momotaro.json")!!.readBytes().decodeToString()
    private val catalogoJson =
        javaClass.classLoader!!.getResourceAsStream("catalogo.json")!!.readBytes().decodeToString()

    private fun repo(
        http: ClienteHttp = HttpFake(),
        dir: File = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
        dirImportadas: File = File.createTempFile("imp", "").let { it.delete(); it.mkdirs(); it },
    ) = HistoriasRepo(
        leerAsset = { nombre -> if (nombre == "historias/momotaro.json") momotaroJson else null },
        listarAssetsHistorias = { listOf("momotaro.json") },
        dirDescargas = dir,
        dirImportadas = dirImportadas,
        http = http,
    )

    @Test
    fun `carga historia desde assets`() {
        assertEquals("桃太郎", repo().cargarHistoria("momotaro")!!.titulo)
        assertNull(repo().cargarHistoria("inexistente"))
    }

    @Test
    fun `catalogo remoto ok`() = runTest {
        val http = HttpFake(mutableMapOf(HistoriasRepo.URL_CATALOGO to catalogoJson))
        val resultado = repo(http).catalogoRemoto()
        assertEquals(4, resultado.getOrThrow().historias.size)
    }

    @Test
    fun `sin red devuelve failure sin bloquear lo local`() = runTest {
        val repo = repo()  // HttpFake sin respuestas → IOException
        assertTrue(repo.catalogoRemoto().isFailure)
        assertEquals(1, repo.historiasLocales().size)  // lo local sigue
    }

    @Test
    fun `descarga corrupta se descarta sin guardar`() = runTest {
        val dir = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it }
        val http = HttpFake(mutableMapOf(HistoriasRepo.urlHistoria("rota") to """{"id":"rota"}"""))
        val resultado = repo(http, dir).descargarHistoria("rota")
        assertTrue(resultado.isFailure)
        assertTrue(dir.listFiles()!!.isEmpty())  // ni el json ni un .tmp
    }

    @Test
    fun `descarga valida queda disponible como local`() = runTest {
        val dir = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it }
        val json = momotaroJson.replace(""""id": "momotaro"""", """"id": "urashima_taro"""")
        val http = HttpFake(mutableMapOf(HistoriasRepo.urlHistoria("urashima_taro") to json))
        val repo = repo(http, dir)
        assertTrue(repo.descargarHistoria("urashima_taro").isSuccess)
        assertNotNull(repo.cargarHistoria("urashima_taro"))
        assertEquals(2, repo.historiasLocales().size)
    }

    @Test
    fun `catalogoLocal lee el catalogo embebido en assets sin red`() {
        val catalogoJsonLocal = catalogoJson  // ya cargado arriba desde test resources
        val repoConCatalogoLocal = HistoriasRepo(
            leerAsset = { n ->
                when (n) {
                    "historias/momotaro.json" -> momotaroJson
                    "catalogo.json" -> catalogoJsonLocal
                    else -> null
                }
            },
            listarAssetsHistorias = { listOf("momotaro.json") },
            dirDescargas = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
            dirImportadas = File.createTempFile("imp", "").let { it.delete(); it.mkdirs(); it },
        )
        val catalogo = repoConCatalogoLocal.catalogoLocal()
        assertNotNull(catalogo)
        assertEquals(4, catalogo!!.historias.size)
    }

    @Test
    fun `catalogoLocal sin asset devuelve null`() {
        assertNull(repo().catalogoLocal())
    }

    @Test
    fun `historia importada aparece en locales y se carga por id`() {
        val repo = repo()
        val historia = ParserHistoria.parsear(momotaroJson).copy(id = "mi_texto")
        repo.guardarImportada(historia)
        assertEquals(2, repo.historiasLocales().size)
        assertEquals(historia.titulo, repo.cargarHistoria("mi_texto")!!.titulo)
        assertTrue(repo.esImportada("mi_texto"))
        assertFalse(repo.esImportada("momotaro"))
    }

    @Test
    fun `importada no pisa una historia existente con el mismo id`() {
        val repo = repo()
        val impostora = ParserHistoria.parsear(momotaroJson).copy(titulo = "偽物")
        repo.guardarImportada(impostora)  // id "momotaro" ya existe en assets
        assertEquals("桃太郎", repo.cargarHistoria("momotaro")!!.titulo)  // asset gana
        assertEquals(1, repo.historiasLocales().size)
    }

    @Test
    fun `borrar importada la saca de locales`() {
        val repo = repo()
        repo.guardarImportada(ParserHistoria.parsear(momotaroJson).copy(id = "borrable"))
        assertTrue(repo.borrarImportada("borrable"))
        assertNull(repo.cargarHistoria("borrable"))
        assertEquals(1, repo.historiasLocales().size)
        assertFalse(repo.borrarImportada("borrable"))  // segunda vez: ya no existe
    }

    @Test
    fun `idsLocales une los tres origenes`() {
        val repo = repo()
        repo.guardarImportada(ParserHistoria.parsear(momotaroJson).copy(id = "extra"))
        assertEquals(setOf("momotaro", "extra"), repo.idsLocales())
    }
}
