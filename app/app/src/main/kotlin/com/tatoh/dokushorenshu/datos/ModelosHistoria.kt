package com.tatoh.dokushorenshu.datos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/** fin es EXCLUSIVO, índices sobre texto (contrato de catalogo/, Plan 2).
 *  Índices = code points BMP; el catálogo no usa caracteres suplementarios. */
data class Furigana(val inicio: Int, val fin: Int, val lectura: String)
data class Oracion(val texto: String, val furigana: List<Furigana>, val traduccion: String? = null)
data class Parrafo(val oraciones: List<Oracion>)
data class Historia(
    val id: String, val titulo: String, val autor: String, val fuente: String,
    val licencia: String, val dificultad: String, val version: Int,
    val parrafos: List<Parrafo>,
)
data class EntradaCatalogo(
    val id: String, val titulo: String, val tituloLectura: String, val tituloEn: String?,
    val autor: String, val dificultad: String, val tamanio: Long, val version: Int,
    val kanjisUnicos: Int, val oraciones: Int,
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
        val version = raiz.req("version").jsonPrimitive.int
        require(version == 2) { "catálogo: versión no soportada ($version, se espera 2)" }
        Catalogo(
            version = version,
            historias = raiz.req("historias").jsonArray.map { e ->
                val o = e.jsonObject
                EntradaCatalogo(
                    id = o.texto("id"), titulo = o.texto("titulo"),
                    tituloLectura = o.texto("titulo_lectura"),
                    tituloEn = o["titulo_en"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                    autor = o.texto("autor"),
                    dificultad = validarDificultad(o.texto("dificultad")),
                    tamanio = o.req("tamaño").jsonPrimitive.content.toLong(),
                    version = o.req("version").jsonPrimitive.int,
                    kanjisUnicos = o.req("kanjis_unicos").jsonPrimitive.int,
                    oraciones = o.req("oraciones").jsonPrimitive.int,
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
        // traduccion (PR B, backlog feedback de uso): string no vacío o null/ausente.
        // Catálogos viejos no traen el campo; importadas lo emiten null.
        val traduccion = obj["traduccion"]
            ?.takeIf { it !is JsonNull }
            ?.jsonPrimitive?.content
            ?.takeIf { it.isNotEmpty() }
        return Oracion(textoOracion, furigana, traduccion)
    }

    private fun validarDificultad(valor: String): String {
        require(valor in setOf("facil", "media", "dificil")) { "dificultad inválida: '$valor'" }
        return valor
    }

    private fun JsonObject.req(clave: String) =
        this[clave] ?: throw IllegalArgumentException("falta clave '$clave'")

    private fun JsonObject.texto(clave: String): String = req(clave).jsonPrimitive.content
}

/** Inverso de [ParserHistoria.parsear]: emite el schema v2 exacto (mismo
 *  contrato que el emisor Python del Plan 2 — claves en español, furigana como
 *  ternas, `traduccion` = valor de la oración o null (importadas: null — Kuromoji no traduce),
 *  japonés sin escapar). Round-trip garantizado por test: parsear(serializar(h)) == h. */
object SerializadorHistoria {
    fun serializar(historia: Historia): String = buildJsonObject {
        put("id", historia.id)
        put("titulo", historia.titulo)
        put("autor", historia.autor)
        put("fuente", historia.fuente)
        put("licencia", historia.licencia)
        put("dificultad", historia.dificultad)
        put("version", historia.version)
        putJsonArray("parrafos") {
            for (parrafo in historia.parrafos) addJsonObject {
                putJsonArray("oraciones") {
                    for (oracion in parrafo.oraciones) addJsonObject {
                        put("texto", oracion.texto)
                        putJsonArray("furigana") {
                            for (f in oracion.furigana) addJsonArray {
                                add(f.inicio); add(f.fin); add(f.lectura)
                            }
                        }
                        if (oracion.traduccion != null) {
                            put("traduccion", oracion.traduccion)
                        } else {
                            put("traduccion", JsonNull)
                        }
                    }
                }
            }
        }
    }.toString()
}
