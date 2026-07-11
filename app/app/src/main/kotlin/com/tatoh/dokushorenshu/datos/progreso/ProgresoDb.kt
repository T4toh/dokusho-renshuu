package com.tatoh.dokushorenshu.datos.progreso

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "progreso")
data class ProgresoHistoria(
    @PrimaryKey val idHistoria: String,
    val parrafo: Int,
    val oracion: Int,
)

@Entity(tableName = "palabras_tocadas", primaryKeys = ["idHistoria", "termino"])
data class PalabraTocada(
    val idHistoria: String,
    val termino: String,
    val timestamp: Long,
)

@Entity(tableName = "prefs")
data class Pref(@PrimaryKey val clave: String, val valor: String)

/** Insumo del repaso básico (Home > Review, Task 12) y de los mazos de Plan 4.
 *  dificultad ∈ {easy, medium, hard} o null (visto pero no taggeado). timestamp
 *  se actualiza en cada apertura de Detalle kanji: funciona como "última vez
 *  visto", así el repaso rota entre los taggeados (el menos visto recientemente
 *  aparece primero). */
@Entity(tableName = "kanjis_tocados")
data class KanjiTocado(
    @PrimaryKey val kanji: String,
    val dificultad: String?,
    val timestamp: Long,
)

@Dao
interface ProgresoDao {
    @Query("SELECT * FROM progreso WHERE idHistoria = :id")
    suspend fun progreso(id: String): ProgresoHistoria?

    @Query("SELECT * FROM progreso")
    suspend fun todos(): List<ProgresoHistoria>

    @Upsert
    suspend fun guardarProgreso(progreso: ProgresoHistoria)

    @Upsert
    suspend fun registrarPalabra(palabra: PalabraTocada)

    @Query("SELECT * FROM palabras_tocadas WHERE idHistoria = :id")
    suspend fun palabrasDe(id: String): List<PalabraTocada>

    @Query("SELECT valor FROM prefs WHERE clave = :clave")
    suspend fun pref(clave: String): String?

    @Upsert
    suspend fun guardarPref(pref: Pref)

    /** Inserta la fila si no existe (IGNORE evita pisar una fila con dificultad
     *  ya taggeada); si el INSERT no aplicó (-1L => ya existía) actualiza solo
     *  el timestamp. Evitamos `INSERT ... ON CONFLICT DO UPDATE` (SQLite >= 3.24)
     *  porque minSdk 26 puede traer SQLite 3.18 y esa sintaxis lanza
     *  SQLiteException en tiempo de ejecución en esos dispositivos. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertarKanjiSiNoExiste(kanjiTocado: KanjiTocado): Long

    @Query("UPDATE kanjis_tocados SET timestamp = :timestamp WHERE kanji = :kanji")
    suspend fun actualizarTimestampKanji(kanji: String, timestamp: Long)

    suspend fun registrarAperturaKanji(kanji: String, timestamp: Long) {
        val filaNueva = insertarKanjiSiNoExiste(KanjiTocado(kanji, null, timestamp))
        if (filaNueva == -1L) {
            actualizarTimestampKanji(kanji, timestamp)
        }
    }

    @Query("UPDATE kanjis_tocados SET dificultad = :dificultad WHERE kanji = :kanji")
    suspend fun setDificultadKanji(kanji: String, dificultad: String?)

    @Query("SELECT * FROM kanjis_tocados WHERE kanji = :kanji")
    suspend fun kanjiTocado(kanji: String): KanjiTocado?

    @Query("SELECT * FROM kanjis_tocados WHERE dificultad = :dificultad ORDER BY timestamp ASC")
    suspend fun kanjisPorDificultad(dificultad: String): List<KanjiTocado>

    /** Todas las filas, de todas las historias — el dedupe por término (una
     *  palabra puede tocarse en más de una historia) lo hace el caller
     *  (`ArmadorMazos`, Plan 4a). */
    @Query("SELECT * FROM palabras_tocadas")
    suspend fun todasPalabras(): List<PalabraTocada>

    /** Solo kanjis taggeados (easy/medium/hard) — insumo del mazo de kanji
     *  (Plan 4a): los vistos-sin-tag son ruido de consulta, spec explícito. */
    @Query("SELECT * FROM kanjis_tocados WHERE dificultad IS NOT NULL")
    suspend fun kanjisTaggeados(): List<KanjiTocado>
}

/** No destructiva: agrega la tabla nueva, conserva progreso/palabras_tocadas/prefs. */
val MIGRACION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `kanjis_tocados` (" +
                "`kanji` TEXT NOT NULL, `dificultad` TEXT, `timestamp` INTEGER NOT NULL," +
                " PRIMARY KEY(`kanji`))",
        )
    }
}

@Database(
    entities = [ProgresoHistoria::class, PalabraTocada::class, Pref::class, KanjiTocado::class],
    version = 2,
    exportSchema = false,
)
abstract class ProgresoDb : RoomDatabase() {
    abstract fun dao(): ProgresoDao

    companion object {
        fun crear(contexto: Context): ProgresoDb =
            Room.databaseBuilder(contexto, ProgresoDb::class.java, "progreso.db")
                .addMigrations(MIGRACION_1_2)
                .build()
    }
}
