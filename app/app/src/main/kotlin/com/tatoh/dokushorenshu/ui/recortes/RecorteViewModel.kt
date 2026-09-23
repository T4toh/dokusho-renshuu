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
import com.tatoh.dokushorenshu.dominio.ocr.RecortadorOcr
import com.tatoh.dokushorenshu.dominio.ocr.Recorte as RectanguloOcr
import com.tatoh.dokushorenshu.ui.comun.OracionPlana
import com.tatoh.dokushorenshu.ui.comun.SeleccionTexto
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
    val modoRecorte: Boolean = false,
    val seleccionImagen: RectanguloOcr? = null,
    val consulta: ConsultaPalabra? = null,
    val seleccion: SeleccionTexto? = null,
    val cargado: Boolean = false,
    // Mensaje de error pendiente de mostrar (Toast). Vive en el estado y no en un
    // canal aparte porque es el único aviso de esta pantalla y la Screen ya observa
    // este StateFlow; se limpia con errorMostrado() apenas se muestra.
    val error: String? = null,
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
    private val recortador: RecortadorOcr,
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
                // Se resetean porque apuntan a las planas VIEJAS, que esta línea acaba de
                // reemplazar: los offsets de `seleccion` quedarían fuera de rango y el
                // substring de textoSeleccionado tiraría IndexOutOfBounds. El lector se
                // salva de esto solo porque su cargar() arma un estado entero nuevo.
                seleccion = null,
                consulta = null,
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
            // runCatching adentro del withContext, igual que la creación en MainActivity:
            // quitarImagen() reescribe el JSON vía guardar(), que lanza si el rename o el
            // writeText fallan, y una excepción suelta en viewModelScope mata el proceso.
            val resultado = withContext(ioDispatcher) {
                runCatching {
                    recortesRepo.quitarImagen(id)
                    // Se RE-LEE el disco en vez de asumir que se borró: si delete() falló
                    // (quitarImagen devuelve false y lo loguea), el .jpg sigue ahí y la
                    // miniatura tiene que seguir ofreciéndose. Mostrarla como quitada sería
                    // mentir hasta que el usuario reabre la nota y la ve reaparecer.
                    recortesRepo.archivoImagen(id)
                }
            }
            val imagen = resultado.getOrElse {
                // Estado intacto a propósito: no se sabe si el .jpg sobrevivió, y mostrar
                // la miniatura como quitada sin haberla quitado es la mentira que el
                // chequeo de delete() en el repo existe para evitar.
                _estado.value = _estado.value.copy(error = "Could not remove the image")
                return@launch
            }
            val estado = _estado.value
            _estado.value = estado.copy(
                recorte = estado.recorte?.copy(tieneImagen = imagen != null),
                imagen = imagen,
                imagenExpandida = estado.imagenExpandida && imagen != null,
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
        // Limpia la selección igual que empezarEdicion() y guardarEdicion(): la regla es
        // "al entrar Y al salir de la edición", y de esa regla depende que los offsets de
        // seleccion siempre apunten a las planas vigentes.
        _estado.value = _estado.value.copy(editando = false, textoEditado = "", seleccion = null)
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
            // runCatching adentro del withContext, igual que la creación en MainActivity:
            // guardar() lanza si el rename atómico o el writeText fallan, y una excepción
            // suelta en viewModelScope mata el proceso entero.
            val guardado = withContext(ioDispatcher) {
                runCatching {
                    val nuevo = creadorRecortes.editar(recorte, texto)
                    nuevo to aplanar(nuevo.parrafos, tokenizador)
                }
            }
            guardado.onSuccess { (nuevo, planas) ->
                _estado.value = _estado.value.copy(
                    recorte = nuevo,
                    planas = planas,
                    editando = false,
                    textoEditado = "",
                    seleccion = null,
                )
            }
            guardado.onFailure {
                // El borrador NO se toca: ni se sale de `editando` ni se limpia
                // `textoEditado`. Este texto lo tipeó el usuario a mano y el disco es el
                // único lugar donde NO quedó, así que salir de la edición acá lo perdería
                // para siempre — justo lo que la spec (sección "Errores") prohíbe: "el
                // texto queda en pantalla, nunca se pierde en silencio". El aviso alcanza;
                // reintentar Save es del usuario.
                _estado.value = _estado.value.copy(error = "Could not save the note")
            }
        }
    }

    /** Persiste la edición en curso, si la hay, sin tocar el estado. La llama MainActivity
     *  cuando llega una captura nueva con este recorte abierto, justo antes de taparlo con
     *  el nuevo: el borrador (tipeado o re-escaneado) no puede perderse en silencio.
     *  Lanza si guardar() falla; el que llama decide no cerrar la pantalla. */
    suspend fun guardarPendiente() {
        val estado = _estado.value
        val recorte = estado.recorte ?: return
        val texto = estado.textoEditado
        if (!estado.editando || texto.isBlank() || texto == recorte.texto) return
        withContext(ioDispatcher) { creadorRecortes.editar(recorte, texto) }
    }

    /** La Screen avisa que ya mostró el Toast: sin esto el mensaje se repetiría en cada
     *  recomposición con key nueva (rotar, por ejemplo). */
    fun errorMostrado() {
        _estado.value = _estado.value.copy(error = null)
    }

    /** Entra al modo recorte sobre la imagen guardada. Expande la miniatura de paso: sin
     *  imagen a la vista no hay nada que recortar. Sin `.jpg` en disco no hace nada — la
     *  Screen tampoco ofrece la acción, pero el estado manda. */
    fun empezarRecorte() {
        val estado = _estado.value
        if (estado.imagen == null) return
        // La selección de TEXTO se limpia al entrar, misma regla que la edición: sus
        // offsets apuntan a las planas y el modo recorte tapa la lista con otra cosa.
        _estado.value = estado.copy(
            modoRecorte = true,
            imagenExpandida = true,
            seleccionImagen = null,
            seleccion = null,
        )
    }

    /** El recuadro que el usuario arrastró, en coordenadas del área DIBUJADA (la imagen
     *  se muestra con ContentScale.FillWidth, así que no coincide con el bitmap; el
     *  escalado es del recortador). null mientras no haya arrastrado nada. */
    fun setSeleccionImagen(seleccion: RectanguloOcr?) {
        _estado.value = _estado.value.copy(seleccionImagen = seleccion)
    }

    fun cancelarRecorte() {
        _estado.value = _estado.value.copy(modoRecorte = false, seleccionImagen = null)
    }

    /** Re-corre el OCR sobre el pedazo elegido de la imagen y deja el texto reconocido
     *  EN EL EDITOR, sin guardarlo: el texto viejo sobrevive en disco hasta que el
     *  usuario toque Save. Es la válvula para el texto chico y para el recorte que salió
     *  mal en la captura original, sin volver a la app de origen.
     *
     *  Sin recuadro no hace nada y no avisa: no es un error del usuario, todavía no
     *  eligió. Con recuadro pero sin texto sí avisa — ahí eligió y no salió nada.
     *
     *  runCatching adentro del withContext, igual que guardarEdicion(): reconocer()
     *  lanza si ML Kit se traba (timeout de 15 s) o si el .jpg no se puede decodificar,
     *  y una excepción suelta en viewModelScope mata el proceso. */
    fun escanearSeleccion(anchoDibujado: Int, altoDibujado: Int) {
        val estado = _estado.value
        val archivo = estado.imagen ?: return
        val seleccion = estado.seleccionImagen ?: return
        viewModelScope.launch {
            val resultado = withContext(ioDispatcher) {
                runCatching { recortador.reconocer(archivo, seleccion, anchoDibujado, altoDibujado) }
            }
            val texto = resultado.getOrElse {
                // Se queda en modo recorte con el recuadro puesto: reintentar es un tap.
                _estado.value = _estado.value.copy(error = "Could not scan the selection")
                return@launch
            }
            if (texto.isBlank()) {
                // Mismo texto que el aviso del Service al capturar un área vacía: es el
                // mismo hecho para el usuario, no conviene que suene distinto.
                _estado.value = _estado.value.copy(error = "No text found in the selected area")
                return@launch
            }
            _estado.value = _estado.value.copy(
                modoRecorte = false,
                seleccionImagen = null,
                editando = true,
                textoEditado = texto,
                seleccion = null,
            )
        }
    }
}
