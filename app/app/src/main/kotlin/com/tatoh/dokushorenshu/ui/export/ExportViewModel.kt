package com.tatoh.dokushorenshu.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDao
import com.tatoh.dokushorenshu.dominio.anki.ArmadorMazos
import com.tatoh.dokushorenshu.dominio.anki.EscritorApkg
import com.tatoh.dokushorenshu.dominio.anki.MazoNotas
import com.tatoh.dokushorenshu.dominio.anki.ModeloNotas
import com.tatoh.dokushorenshu.dominio.anki.NotaKanji
import com.tatoh.dokushorenshu.dominio.anki.NotaWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Un botón por mazo en la pantalla — nunca un archivo combinado (spec Plan 4a:
 *  "dos mazos"). */
enum class TipoExport { WORDS, KANJI, STORIES }

data class ContadoresExport(val words: Int, val kanjisTaggeados: Int, val historias: Int = 0)

sealed interface EstadoExport {
    data object Idle : EstadoExport
    data object Generando : EstadoExport
    data class Listo(val archivo: File, val resumen: String) : EstadoExport
    data class Error(val mensaje: String) : EstadoExport
}

class ExportViewModel(
    private val progresoDao: ProgresoDao,
    private val armadorMazos: ArmadorMazos,
    private val dirExport: File,
    // inyectable solo para tests: mismo motivo que ClienteHttp/HistoriasRepo —
    // evita que el test real escriba/zippee un .apkg (eso lo cubre
    // EscritorApkgTest, Tasks 1-2). En producción, EscritorApkg::escribir.
    private val escribir: (File, List<NotaWords>, List<NotaKanji>) -> Unit = EscritorApkg::escribir,
    private val escribirMazos: (File, List<MazoNotas>) -> Unit = EscritorApkg::escribir,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // inyectable: android.util.Log no existe en los tests de JVM plano
    private val log: (String, Throwable) -> Unit = { msg, t -> android.util.Log.e("ExportViewModel", msg, t) },
) : ViewModel() {
    private val _contadores = MutableStateFlow(ContadoresExport(0, 0))
    val contadores: StateFlow<ContadoresExport> = _contadores

    private val _estado = MutableStateFlow<EstadoExport>(EstadoExport.Idle)
    val estado: StateFlow<EstadoExport> = _estado

    fun cargar() {
        viewModelScope.launch {
            try {
                _contadores.value = withContext(ioDispatcher) {
                    val words = progresoDao.todasPalabras().map { it.termino }.distinct().size
                    val kanjis = progresoDao.kanjisTaggeados().size
                    val historias = armadorMazos.contarHistoriasLocales()
                    ContadoresExport(words, kanjis, historias)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // counts quedan en 0 (botones deshabilitados con hint) — nunca crash
                log("cargar() falló leyendo counts", e)
            }
        }
    }

    fun exportar(tipo: TipoExport) {
        viewModelScope.launch {
            // guard contra doble tap — dos escrituras concurrentes corromperían el .apkg
            if (_estado.value is EstadoExport.Generando) return@launch
            _estado.value = EstadoExport.Generando
            val destino = File(dirExport, nombreArchivo(tipo))
            try {
                val resumen = withContext(ioDispatcher) {
                    dirExport.mkdirs()
                    val resultado = armadorMazos.armar()
                    when (tipo) {
                        TipoExport.WORDS -> {
                            escribir(destino, resultado.notasWords, emptyList())
                            "${resultado.notasWords.size} words"
                        }
                        TipoExport.KANJI -> {
                            escribir(destino, emptyList(), resultado.notasKanji)
                            val base = "${resultado.notasKanji.size} kanji"
                            if (resultado.kanjisOmitidos > 0) "$base (${resultado.kanjisOmitidos} skipped)" else base
                        }
                        TipoExport.STORIES -> {
                            val resultadoHistorias = armadorMazos.armarHistorias()
                            val mazos = resultadoHistorias.mazos.map { mazo ->
                                MazoNotas(
                                    deckId = ModeloNotas.deckIdDeHistoria(mazo.idHistoria),
                                    nombre = ModeloNotas.nombreDeckHistoria(mazo.titulo),
                                    notasKanji = mazo.notas,
                                )
                            }
                            escribirMazos(destino, mazos)
                            val totalKanji = resultadoHistorias.mazos.sumOf { it.notas.size }
                            val base = "${resultadoHistorias.mazos.size} stories ($totalKanji kanji"
                            if (resultadoHistorias.kanjisOmitidos > 0) {
                                "$base, ${resultadoHistorias.kanjisOmitidos} skipped)"
                            } else {
                                "$base)"
                            }
                        }
                    }
                }
                _estado.value = EstadoExport.Listo(destino, "Exported $resumen")
            } catch (e: CancellationException) {
                destino.delete()  // nunca dejar un .apkg a medias en cache (spec "Manejo de errores")
                throw e  // nunca tragar la cancelación del propio viewModelScope
            } catch (e: Exception) {
                destino.delete()  // nunca dejar un .apkg a medias en cache (spec "Manejo de errores")
                log("exportar($tipo) falló", e)
                _estado.value = EstadoExport.Error("Export failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun nombreArchivo(tipo: TipoExport): String = when (tipo) {
        TipoExport.WORDS -> "dokusho-words.apkg"
        TipoExport.KANJI -> "dokusho-kanji.apkg"
        TipoExport.STORIES -> "dokusho-stories.apkg"
    }
}
