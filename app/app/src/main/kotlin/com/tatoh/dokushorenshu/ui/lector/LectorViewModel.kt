package com.tatoh.dokushorenshu.ui.lector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.Historia
import com.tatoh.dokushorenshu.datos.Oracion
import com.tatoh.dokushorenshu.datos.progreso.PalabraTocada
import com.tatoh.dokushorenshu.datos.progreso.PrefsRepo
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDao
import com.tatoh.dokushorenshu.datos.progreso.ProgresoHistoria
import com.tatoh.dokushorenshu.dominio.BuscadorPalabras
import com.tatoh.dokushorenshu.dominio.ConsultaPalabra
import com.tatoh.dokushorenshu.dominio.PalabraToken
import com.tatoh.dokushorenshu.dominio.Tokenizador
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OracionPlana(
    val parrafo: Int,
    val oracionEnParrafo: Int,
    val oracion: Oracion,
    val tokens: List<PalabraToken>,
)

data class EstadoLector(
    val titulo: String = "",
    val oraciones: List<OracionPlana> = emptyList(),
    val indiceActual: Int = 0,
    val furiganaActiva: Boolean = true,
    val consulta: ConsultaPalabra? = null,
)

/** Resultado intermedio de cargar(): agrupa lo leído en IO antes de publicar el estado. */
private data class DatosCarga(
    val historia: Historia,
    val planas: List<OracionPlana>,
    val furiganaActiva: Boolean,
    val progreso: ProgresoHistoria?,
)

class LectorViewModel(
    private val idHistoria: String,
    private val historiasRepo: HistoriasRepo,
    private val progresoDao: ProgresoDao,
    private val prefs: PrefsRepo,
    private val tokenizador: Tokenizador,
    private val buscador: BuscadorPalabras,
    // Inyectable solo para tests (mismo patrón que BibliotecaViewModel/HistoriasRepo):
    // en producción siempre Dispatchers.IO, nunca el hilo principal. cargarHistoria() es
    // I/O bloqueante síncrono, así que SIEMPRE debe correr fuera del hilo principal.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoLector())
    val estado: StateFlow<EstadoLector> = _estado

    fun cargar() {
        viewModelScope.launch {
            val datos = withContext(ioDispatcher) {
                val historia = historiasRepo.cargarHistoria(idHistoria)
                if (historia == null) {
                    null
                } else {
                    val planas = historia.parrafos.flatMapIndexed { p, parrafo ->
                        parrafo.oraciones.mapIndexed { o, oracion ->
                            OracionPlana(p, o, oracion, tokenizador.tokenizar(oracion.texto))
                        }
                    }
                    DatosCarga(historia, planas, prefs.furiganaActiva(), progresoDao.progreso(idHistoria))
                }
            }
            // La historia puede haber sido borrada o corrompida entre que se listó en la
            // biblioteca y que se abrió acá: nunca crashear: degradar a un estado vacío visible.
            if (datos == null) {
                _estado.value = EstadoLector(titulo = "Historia no disponible")
                return@launch
            }
            val indice = datos.progreso?.let { guardado ->
                datos.planas.indexOfFirst {
                    it.parrafo == guardado.parrafo && it.oracionEnParrafo == guardado.oracion
                }.takeIf { it >= 0 }
            } ?: 0
            _estado.value = EstadoLector(
                titulo = datos.historia.titulo,
                oraciones = datos.planas,
                indiceActual = indice,
                furiganaActiva = datos.furiganaActiva,
            )
        }
    }

    fun avanzar() = mover(+1)
    fun retroceder() = mover(-1)

    private fun mover(delta: Int) {
        val estado = _estado.value
        if (estado.oraciones.isEmpty()) return
        val nuevo = (estado.indiceActual + delta).coerceIn(0, estado.oraciones.lastIndex)
        if (nuevo == estado.indiceActual) return
        _estado.value = estado.copy(indiceActual = nuevo)
        val plana = estado.oraciones[nuevo]
        viewModelScope.launch(ioDispatcher) {
            progresoDao.guardarProgreso(
                ProgresoHistoria(idHistoria, plana.parrafo, plana.oracionEnParrafo),
            )
        }
    }

    fun alternarFurigana() {
        val nueva = !_estado.value.furiganaActiva
        _estado.value = _estado.value.copy(furiganaActiva = nueva)
        viewModelScope.launch(ioDispatcher) { prefs.setFuriganaActiva(nueva) }
    }

    fun tocarPalabra(token: PalabraToken) {
        viewModelScope.launch {
            val consulta = withContext(ioDispatcher) {
                progresoDao.registrarPalabra(
                    PalabraTocada(idHistoria, token.superficie, System.currentTimeMillis()),
                )
                buscador.consultar(token)
            }
            _estado.value = _estado.value.copy(consulta = consulta)
        }
    }

    fun cerrarSheet() {
        _estado.value = _estado.value.copy(consulta = null)
    }
}
