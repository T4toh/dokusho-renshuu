package com.tatoh.dokushorenshu.ui.biblioteca

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tatoh.dokushorenshu.datos.EntradaCatalogo
import com.tatoh.dokushorenshu.datos.Historia
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ItemBiblioteca(val historia: Historia, val progresoPct: Int)

sealed interface EstadoCatalogo {
    data object Cargando : EstadoCatalogo
    data class Error(val mensaje: String) : EstadoCatalogo
    data class Ok(val disponibles: List<EntradaCatalogo>) : EstadoCatalogo
}

class BibliotecaViewModel(
    private val historiasRepo: HistoriasRepo,
    private val progresoDao: ProgresoDao,
    // Inyectable solo para tests (ver HistoriasRepo.ioDispatcher): en producción siempre
    // Dispatchers.IO, nunca el hilo principal.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _locales = MutableStateFlow<List<ItemBiblioteca>>(emptyList())
    val locales: StateFlow<List<ItemBiblioteca>> = _locales

    private val _catalogo = MutableStateFlow<EstadoCatalogo>(EstadoCatalogo.Cargando)
    val catalogo: StateFlow<EstadoCatalogo> = _catalogo

    fun cargar() {
        viewModelScope.launch {
            _locales.value = withContext(ioDispatcher) {
                historiasRepo.historiasLocales().map { historia ->
                    val progreso = progresoDao.progreso(historia.id)
                    val pct = if (progreso == null || historia.parrafos.isEmpty()) 0
                    else (progreso.parrafo * 100) / historia.parrafos.size
                    ItemBiblioteca(historia, pct)
                }
            }
            refrescarCatalogo()
        }
    }

    fun refrescarCatalogo() {
        viewModelScope.launch {
            _catalogo.value = EstadoCatalogo.Cargando
            val idsLocales = _locales.value.map { it.historia.id }.toSet()
            _catalogo.value = historiasRepo.catalogoRemoto().fold(
                onSuccess = { catalogo ->
                    EstadoCatalogo.Ok(catalogo.historias.filter { it.id !in idsLocales })
                },
                onFailure = { EstadoCatalogo.Error("No se pudo cargar el catálogo") },
            )
        }
    }

    fun descargar(id: String) {
        viewModelScope.launch {
            historiasRepo.descargarHistoria(id)
                .onSuccess { cargar() }
                .onFailure { _catalogo.value = EstadoCatalogo.Error("Descarga fallida: $id") }
        }
    }
}
