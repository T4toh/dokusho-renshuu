package com.tatoh.dokushorenshu.ui.recortes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tatoh.dokushorenshu.datos.Recorte
import com.tatoh.dokushorenshu.datos.RecortesRepo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mismo patrón que BibliotecaViewModel: MutableStateFlow privado + StateFlow
 *  público, I/O de disco fuera del main thread. */
class RecortesViewModel(
    private val recortesRepo: RecortesRepo,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _recortes = MutableStateFlow<List<Recorte>>(emptyList())
    val recortes: StateFlow<List<Recorte>> = _recortes

    fun cargar() {
        viewModelScope.launch {
            _recortes.value = withContext(ioDispatcher) { recortesRepo.listar() }
        }
    }

    fun borrar(id: String) {
        viewModelScope.launch {
            withContext(ioDispatcher) { recortesRepo.borrar(id) }
            cargar()
        }
    }
}
