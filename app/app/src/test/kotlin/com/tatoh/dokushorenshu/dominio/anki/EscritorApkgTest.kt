package com.tatoh.dokushorenshu.dominio.anki

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
class EscritorApkgTest {

    private fun notaWords(palabra: String) = NotaWords(
        palabra = palabra, lectura = "よみ", significados = "significado",
        oraciones = listOf("<ruby>物<rt>もの</rt></ruby>語です。"),
    )

    private fun notaKanji(kanji: String) = NotaKanji(
        kanji = kanji, onYomi = "オン", kunYomi = "くん", significados = "significado",
        dificultad = "easy", oraciones = listOf("oración kanji"),
    )

    /** Extrae collection.anki2 del zip a un archivo temporal real (SQLite necesita ruta,
     *  mismo motivo que DiccionarioSqliteTest.abrirFixture). */
    private fun unzipCollection(apkg: File): File {
        val destino = File.createTempFile("collection", ".anki2")
        ZipFile(apkg).use { zip ->
            val entrada = zip.getEntry("collection.anki2")!!
            zip.getInputStream(entrada).use { entrada2 -> destino.outputStream().use { entrada2.copyTo(it) } }
        }
        return destino
    }

    @Test
    fun `apkg generado pasa integrity_check y tiene los counts correctos`() {
        val destino = File.createTempFile("mazo", ".apkg")
        EscritorApkg.escribir(destino, listOf(notaWords("物語"), notaWords("犬")), listOf(notaKanji("犬")))

        val sqlite = unzipCollection(destino)
        SQLiteDatabase.openDatabase(sqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ok", c.getString(0))
            }
            db.rawQuery("SELECT COUNT(*) FROM notes", null).use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM cards", null).use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM notes WHERE mid = ?", arrayOf(ModeloNotas.MODEL_ID_WORDS.toString())).use { c ->
                c.moveToFirst(); assertEquals(2, c.getInt(0))
            }
            db.rawQuery("SELECT COUNT(*) FROM notes WHERE mid = ?", arrayOf(ModeloNotas.MODEL_ID_KANJI.toString())).use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
        }
    }

    @Test
    fun `guid y flds estables entre dos corridas independientes`() {
        val notas = listOf(notaWords("物語"))
        val destino1 = File.createTempFile("mazo1", ".apkg")
        val destino2 = File.createTempFile("mazo2", ".apkg")
        EscritorApkg.escribir(destino1, notas, emptyList())
        EscritorApkg.escribir(destino2, notas, emptyList())

        fun guidYFlds(apkg: File): Pair<String, String> {
            val sqlite = unzipCollection(apkg)
            return SQLiteDatabase.openDatabase(sqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT guid, flds FROM notes", null).use { c ->
                    c.moveToFirst()
                    c.getString(0) to c.getString(1)
                }
            }
        }
        assertEquals(guidYFlds(destino1), guidYFlds(destino2))
    }

    @Test
    fun `media es exactamente el objeto json vacio`() {
        val destino = File.createTempFile("mazo", ".apkg")
        EscritorApkg.escribir(destino, listOf(notaWords("物語")), emptyList())
        ZipFile(destino).use { zip ->
            val texto = zip.getInputStream(zip.getEntry("media")!!).bufferedReader().readText()
            assertEquals("{}", texto)
        }
    }

    @Test
    fun `flds usa el separador 0x1f y sfld es el primer campo`() {
        val destino = File.createTempFile("mazo", ".apkg")
        EscritorApkg.escribir(destino, listOf(notaWords("物語")), emptyList())
        val sqlite = unzipCollection(destino)
        SQLiteDatabase.openDatabase(sqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT flds, sfld FROM notes LIMIT 1", null).use { c ->
                c.moveToFirst()
                val campos = c.getString(0).split("\u001f")
                assertEquals(9, campos.size) // CAMPOS_WORDS
                assertEquals("物語", campos[0])
                assertEquals("物語", c.getString(1))
            }
        }
    }

    @Test
    fun `col decks contiene Default DECK_ID_WORDS y DECK_ID_KANJI`() {
        val destino = File.createTempFile("mazo", ".apkg")
        EscritorApkg.escribir(destino, listOf(notaWords("物語")), listOf(notaKanji("犬")))
        val sqlite = unzipCollection(destino)
        SQLiteDatabase.openDatabase(sqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT decks FROM col", null).use { c ->
                c.moveToFirst()
                val decksJson = Json.parseToJsonElement(c.getString(0)).jsonObject
                assertTrue("Falta deck Default (1)", decksJson.containsKey("1"))
                assertTrue("Falta DECK_ID_WORDS", decksJson.containsKey(ModeloNotas.DECK_ID_WORDS.toString()))
                assertTrue("Falta DECK_ID_KANJI", decksJson.containsKey(ModeloNotas.DECK_ID_KANJI.toString()))
            }
        }
    }

    @Test
    fun `escribir con mazos de historias crea un subdeck por historia y asigna las cards`() {
        val notaMomotaro = NotaKanji("山", "サン", "やま", "mountain", "", claveGuidPropia = "story:momotaro:山")
        val notaUrashima = NotaKanji("浦", "ホ", "うら", "bay", "", claveGuidPropia = "story:urashima_taro:浦")
        val mazos = listOf(
            MazoNotas(
                deckId = ModeloNotas.deckIdDeHistoria("momotaro"),
                nombre = ModeloNotas.nombreDeckHistoria("桃太郎"),
                notasKanji = listOf(notaMomotaro),
            ),
            MazoNotas(
                deckId = ModeloNotas.deckIdDeHistoria("urashima_taro"),
                nombre = ModeloNotas.nombreDeckHistoria("浦島太郎"),
                notasKanji = listOf(notaUrashima),
            ),
        )
        val destino = File.createTempFile("stories", ".apkg")
        EscritorApkg.escribir(destino, mazos)

        val sqlite = unzipCollection(destino)
        SQLiteDatabase.openDatabase(sqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            // decks: Default + los 2 subdecks, SIN Words/Kanji
            db.rawQuery("SELECT decks FROM col", null).use { c ->
                c.moveToFirst()
                val decksJson = Json.parseToJsonElement(c.getString(0)).jsonObject
                assertEquals(
                    setOf(
                        "1",
                        ModeloNotas.deckIdDeHistoria("momotaro").toString(),
                        ModeloNotas.deckIdDeHistoria("urashima_taro").toString(),
                    ),
                    decksJson.keys,
                )
                assertEquals(
                    "Dokusho — Stories::桃太郎",
                    decksJson[ModeloNotas.deckIdDeHistoria("momotaro").toString()]!!.jsonObject["name"]!!.jsonPrimitive.content,
                )
            }
            // cada card en el did de su mazo, due incremental global (1, 2)
            db.rawQuery("SELECT did, due FROM cards ORDER BY due", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(ModeloNotas.deckIdDeHistoria("momotaro"), c.getLong(0))
                assertEquals(1L, c.getLong(1))
                assertTrue(c.moveToNext())
                assertEquals(ModeloNotas.deckIdDeHistoria("urashima_taro"), c.getLong(0))
                assertEquals(2L, c.getLong(1))
            }
            // guid de la nota = guidDe de la clave propia
            db.rawQuery("SELECT guid FROM notes ORDER BY id LIMIT 1", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(ModeloNotas.guidDe("story:momotaro:山"), c.getString(0))
            }
        }
    }

    @Test
    fun `escribir con lista vacia de mazos falla rapido`() {
        val destino = File.createTempFile("vacio", ".apkg")
        assertThrows(IllegalArgumentException::class.java) {
            EscritorApkg.escribir(destino, emptyList())
        }
    }
}
