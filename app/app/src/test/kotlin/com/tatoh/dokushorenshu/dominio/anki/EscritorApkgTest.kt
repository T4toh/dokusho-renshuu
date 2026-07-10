package com.tatoh.dokushorenshu.dominio.anki

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

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
}
