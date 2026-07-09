package com.tatoh.dokushorenshu.ui.biblioteca

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tatoh.dokushorenshu.datos.Diccionario
import com.tatoh.dokushorenshu.datos.EntradaCatalogo
import com.tatoh.dokushorenshu.datos.Historia
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ItemBiblioteca(val historia: Historia, val progresoPct: Int, val metadata: EntradaCatalogo?)

sealed interface EstadoCatalogo {
    data object Cargando : EstadoCatalogo
    data class Error(val mensaje: String) : EstadoCatalogo
    data class Ok(val disponibles: List<EntradaCatalogo>) : EstadoCatalogo
}

/** Tarjeta de la sección Review del home: una por dificultad (easy/medium/hard). */
sealed interface TarjetaReview {
    val dificultad: String
    data class ConKanji(override val dificultad: String, val kanji: KanjiInfo, val lecturaPrincipal: String?) : TarjetaReview
    data class Vacia(override val dificultad: String) : TarjetaReview
}

private val DIFICULTADES = listOf("easy", "medium", "hard")
// cuántos candidatos taggeados probar antes de dar por vacía la tarjeta (kanjis
// borrados del db nuevo se saltean — query defensiva, spec "Manejo de errores").
private const val CANDIDATOS_POR_DIFICULTAD = 5

class BibliotecaViewModel(
    private val historiasRepo: HistoriasRepo,
    private val progresoDao: ProgresoDao,
    private val diccionario: Diccionario,
    // Inyectable solo para tests (ver HistoriasRepo.ioDispatcher): en producción siempre
    // Dispatchers.IO, nunca el hilo principal.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _locales = MutableStateFlow<List<ItemBiblioteca>>(emptyList())
    val locales: StateFlow<List<ItemBiblioteca>> = _locales

    private val _catalogo = MutableStateFlow<EstadoCatalogo>(EstadoCatalogo.Cargando)
    val catalogo: StateFlow<EstadoCatalogo> = _catalogo

    private val _review = MutableStateFlow<List<TarjetaReview>>(emptyList())
    val review: StateFlow<List<TarjetaReview>> = _review

    fun cargar() {
        viewModelScope.launch {
            _locales.value = withContext(ioDispatcher) {
                val catalogoLocal = historiasRepo.catalogoLocal()
                historiasRepo.historiasLocales().map { historia ->
                    val progreso = progresoDao.progreso(historia.id)
                    val pct = if (progreso == null || historia.parrafos.isEmpty()) 0
                    else (progreso.parrafo * 100) / historia.parrafos.size
                    val metadata = catalogoLocal?.historias?.firstOrNull { it.id == historia.id }
                    ItemBiblioteca(historia, pct, metadata)
                }
            }
            _review.value = withContext(ioDispatcher) { cargarReview() }
            refrescarCatalogo()
        }
    }

    private suspend fun cargarReview(): List<TarjetaReview> {
        val tarjetas = DIFICULTADES.map { dificultad ->
            val candidatos = progresoDao.kanjisPorDificultad(dificultad).take(CANDIDATOS_POR_DIFICULTAD)
            val encontrado = candidatos.firstNotNullOfOrNull { tocado ->
                diccionario.buscarKanji(tocado.kanji)?.let { info -> info }
            }
            if (encontrado == null) TarjetaReview.Vacia(dificultad)
            else TarjetaReview.ConKanji(
                dificultad, encontrado,
                lecturaPrincipal = encontrado.kunYomi.firstOrNull() ?: encontrado.onYomi.firstOrNull(),
            )
        }
        val hayAlgunTaggeado = DIFICULTADES.any { progresoDao.kanjisPorDificultad(it).isNotEmpty() }
        return if (hayAlgunTaggeado) tarjetas else emptyList()
    }

    fun refrescarCatalogo() {
        viewModelScope.launch {
            _catalogo.value = EstadoCatalogo.Cargando
            val idsLocales = _locales.value.map { it.historia.id }.toSet()
            _catalogo.value = historiasRepo.catalogoRemoto().fold(
                onSuccess = { catalogo ->
                    EstadoCatalogo.Ok(catalogo.historias.filter { it.id !in idsLocales })
                },
                onFailure = { EstadoCatalogo.Error("Couldn't load the catalog") },
            )
        }
    }

    fun descargar(id: String) {
        viewModelScope.launch {
            historiasRepo.descargarHistoria(id)
                .onSuccess { cargar() }
                .onFailure { _catalogo.value = EstadoCatalogo.Error("Download failed: $id") }
        }
    }
}
