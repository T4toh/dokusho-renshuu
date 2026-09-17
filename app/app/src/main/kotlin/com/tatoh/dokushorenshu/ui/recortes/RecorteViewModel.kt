package com.tatoh.dokushorenshu.ui.recortes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tatoh.dokushorenshu.datos.Recorte
import com.tatoh.dokushorenshu.datos.RecortesRepo
import com.tatoh.dokushorenshu.datos.progreso.PalabraTocada
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDao
import com.tatoh.dokushorenshu.dominio.BuscadorPalabras
import com.tatoh.dokushorenshu.dominio.ConsultaPalabra
import com.tatoh.dokushorenshu.dominio.CreadorRecortes
import com.tatoh.dokushorenshu.dominio.PalabraToken
import com.tatoh.dokushorenshu.dominio.Tokenizador
import com.tatoh.dokushorenshu.ui.comun.OracionPlana
import com.tatoh.dokushorenshu.ui.comun.aplanar
import com.tatoh.dokushorenshu.ui.lector.SeleccionTexto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** [cargado] distingue "todavía cargando" de "el recorte no existe" (se borró desde la
 *  lista con esta pantalla abierta): sin él, el estado inicial vacío y el degradado son
 *  indistinguibles.
 *
 *  [imagen] es el `.jpg` resuelto contra el disco, no el flag [Recorte.tieneImagen]: un
 *  flag en true con el archivo ausente es un estado NORMAL (el usuario borró el archivo
 *  por fuera), no un error, y así la miniatura simplemente no se ofrece.
 *
 *  Modo edición y miniatura expandida viven acá, en el ViewModel, y no en la composición:
 *  así rotar la pantalla no descarta lo que el usuario venía tipeando. */
data class EstadoRecorte(
    val recorte: Recorte? = null,
    val planas: List<OracionPlana> = emptyList(),
    val imagen: File? = null,
    val editando: Boolean = false,
    val textoEditado: String = "",
    val imagenExpandida: Boolean = false,
    val consulta: ConsultaPalabra? = null,
    val seleccion: SeleccionTexto? = null,
    val cargado: Boolean = false,
) {
    val textoSeleccionado: String? get() = seleccion?.let { s ->
        planas.getOrNull(s.indiceOracion)?.oracion?.texto?.substring(s.inicio, s.fin)
    }
}

/** Resultado intermedio de cargar(): lo que sale del disco, antes de mezclarlo con el
 *  estado vivo (que puede tener una edición a medio tipear). */
private data class DatosRecorte(
    val recorte: Recorte?,
    val planas: List<OracionPlana>,
    val imagen: File?,
)

