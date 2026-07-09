package com.tatoh.dokushorenshu.ui.kanji

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tatoh.dokushorenshu.datos.Diccionario
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.OracionEjemplo
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EstadoKanji(
    val info: KanjiInfo? = null,
    val ejemplos: List<OracionEjemplo> = emptyList(),
    val cargando: Boolean = true,
    val dificultad: String? = null,
)

class DetalleKanjiViewModel(
    private val kanji: String,
    private val diccionario: Diccionario,
    private val progresoDao: ProgresoDao,
    // en producción siempre Dispatchers.IO (mismo patrón que LectorViewModel).
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoKanji())
    val estado: StateFlow<EstadoKanji> = _estado

    fun cargar() {
        viewModelScope.launch {
            val nuevo = withContext(ioDispatcher) {
                // registro automático: cuenta como "visto" cada vez que se abre.
                progresoDao.registrarAperturaKanji(kanji, System.currentTimeMillis())
                EstadoKanji(
                    info = diccionario.buscarKanji(kanji),
                    ejemplos = diccionario.oracionesDeKanji(kanji, limite = 5),
                    cargando = false,
                    dificultad = progresoDao.kanjiTocado(kanji)?.dificultad,
                )
            }
            _estado.value = nuevo
        }
    }

    /** Tap en un FilterChip de dificultad: taggea, o destaggea si ya estaba activo. */
    fun alternarDificultad(valor: String) {
        val siguiente = if (_estado.value.dificultad == valor) null else valor
        _estado.value = _estado.value.copy(dificultad = siguiente)
        viewModelScope.launch(ioDispatcher) { progresoDao.setDificultadKanji(kanji, siguiente) }
    }
}
