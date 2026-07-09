package com.tatoh.dokushorenshu.datos

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** diccionario-v2.db readonly. Se copia de assets al filesDir en el primer
 *  arranque; si falta o la versión no valida, se re-copia (spec: nunca crash). */
class DiccionarioSqlite private constructor(private val db: SQLiteDatabase) : Diccionario {

    companion object {
        const val NOMBRE_DB = "diccionario-v2.db"
        const val VERSION_ESPERADA = 2

        fun abrir(contexto: Context): DiccionarioSqlite {
            // limpieza post-upgrade: dispositivos que migraron desde v1 se quedan con
            // diccionario-v1.db huérfano (~79 MB) en filesDir; delete() es no-op si no existe.
            File(contexto.filesDir, "diccionario-v1.db").delete()
            val archivo = File(contexto.filesDir, NOMBRE_DB)
            if (!archivo.exists() || !versionValida(archivo)) {
                copiarDesdeAssets(contexto, archivo)
            }
            File(contexto.filesDir, "diccionario-v1.db").delete()
            return desdeArchivo(archivo.path)
        }

        /** Para tests: abre un db ya existente sin pasar por assets. */
        fun desdeArchivo(ruta: String): DiccionarioSqlite =
            DiccionarioSqlite(SQLiteDatabase.openDatabase(ruta, null, SQLiteDatabase.OPEN_READONLY))

        private fun copiarDesdeAssets(contexto: Context, archivo: File) {
            archivo.delete()
            contexto.assets.open(NOMBRE_DB).use { entrada ->
                archivo.outputStream().use { salida -> entrada.copyTo(salida) }
            }
        }

        private fun versionValida(archivo: File): Boolean = try {
            SQLiteDatabase.openDatabase(archivo.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT valor FROM metadata WHERE clave = 'version'", null).use { c ->
                    c.moveToFirst() && c.getString(0).toInt() == VERSION_ESPERADA
                }
            }
        } catch (e: Exception) {
            false  // corrupto o esquema desconocido → re-copiar
        }
    }

    override fun buscarPalabra(termino: String): List<Palabra> =
        db.rawQuery(
            "SELECT termino, lectura, significados, tags, popularidad FROM palabras" +
                " WHERE termino = ? ORDER BY popularidad DESC LIMIT 10",
            arrayOf(termino),
        ).use { c ->
            generateSequence { if (c.moveToNext()) c else null }.map {
                Palabra(
                    termino = c.getString(0),
                    lectura = c.getString(1),
                    significados = listaJson(c.getString(2)),
                    tags = listaJson(c.getString(3)),
                    popularidad = c.getInt(4),
                )
            }.toList()
        }

    override fun buscarPorLectura(lectura: String): List<Palabra> =
        db.rawQuery(
            "SELECT termino, lectura, significados, tags, popularidad FROM palabras" +
                " WHERE lectura = ? ORDER BY popularidad DESC LIMIT 10",
            arrayOf(lectura),
        ).use { c ->
            generateSequence { if (c.moveToNext()) c else null }.map {
                Palabra(
                    termino = c.getString(0),
                    lectura = c.getString(1),
                    significados = listaJson(c.getString(2)),
                    tags = listaJson(c.getString(3)),
                    popularidad = c.getInt(4),
                )
            }.toList()
        }

    override fun buscarKanji(kanji: String): KanjiInfo? =
        db.rawQuery(
            "SELECT kanji, significados, on_yomi, kun_yomi, jlpt, strokes FROM kanjis WHERE kanji = ?",
            arrayOf(kanji),
        ).use { c ->
            if (!c.moveToFirst()) return null
            KanjiInfo(
                kanji = c.getString(0),
                significados = listaJson(c.getString(1)),
                onYomi = listaJson(c.getString(2)),
                kunYomi = listaJson(c.getString(3)),
                jlpt = if (c.isNull(4)) null else c.getInt(4),
                strokes = if (c.isNull(5)) null else c.getInt(5),
            )
        }

    override fun oracionesDePalabra(termino: String, limite: Int): List<OracionEjemplo> =
        consultarOraciones(
            "SELECT o.japones, o.ingles FROM oracion_palabra op" +
                " JOIN oraciones o ON o.id = op.id_oracion WHERE op.termino = ? LIMIT ?",
            termino, limite,
        )

    override fun oracionesDeKanji(kanji: String, limite: Int): List<OracionEjemplo> =
        consultarOraciones(
            "SELECT o.japones, o.ingles FROM oracion_kanji ok" +
                " JOIN oraciones o ON o.id = ok.id_oracion WHERE ok.kanji = ? LIMIT ?",
            kanji, limite,
        )

    private fun consultarOraciones(sql: String, arg: String, limite: Int): List<OracionEjemplo> =
        db.rawQuery(sql, arrayOf(arg, limite.toString())).use { c ->
            generateSequence { if (c.moveToNext()) c else null }
                .map { OracionEjemplo(c.getString(0), c.getString(1)) }
                .toList()
        }

    private fun listaJson(crudo: String?): List<String> =
        if (crudo.isNullOrEmpty()) emptyList()
        else Json.parseToJsonElement(crudo).jsonArray.map { it.jsonPrimitive.content }
}