class RecorteViewModel(
    private val id: String,
    private val recortesRepo: RecortesRepo,
    private val creadorRecortes: CreadorRecortes,
    private val tokenizador: Tokenizador,
    private val buscador: BuscadorPalabras,
    private val progresoDao: ProgresoDao,
    // Inyectable solo para tests (mismo patrón que LectorViewModel): en producción
    // siempre Dispatchers.IO — aplanar() bloquea en Kuromoji.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoRecorte())
    val estado: StateFlow<EstadoRecorte> = _estado

    /** NUNCA puede pisar un borrador en curso, y eso es por construcción, no por suerte:
     *  solo escribe los campos que salen del disco (recorte/planas/imagen/cargado) vía
     *  copy() sobre el estado vigente, así `editando` y `textoEditado` sobreviven aunque
     *  alguien agregue mañana un refresh forzado. Importa porque la Screen la dispara con
     *  LaunchedEffect(Unit), que vuelve a correr al rotar: la composición se recrea, el
     *  ViewModel no. El corte por `cargado` es aparte y es solo de costo — evita repetir
     *  el aplanar() de Kuromoji en cada rotación. */
    fun cargar() {
        if (_estado.value.cargado) return
        viewModelScope.launch {
            val datos = withContext(ioDispatcher) {
                val recorte = recortesRepo.cargar(id)
                DatosRecorte(
                    recorte = recorte,
                    planas = recorte?.let { aplanar(it.parrafos, tokenizador) }.orEmpty(),
                    imagen = recortesRepo.archivoImagen(id),
                )
            }
            _estado.value = _estado.value.copy(
                recorte = datos.recorte,
                planas = datos.planas,
                imagen = datos.imagen,
                cargado = true,
            )
        }
    }

    /** Long-press: ancla (o re-ancla) la selección en ese token. No abre el diccionario
     *  — eso es del tap simple. Misma mecánica que el lector, sin el enfocar(): acá no
     *  hay oración "actual" que mover. */
    fun iniciarSeleccion(indice: Int, token: PalabraToken) {
        if (indice !in _estado.value.planas.indices) return
        _estado.value = _estado.value.copy(seleccion = SeleccionTexto(indice, token.inicio, token.fin))
    }

    /** Tap sobre un token: con selección activa en la MISMA oración extiende el rango a
     *  la unión [min(inicio), max(fin)] en vez de abrir el diccionario; en cualquier otro
     *  caso (sin selección, o en otra oración) limpia la selección vieja y consulta.
     *  Idéntico a LectorViewModel.tapPalabra, salvo que allá el limpiado lo hacía
     *  enfocar() de paso. */
    fun tapPalabra(indice: Int, token: PalabraToken) {
        val seleccion = _estado.value.seleccion
        if (seleccion != null && seleccion.indiceOracion == indice) {
            _estado.value = _estado.value.copy(
                seleccion = seleccion.copy(
                    inicio = minOf(seleccion.inicio, token.inicio),
                    fin = maxOf(seleccion.fin, token.fin),
                ),
            )
            return
        }
        _estado.value = _estado.value.copy(seleccion = null)
        tocarPalabra(token)
    }

    fun limpiarSeleccion() {
        _estado.value = _estado.value.copy(seleccion = null)
    }

    private fun tocarPalabra(token: PalabraToken) {
        viewModelScope.launch {
            val consulta = withContext(ioDispatcher) {
                // El idHistoria que se registra es "recorte:$id", y el literal NO se puede
                // tocar: es lo único que separa el mazo de los recortes del de las historias
                // (la exportación filtra con LIKE 'recorte:%'). Los dos puntos tampoco son
                // arbitrarios: ImportadorHistoria.generarId() sanea los títulos con
                // [\/:*?"<>|.\s　]+ → "_", así que un id de historia NUNCA puede contener
                // ':' — con un '-' habría colisión, porque una historia titulada
                // "recorte-123" produce justamente el id "recorte-123". Si este prefijo
                // cambia, el filtro deja de matchear EN SILENCIO y el vocabulario de los
                // recortes se cuela en el mazo equivocado.
                //
                // Se guarda la forma de diccionario y no la superficie conjugada, por el
                // mismo motivo que en LectorViewModel.tocarPalabra().
                progresoDao.registrarPalabra(
                    PalabraTocada("recorte:$id", token.formaBase ?: token.superficie, System.currentTimeMillis()),
                )
                buscador.consultar(token)
            }
            _estado.value = _estado.value.copy(consulta = consulta)
        }
    }

    fun cerrarSheet() {
        _estado.value = _estado.value.copy(consulta = null)
    }

    fun alternarImagen() {
        _estado.value = _estado.value.copy(imagenExpandida = !_estado.value.imagenExpandida)
    }

    fun quitarImagen() {
        viewModelScope.launch {
            withContext(ioDispatcher) { recortesRepo.quitarImagen(id) }
            val estado = _estado.value
            _estado.value = estado.copy(
                recorte = estado.recorte?.copy(tieneImagen = false),
                imagen = null,
                imagenExpandida = false,
            )
        }
    }

    fun empezarEdicion() {
        val estado = _estado.value
        val recorte = estado.recorte ?: return
        // La selección se limpia al entrar y al salir de la edición: sus offsets apuntan
        // a oraciones que el TextField oculta y que guardarEdicion() regenera desde cero.
        _estado.value = estado.copy(editando = true, textoEditado = recorte.texto, seleccion = null)
    }

    fun cancelarEdicion() {
        _estado.value = _estado.value.copy(editando = false, textoEditado = "")
    }

    fun setTextoEditado(texto: String) {
        _estado.value = _estado.value.copy(textoEditado = texto)
    }

    /** Regenera párrafos y furigana desde el texto crudo y persiste. Texto en blanco se
     *  ignora: CreadorRecortes exige al menos un párrafo, y un recorte vacío no es nada
     *  que el usuario quiera guardar. */
    fun guardarEdicion() {
        val estado = _estado.value
        val recorte = estado.recorte ?: return
        val texto = estado.textoEditado
        if (texto.isBlank()) return
        viewModelScope.launch {
            val guardado = withContext(ioDispatcher) {
                val nuevo = creadorRecortes.editar(recorte, texto)
                nuevo to aplanar(nuevo.parrafos, tokenizador)
            }
            _estado.value = _estado.value.copy(
                recorte = guardado.first,
                planas = guardado.second,
                editando = false,
                textoEditado = "",
                seleccion = null,
            )
        }
    }
}
