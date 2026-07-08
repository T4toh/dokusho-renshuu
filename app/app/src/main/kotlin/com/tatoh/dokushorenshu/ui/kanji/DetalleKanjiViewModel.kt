package com.tatoh.dokushorenshu.ui.kanji

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tatoh.dokushorenshu.datos.Diccionario
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.OracionEjemplo
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
)

class DetalleKanjiViewModel(
    private val kanji: String,
    private val diccionario: Diccionario,
    // en producción siempre Dispatchers.IO: buscarKanji/oracionesDeKanji son I/O
    // bloqueante síncrono sobre SQLite (mismo patrón que LectorViewModel/Task 10).
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoKanji())
    val estado: StateFlow<EstadoKanji> = _estado

    fun cargar() {
        viewModelScope.launch {
            _estado.value = withContext(ioDispatcher) {
                EstadoKanji(
                    info = diccionario.buscarKanji(kanji),
                    ejemplos = diccionario.oracionesDeKanji(kanji, limite = 5),
                    cargando = false,
                )
            }
        }
    }
}
