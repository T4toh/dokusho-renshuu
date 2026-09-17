package com.tatoh.dokushorenshu.datos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Un recorte de captura: texto con furigana, sin la metadata de catálogo que
 *  arrastra [Historia] (autor, licencia, dificultad, version, fuente) y que no
 *  significa nada para tres líneas de manga.
 *
 *  [texto] es la fuente de verdad y [parrafos] la caché derivada: al editar se
 *  regeneran los párrafos desde el texto, así no hay dos estados que puedan
 *  desincronizarse.
 *
 *  [tieneImagen] es derivable de si existe `<id>.jpg`, pero se guarda para no
 *  hacer un File.exists() por fila al pintar la lista. */
data class Recorte(
    val id: String,
    val texto: String,
    val parrafos: List<Parrafo>,
    val timestamp: Long,
    val tieneImagen: Boolean,
)

object SerializadorRecorte {
    fun serializar(recorte: Recorte): String = buildJsonObject {
        put("id", recorte.id)
        put("texto", recorte.texto)
        put("timestamp", recorte.timestamp)
        put("tieneImagen", recorte.tieneImagen)
        putJsonArray("parrafos") {
            for (parrafo in recorte.parrafos) addJsonObject {
                putJsonArray("oraciones") {
                    for (oracion in parrafo.oraciones) addJsonObject {
                        put("texto", oracion.texto)
                        // mismo formato de terna que el catálogo: [inicio, fin, lectura]
                        putJsonArray("furigana") {
                            for (f in oracion.furigana) addJsonArray {
                                add(f.inicio); add(f.fin); add(f.lectura)
                            }
                        }
                        // un recorte no tiene traducción: es captura sin contexto
                    }
                }
            }
        }
    }.toString()
}

object ParserRecorte {
    private val json = Json { ignoreUnknownKeys = true }

    /** Falla con IllegalArgumentException ante cualquier estructura inválida —
     *  el caller descarta el archivo corrupto y sigue (spec: un recorte roto
     *  nunca tumba la lista). */
    fun parsear(texto: String): Recorte = try {
        val raiz = json.parseToJsonElement(texto).jsonObject
        Recorte(
            id = raiz.req("id").jsonPrimitive.content,
            texto = raiz.req("texto").jsonPrimitive.content,
            timestamp = raiz.req("timestamp").jsonPrimitive.long,
            tieneImagen = raiz.req("tieneImagen").jsonPrimitive.boolean,
            parrafos = raiz.req("parrafos").jsonArray.map { p ->
                Parrafo(p.jsonObject.req("oraciones").jsonArray.map { o ->
                    val obj = o.jsonObject
                    val textoOracion = obj.req("texto").jsonPrimitive.content
                    Oracion(
                        texto = textoOracion,
                        furigana = obj.req("furigana").jsonArray.map { f ->
                            val terna = f.jsonArray
                            require(terna.size == 3) { "furigana no es terna: $terna" }
                            val inicio = terna[0].jsonPrimitive.int
                            val fin = terna[1].jsonPrimitive.int
                            val lectura = terna[2].jsonPrimitive.content
                            require(inicio in 0 until fin && fin <= textoOracion.length && lectura.isNotEmpty()) {
                                "furigana fuera de rango: [$inicio, $fin] sobre ${textoOracion.length} chars"
                            }
                            Furigana(inicio, fin, lectura)
                        },
                    )
                })
            },
        )
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("JSON de recorte inválido: ${e.message}", e)
    } catch (e: Exception) {
        throw IllegalArgumentException("JSON de recorte inválido: ${e.message}", e)
    }

    private fun JsonObject.req(clave: String) =
        this[clave] ?: throw IllegalArgumentException("falta el campo '$clave'")
}
