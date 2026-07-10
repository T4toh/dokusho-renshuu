package com.tatoh.dokushorenshu.dominio.anki

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Genera un `.apkg` (zip de `collection.anki2` SQLite + `media`) a partir de listas
 *  de notas ya armadas por `ArmadorMazos` (fuera de esta tarea). Puro respecto a
 *  datos: no conoce Room ni el Diccionario, solo [NotaWords]/[NotaKanji] y
 *  [ModeloNotas]. Schema "Anki 2 legacy" (`ver = 11`), DDL y JSON del `col`
 *  verificados contra el código fuente de genanki — ver cabecera del draft de este
 *  plan para el detalle de la investigación. */
object EscritorApkg {

    // DDL EXACTO de genanki/apkg_schema.py (APKG_SCHEMA), una sentencia por elemento
    // porque SQLiteDatabase.execSQL no acepta scripts multi-sentencia (a diferencia
    // de sqlite3.executescript en Python).
    private val DDL: List<String> = listOf(
        """CREATE TABLE col (
            id integer primary key,
            crt integer not null,
            mod integer not null,
            scm integer not null,
            ver integer not null,
            dty integer not null,
            usn integer not null,
            ls integer not null,
            conf text not null,
            models text not null,
            decks text not null,
            dconf text not null,
            tags text not null
        )""",
        """CREATE TABLE notes (
            id integer primary key,
            guid text not null,
            mid integer not null,
            mod integer not null,
            usn integer not null,
            tags text not null,
            flds text not null,
            sfld integer not null,
            csum integer not null,
            flags integer not null,
            data text not null
        )""",
        """CREATE TABLE cards (
            id integer primary key,
            nid integer not null,
            did integer not null,
            ord integer not null,
            mod integer not null,
            usn integer not null,
            type integer not null,
            queue integer not null,
            due integer not null,
            ivl integer not null,
            factor integer not null,
            reps integer not null,
            lapses integer not null,
            left integer not null,
            odue integer not null,
            odid integer not null,
            flags integer not null,
            data text not null
        )""",
        """CREATE TABLE revlog (
            id integer primary key,
            cid integer not null,
            usn integer not null,
            ease integer not null,
            ivl integer not null,
            lastIvl integer not null,
            factor integer not null,
            time integer not null,
            type integer not null
        )""",
        """CREATE TABLE graves (
            usn integer not null,
            oid integer not null,
            type integer not null
        )""",
        "CREATE INDEX ix_notes_usn on notes (usn)",
        "CREATE INDEX ix_cards_usn on cards (usn)",
        "CREATE INDEX ix_revlog_usn on revlog (usn)",
        "CREATE INDEX ix_cards_nid on cards (nid)",
        "CREATE INDEX ix_cards_sched on cards (did, queue, due)",
        "CREATE INDEX ix_revlog_cid on revlog (cid)",
        "CREATE INDEX ix_notes_csum on notes (csum)",
    )

    fun escribir(destino: File, notasWords: List<NotaWords>, notasKanji: List<NotaKanji>) {
        val sqliteTemp = File.createTempFile("apkg_", ".sqlite", destino.parentFile)
        try {
            SQLiteDatabase.openOrCreateDatabase(sqliteTemp, null).use { db ->
                for (sentencia in DDL) db.execSQL(sentencia)

                val ahoraSegundos = System.currentTimeMillis() / 1000
                insertarCol(db, ahoraSegundos)

                var idGen = ahoraSegundos * 1000 // estilo genanki: contador base epoch-ms
                var due = 1L
                for (nota in notasWords) {
                    escribirNota(
                        db, idGen, idGen + 1, ModeloNotas.guidDe(nota.claveGuid),
                        ModeloNotas.MODEL_ID_WORDS, ahoraSegundos, ModeloNotas.DECK_ID_WORDS,
                        nota.campos(), due,
                    )
                    idGen += 2
                    due += 1
                }
                for (nota in notasKanji) {
                    escribirNota(
                        db, idGen, idGen + 1, ModeloNotas.guidDe(nota.claveGuid),
                        ModeloNotas.MODEL_ID_KANJI, ahoraSegundos, ModeloNotas.DECK_ID_KANJI,
                        nota.campos(), due,
                    )
                    idGen += 2
                    due += 1
                }
            }
            zipear(sqliteTemp, destino)
        } finally {
            sqliteTemp.delete()
        }
    }

