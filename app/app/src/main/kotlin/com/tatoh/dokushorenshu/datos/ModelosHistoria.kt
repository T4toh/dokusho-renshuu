package com.tatoh.dokushorenshu.datos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonObject

/** fin es EXCLUSIVO, índices sobre texto (contrato de catalogo/, Plan 2).
 *  Índices = code points BMP; el catálogo no usa caracteres suplementarios. */
data class Furigana(val inicio: Int, val fin: Int, val lectura: String)
data class Oracion(val texto: String, val furigana: List<Furigana>)
data class Parrafo(val oraciones: List<Oracion>)
data class Historia(
    val id: String, val titulo: String, val autor: String, val fuente: String,
    val licencia: String, val dificultad: String, val version: Int,
    val parrafos: List<Parrafo>,
)
data class EntradaCatalogo(
    val id: String, val titulo: String, val autor: String,
    val dificultad: String, val tamanio: Long, val version: Int,
)
data class Catalogo(val version: Int, val historias: List<EntradaCatalogo>)

/** Parseo manual con JsonElement: furigana es un array heterogéneo [int, int, string].
 *  Falla con IllegalArgumentException ante cualquier estructura inválida — el caller
 *  descarta la descarga corrupta (spec: nunca guardar JSON a medias). */
object ParserHistoria {
    private val json = Json { ignoreUnknownKeys = true }

    fun parsear(texto: String): Historia = try {
        val raiz = json.parseToJsonElement(texto).jsonObject
        // Validar primero los campos de texto (fail fast si falta alguno)
        val id = raiz.texto("id")
        val titulo = raiz.texto("titulo")
        val autor = raiz.texto("autor")
        val fuente = raiz.texto("fuente")
        val licencia = raiz.texto("licencia")
        val dificultad = validarDificultad(raiz.texto("dificultad"))
        val version = raiz.req("version").jsonPrimitive.int
        // Después procesar los párrafos
        val parrafos = raiz.req("parrafos").jsonArray.map { p ->
            Parrafo(p.jsonObject.req("oraciones").jsonArray.map { parsearOracion(it.jsonObject) })
        }
        require(parrafos.isNotEmpty()) { "historia sin párrafos" }
        Historia(id, titulo, autor, fuente, licencia, dificultad, version, parrafos)
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("JSON de historia inválido: ${e.message}", e)
    }

    fun parsearCatalogo(texto: String): Catalogo = try {
        val raiz = json.parseToJsonElement(texto).jsonObject
        Catalogo(
            version = raiz.req("version").jsonPrimitive.int,
            historias = raiz.req("historias").jsonArray.map { e ->
                val o = e.jsonObject
                EntradaCatalogo(
                    id = o.texto("id"), titulo = o.texto("titulo"), autor = o.texto("autor"),
                    dificultad = validarDificultad(o.texto("dificultad")),
                    tamanio = o.req("tamaño").jsonPrimitive.content.toLong(),
                    version = o.req("version").jsonPrimitive.int,
                )
            },
        )
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("JSON de catálogo inválido: ${e.message}", e)
    }

    private fun parsearOracion(obj: JsonObject): Oracion {
        val textoOracion = obj.texto("texto")
        require(textoOracion.isNotEmpty()) { "oración con texto vacío" }
        val furigana = obj["furigana"]?.jsonArray?.map { f ->
            val terna = f.jsonArray
            require(terna.size == 3) { "furigana no es terna: $terna" }
            val inicio = terna[0].jsonPrimitive.int
            val fin = terna[1].jsonPrimitive.int
            val lectura = terna[2].jsonPrimitive.content
            require(inicio in 0 until fin && fin <= textoOracion.length && lectura.isNotEmpty()) {
                "furigana fuera de rango: [$inicio, $fin] sobre ${textoOracion.length} chars"
            }
            Furigana(inicio, fin, lectura)
        } ?: emptyList()
        return Oracion(textoOracion, furigana)
    }

    private fun validarDificultad(valor: String): String {
        require(valor in setOf("facil", "media", "dificil")) { "dificultad inválida: '$valor'" }
        return valor
    }

    private fun JsonObject.req(clave: String) =
        this[clave] ?: throw IllegalArgumentException("falta clave '$clave'")

    private fun JsonObject.texto(clave: String): String = req(clave).jsonPrimitive.content
}
