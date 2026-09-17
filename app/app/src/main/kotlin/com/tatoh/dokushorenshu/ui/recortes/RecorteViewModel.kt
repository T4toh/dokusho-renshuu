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
    val cargado: Boolean = false,
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

    /** Idempotente a propósito: la Screen la dispara con LaunchedEffect(Unit), que vuelve
     *  a correr al rotar (la composición se recrea, el ViewModel no). Recargar ahí
     *  pisaría una edición en curso con el texto de disco. */
    fun cargar() {
        if (_estado.value.cargado) return
        viewModelScope.launch {
            _estado.value = withContext(ioDispatcher) {
                val recorte = recortesRepo.cargar(id)
                EstadoRecorte(
                    recorte = recorte,
                    planas = recorte?.let { aplanar(it.parrafos, tokenizador) }.orEmpty(),
                    imagen = recortesRepo.archivoImagen(id),
                    cargado = true,
                )
            }
        }
    }

    fun tocarPalabra(token: PalabraToken) {
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
        _estado.value = estado.copy(editando = true, textoEditado = recorte.texto)
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
            )
        }
    }
}