    private fun escribirNota(
        db: SQLiteDatabase,
        idNota: Long,
        idCarta: Long,
        guid: String,
        mid: Long,
        modSegundos: Long,
        did: Long,
        campos: List<String>,
        due: Long,
    ) {
        val flds = campos.joinToString("\u001f")
        val sfld = campos[0]
        db.execSQL(
            "INSERT INTO notes(id, guid, mid, mod, usn, tags, flds, sfld, csum, flags, data)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(idNota, guid, mid, modSegundos, -1L, " ", flds, sfld, csumDe(sfld), 0L, ""),
        )
        db.execSQL(
            "INSERT INTO cards(id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, reps, lapses, left, odue, odid, flags, data)" +
                " VALUES (?, ?, ?, 0, ?, ?, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, '')",
            arrayOf<Any>(idCarta, idNota, did, modSegundos, -1L, due),
        )
    }

    /** Algoritmo real de Anki para `csum` (no genanki, que lo hardcodea a 0 — ver
     *  cabecera del draft): SHA-1 del primer campo, primeros 8 hex como entero.
     *  Alimenta `ix_notes_csum` (usado por Anki para "Find Duplicates"); no afecta
     *  el import ni el guid, que es lo que garantiza que el re-export no duplica. */
    private fun csumDe(primerCampo: String): Long {
        val hash = MessageDigest.getInstance("SHA-1").digest(primerCampo.toByteArray(Charsets.UTF_8))
        val hex = hash.joinToString("") { "%02x".format(it) }
        return hex.take(8).toLong(16)
    }

    private fun insertarCol(db: SQLiteDatabase, ahoraSegundos: Long) {
        db.execSQL(
            "INSERT INTO col(id, crt, mod, scm, ver, dty, usn, ls, conf, models, decks, dconf, tags)" +
                " VALUES (NULL, ?, ?, ?, 11, 0, 0, 0, ?, ?, ?, ?, '{}')",
            arrayOf<Any>(
                ahoraSegundos, ahoraSegundos, ahoraSegundos * 1000,
                confJson(ahoraSegundos).toString(),
                modelsJson(ahoraSegundos).toString(),
                decksJson(ahoraSegundos).toString(),
                dconfJson().toString(),
            ),
        )
    }

    // --- JSON del `col`, construido con kotlinx.serialization (ya dependencia del
    // proyecto). Estructura y claves EXACTAS de genanki (apkg_col.py APKG_COL,
    // model.py Model.to_json, deck.py Deck.to_json), adaptadas a nuestros IDs. ---

    private fun confJson(modSegundos: Long) = buildJsonObject {
        put("activeDecks", buildJsonArray { add(ModeloNotas.DECK_ID_WORDS); add(ModeloNotas.DECK_ID_KANJI) })
        put("addToCur", true)
        put("collapseTime", 1200)
        put("curDeck", ModeloNotas.DECK_ID_WORDS)
        put("curModel", ModeloNotas.MODEL_ID_WORDS.toString())
        put("dueCounts", true)
        put("estTimes", true)
        put("newBury", true)
        put("newSpread", 0)
        put("nextPos", 1)
        put("sortBackwards", false)
        put("sortType", "noteFld")
        put("timeLim", 0)
    }

    private fun dconfJson() = buildJsonObject {
        put("1", buildJsonObject {
            put("autoplay", true)
            put("id", 1)
            put("lapse", buildJsonObject {
                put("delays", buildJsonArray { add(10) })
                put("leechAction", 0)
                put("leechFails", 8)
                put("minInt", 1)
                put("mult", 0)
            })
            put("maxTaken", 60)
            put("mod", 0)
            put("name", "Default")
            put("new", buildJsonObject {
                put("bury", true)
                put("delays", buildJsonArray { add(1); add(10) })
                put("initialFactor", 2500)
                put("ints", buildJsonArray { add(1); add(4); add(7) })
                put("order", 1)
                put("perDay", 20)
                put("separate", true)
            })
            put("replayq", true)
            put("rev", buildJsonObject {
                put("bury", true)
                put("ease4", 1.3)
                put("fuzz", 0.05)
                put("ivlFct", 1)
                put("maxIvl", 36500)
                put("minSpace", 1)
                put("perDay", 100)
            })
            put("timer", 0)
            put("usn", 0)
        })
    }

