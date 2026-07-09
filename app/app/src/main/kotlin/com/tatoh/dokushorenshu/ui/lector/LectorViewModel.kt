package com.tatoh.dokushorenshu.ui.lector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tatoh.dokushorenshu.datos.EntradaCatalogo
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

/** indiceActual == -1 representa la portada (Task C3): título, autor, stats y
 *  botón Start/Continue reading. "Previous" desde la oración 0 vuelve acá. */
data class EstadoLector(
    val titulo: String = "",
    val autor: String = "",
    val metadata: EntradaCatalogo? = null,
    val oraciones: List<OracionPlana> = emptyList(),
    val indiceActual: Int = -1,
    val furiganaActiva: Boolean = true,
    val consulta: ConsultaPalabra? = null,
    // última posición persistida en progresoDao: a diferencia de indiceActual, no vuelve
    // a -1 al retroceder hasta la portada, así la portada puede distinguir "Continue
    // reading" (hay progreso guardado) de "Start reading" (nunca se avanzó).
    val progresoGuardado: Int = -1,
    // Contador de centrados EXPLÍCITOS pedidos (Plan 3.6 Task 2 fix — rubberbanding):
    // solo mover() (Previous/Next, incluido el salto Start/Continue desde la portada) y
    // cargar() lo incrementan. enfocar() (foco por asentado de scroll libre o tap sobre
    // una oración no-actual) NUNCA lo toca. LectorScreen solo dispara el auto-scroll de
    // centrado cuando este contador cambia, así el centrado tras soltar el dedo (que ya
    // queda centrado por el snap del fling) no dispara una SEGUNDA corrección — esa
    // doble corrección era el "rubberbanding" reportado.
    val centradoPedido: Int = 0,
) {
    val enPortada: Boolean get() = indiceActual == -1
    val porcentajeLeido: Int get() =
        if (oraciones.isEmpty()) 0 else ((indiceActual.coerceAtLeast(0) + 1) * 100) / oraciones.size
}

/** Resultado intermedio de cargar(): agrupa lo leído en IO antes de publicar el estado. */
private data class DatosCarga(
    val historia: Historia,
    val metadata: EntradaCatalogo?,
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
                val historia = historiasRepo.cargarHistoria(idHistoria) ?: return@withContext null
                val metadata = historiasRepo.catalogoLocal()?.historias?.firstOrNull { it.id == idHistoria }
                val planas = historia.parrafos.flatMapIndexed { p, parrafo ->
                    parrafo.oraciones.mapIndexed { o, oracion ->
                        OracionPlana(p, o, oracion, tokenizador.tokenizar(oracion.texto))
                    }
                }
                DatosCarga(historia, metadata, planas, prefs.furiganaActiva(), progresoDao.progreso(idHistoria))
            }
            // La historia puede haber sido borrada o corrompida entre que se listó en la
            // biblioteca y que se abrió acá: nunca crashear: degradar a un estado vacío visible.
            if (datos == null) {
                _estado.value = EstadoLector(titulo = "Story not available")
                return@launch
            }
            // Sin progreso guardado: arranca en la portada (-1). Con progreso, restaura
            // la oración exacta; si no se encuentra (historia editada), vuelve a 0.
            val indice = datos.progreso?.let { guardado ->
                datos.planas.indexOfFirst {
                    it.parrafo == guardado.parrafo && it.oracionEnParrafo == guardado.oracion
                }.takeIf { it >= 0 } ?: 0
            } ?: -1
            _estado.value = EstadoLector(
                titulo = datos.historia.titulo,
                autor = datos.historia.autor,
                metadata = datos.metadata,
                oraciones = datos.planas,
                indiceActual = indice,
                furiganaActiva = datos.furiganaActiva,
                progresoGuardado = indice,
                // carga inicial: cuenta como centrado explícito para que, si esta carga
                // restaura una posición ya avanzada (no portada), la lista se abra
                // centrada en esa oración.
                centradoPedido = 1,
            )
        }
    }

    fun avanzar() = mover(+1)
    fun retroceder() = mover(-1)

    private fun mover(delta: Int) {
        val estado = _estado.value
        if (estado.oraciones.isEmpty()) return
        val nuevo = (estado.indiceActual + delta).coerceIn(-1, estado.oraciones.lastIndex)
        if (nuevo == estado.indiceActual) return
        _estado.value = estado.copy(
            indiceActual = nuevo,
            progresoGuardado = if (nuevo >= 0) nuevo else estado.progresoGuardado,
            centradoPedido = estado.centradoPedido + 1,
        )
        if (nuevo >= 0) {
            persistirProgreso(estado.oraciones[nuevo])
        }
    }

    /** Foco directo a un índice de oración (scroll libre estilo letras, Plan 3.6 Task 2):
     *  lo dispara tanto el asentado del scroll (oración más cercana al centro del
     *  viewport) como el tap sobre cualquier oración visible. A diferencia de mover(),
     *  recibe el índice absoluto en vez de un delta, y NUNCA apunta a la portada (-1):
     *  ese índice no forma parte de la lista de oraciones tappeable/scrolleable, solo
     *  Previous puede volver a la portada. Se ignora si el índice está fuera de rango
     *  o si el estado está degradado (oraciones vacías, historia no encontrada).
     *  A propósito NO toca [EstadoLector.centradoPedido]: este foco no debe disparar el
     *  auto-scroll de centrado en LectorScreen (ver doc de ese campo). */
    fun enfocar(indice: Int) {
        val estado = _estado.value
        if (estado.oraciones.isEmpty()) return
        if (indice < 0 || indice > estado.oraciones.lastIndex) return
        if (indice == estado.indiceActual) return
        _estado.value = estado.copy(indiceActual = indice, progresoGuardado = indice)
        persistirProgreso(estado.oraciones[indice])
    }

    private fun persistirProgreso(plana: OracionPlana) {
        viewModelScope.launch(ioDispatcher) {
            progresoDao.guardarProgreso(ProgresoHistoria(idHistoria, plana.parrafo, plana.oracionEnParrafo))
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
