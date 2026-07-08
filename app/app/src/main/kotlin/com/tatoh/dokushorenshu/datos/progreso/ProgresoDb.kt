package com.tatoh.dokushorenshu.datos.progreso

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

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
}

@Database(
    entities = [ProgresoHistoria::class, PalabraTocada::class, Pref::class],
    version = 1,
    exportSchema = false,
)
abstract class ProgresoDb : RoomDatabase() {
    abstract fun dao(): ProgresoDao

    companion object {
        fun crear(contexto: Context): ProgresoDb =
            Room.databaseBuilder(contexto, ProgresoDb::class.java, "progreso.db").build()
    }
}