    private fun campoJson(nombre: String, orden: Int) = buildJsonObject {
        put("name", nombre)
        put("ord", orden)
        put("font", "Liberation Sans")
        put("media", buildJsonArray {})
        put("rtl", false)
        put("size", 20)
        put("sticky", false)
    }

    private fun templateJson(nombre: String, orden: Int, qfmt: String, afmt: String) = buildJsonObject {
        put("name", nombre)
        put("ord", orden)
        put("qfmt", qfmt)
        put("afmt", afmt)
        put("bafmt", "")
        put("bqfmt", "")
        put("bfont", "")
        put("bsize", 0)
        put("did", JsonNull)
    }

    private fun modeloJson(
        id: Long,
        nombre: String,
        campos: List<String>,
        deckId: Long,
        modSegundos: Long,
        qfmt: String,
        afmt: String,
    ) = buildJsonObject {
        put("css", ModeloNotas.CSS)
        put("did", deckId)
        put("flds", buildJsonArray { campos.forEachIndexed { i, c -> add(campoJson(c, i)) } })
        put("id", id.toString())
        put("latexPost", "\\end{document}")
        put(
            "latexPre",
            "\\documentclass[12pt]{article}\n\\special{papersize=3in,5in}\n\\usepackage[utf8]{inputenc}\n" +
                "\\usepackage{amssymb,amsmath}\n\\pagestyle{empty}\n\\setlength{\\parindent}{0in}\n\\begin{document}\n",
        )
        put("latexsvg", false)
        put("mod", modSegundos)
        put("name", nombre)
        // Único template, referencia solo el campo 0 (Palabra/Kanji) en el qfmt:
        // "required" = [[0, "all", [0]]] (misma lógica de genanki Model._req para
        // este caso simple de un solo campo obligatorio).
        put("req", buildJsonArray { add(buildJsonArray { add(0); add("all"); add(buildJsonArray { add(0) }) }) })
        put("sortf", 0)
        put("tags", buildJsonArray {})
        put("tmpls", buildJsonArray { add(templateJson("Card 1", 0, qfmt, afmt)) })
        put("type", 0) // FRONT_BACK
        put("usn", -1)
        put("vers", buildJsonArray {})
    }

    private fun modelsJson(modSegundos: Long) = buildJsonObject {
        put(
            ModeloNotas.MODEL_ID_WORDS.toString(),
            modeloJson(
                ModeloNotas.MODEL_ID_WORDS, ModeloNotas.NOMBRE_MODELO_WORDS, ModeloNotas.CAMPOS_WORDS,
                ModeloNotas.DECK_ID_WORDS, modSegundos, ModeloNotas.QFMT_WORDS, ModeloNotas.AFMT_WORDS,
            ),
        )
        put(
            ModeloNotas.MODEL_ID_KANJI.toString(),
            modeloJson(
                ModeloNotas.MODEL_ID_KANJI, ModeloNotas.NOMBRE_MODELO_KANJI, ModeloNotas.CAMPOS_KANJI,
                ModeloNotas.DECK_ID_KANJI, modSegundos, ModeloNotas.QFMT_KANJI, ModeloNotas.AFMT_KANJI,
            ),
        )
    }

    private fun deckJson(id: Long, nombre: String, modSegundos: Long) = buildJsonObject {
        put("collapsed", false)
        put("conf", 1)
        put("desc", "")
        put("dyn", 0)
        put("extendNew", 0)
        put("extendRev", 50)
        put("id", id)
        put("lrnToday", buildJsonArray { add(0); add(0) })
        put("mod", modSegundos)
        put("name", nombre)
        put("newToday", buildJsonArray { add(0); add(0) })
        put("revToday", buildJsonArray { add(0); add(0) })
        put("timeToday", buildJsonArray { add(0); add(0) })
        put("usn", -1)
    }

    private fun decksJson(modSegundos: Long) = buildJsonObject {
        put(ModeloNotas.DECK_ID_WORDS.toString(), deckJson(ModeloNotas.DECK_ID_WORDS, ModeloNotas.NOMBRE_DECK_WORDS, modSegundos))
        put(ModeloNotas.DECK_ID_KANJI.toString(), deckJson(ModeloNotas.DECK_ID_KANJI, ModeloNotas.NOMBRE_DECK_KANJI, modSegundos))
    }

    private fun zipear(sqliteFile: File, destino: File) {
        ZipOutputStream(destino.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("collection.anki2"))
            sqliteFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("media"))
            zip.write("{}".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
