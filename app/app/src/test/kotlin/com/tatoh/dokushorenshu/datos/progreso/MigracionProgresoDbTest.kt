package com.tatoh.dokushorenshu.datos.progreso

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigracionProgresoDbTest {
    @Test
    fun `migracion 1 a 2 crea kanjis_tocados sin borrar progreso existente`() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        val nombre = "migracion_test.db"
        contexto.deleteDatabase(nombre)

        // Simula un usuario en v1: schema creado a mano + user_version=1.
        val v1 = SQLiteDatabase.openOrCreateDatabase(contexto.getDatabasePath(nombre), null)
        v1.execSQL(
            "CREATE TABLE progreso (idHistoria TEXT NOT NULL, parrafo INTEGER NOT NULL," +
                " oracion INTEGER NOT NULL, PRIMARY KEY(idHistoria))",
        )
        v1.execSQL(
            "CREATE TABLE palabras_tocadas (idHistoria TEXT NOT NULL, termino TEXT NOT NULL," +
                " timestamp INTEGER NOT NULL, PRIMARY KEY(idHistoria, termino))",
        )
        v1.execSQL("CREATE TABLE prefs (clave TEXT NOT NULL, valor TEXT NOT NULL, PRIMARY KEY(clave))")
        v1.execSQL("INSERT INTO progreso VALUES ('momotaro', 3, 1)")
        v1.version = 1
        v1.close()

        val db = Room.databaseBuilder(contexto, ProgresoDb::class.java, nombre)
            .addMigrations(MIGRACION_1_2)
            .build()
        val dao = db.dao()

        runBlocking {
            assertEquals(3, dao.progreso("momotaro")?.parrafo)  // progreso previo intacto
            dao.registrarAperturaKanji("語", 100L)
            assertEquals("語", dao.kanjiTocado("語")?.kanji)
            assertNull(dao.kanjiTocado("語")?.dificultad)
        }
        db.close()
    }
}
