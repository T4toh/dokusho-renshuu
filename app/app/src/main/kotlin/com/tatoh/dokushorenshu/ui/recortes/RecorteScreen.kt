package com.tatoh.dokushorenshu.ui.recortes

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tatoh.dokushorenshu.dominio.ocr.Recorte as RectanguloOcr
import com.tatoh.dokushorenshu.ui.comun.BarraSeleccion
import com.tatoh.dokushorenshu.ui.comun.ItemOracion
import com.tatoh.dokushorenshu.ui.comun.buscarEnWeb
import com.tatoh.dokushorenshu.ui.lector.PalabraSheet
import java.io.File

/** Vista de un recorte: NO es el lector paginado. Un recorte son tres líneas de manga,
 *  así que no hay portada, ni Previous/Next, ni número de oración, ni atenuado por foco
 *  (todas las oraciones van con esActual = true): un único bloque continuo, ordenado,
 *  donde se pueda tocar cualquier palabra y leer el furigana cómodo.
 *
 *  La imagen original arranca COLAPSADA porque el punto de la nota es el texto: la foto
 *  es la salida de emergencia para cuando el OCR salió torcido. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorteScreen(vm: RecorteViewModel, onVerKanji: (String) -> Unit, onCerrar: () -> Unit) {
    val estado by vm.estado.collectAsState()
    val estadoSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val contexto = LocalContext.current
    val portapapeles = LocalClipboardManager.current
    var confirmarQuitarImagen by remember { mutableStateOf(false) }
    // Tamaño en px del área donde se DIBUJA la imagen: es el sistema de coordenadas
    // del recuadro, y lo necesita la app bar para poder mandar Scan.
    var imagenDibujada by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(Unit) { vm.cargar() }

    // Toast y no Snackbar: es el mismo aviso que usa MainActivity cuando falla la
    // creación del recorte, y sobrevive a que esta pantalla se recomponga entera.
    // El borrador sigue en pantalla: el ViewModel no lo descarta ante un fallo.
    LaunchedEffect(estado.error) {
        estado.error?.let { mensaje ->
            Toast.makeText(contexto, mensaje, Toast.LENGTH_LONG).show()
            vm.errorMostrado()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                actions = {
                    if (estado.modoRecorte) {
                        // Scan/Cancel van acá y no debajo de la imagen: una captura de
                        // pantalla completa dibujada a ancho completo mide más que el
                        // viewport, así que unos botones al pie de la foto quedaban
                        // literalmente fuera de alcance — y en modo recorte el arrastre se
                        // come el scroll de la lista, así que no había forma de bajar.
                        TextButton(
                            onClick = { vm.escanearSeleccion(imagenDibujada.width, imagenDibujada.height) },
                            enabled = estado.seleccionImagen != null,
                        ) { Text("Scan") }
                        TextButton(onClick = vm::cancelarRecorte) { Text("Cancel") }
                    } else if (estado.editando) {
                        TextButton(
                            onClick = vm::guardarEdicion,
                            enabled = estado.textoEditado.isNotBlank(),
                        ) { Text("Save") }
                        TextButton(onClick = vm::cancelarEdicion) { Text("Cancel") }
                    } else {
                        if (estado.recorte != null) {
                            TextButton(onClick = vm::empezarEdicion) { Text("Edit") }
                        }
                        if (estado.imagen != null) {
                            TextButton(onClick = { confirmarQuitarImagen = true }) { Text("Remove image") }
                        }
                        TextButton(onClick = onCerrar) { Text("Close") }
                    }
                },
            )
        },
        bottomBar = {
            // Solo existe mientras hay selección activa: sin ella esta pantalla no tiene
            // barra inferior (no hay Previous/Next que reemplazar, como en el lector).
            estado.textoSeleccionado?.let { seleccionado ->
                BarraSeleccion(
                    texto = seleccionado,
                    onBuscarWeb = { buscarEnWeb(contexto, seleccionado) },
                    onCopiar = {
                        portapapeles.setText(AnnotatedString(seleccionado))
                        vm.limpiarSeleccion()
                    },
                    onCancelar = vm::limpiarSeleccion,
                )
            }
        },
    ) { relleno ->
        // Recorte borrado desde la lista con esta pantalla abierta: no crashear,
        // degradar a un mensaje visible (mismo criterio que LectorScreen).
        if (estado.cargado && estado.recorte == null) {
            Box(Modifier.fillMaxSize().padding(relleno), contentAlignment = Alignment.Center) {
                Text(
                    "Note not available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(relleno)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 700.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            estado.imagen?.let { archivo ->
                item {
                    Miniatura(
                        archivo = archivo,
                        expandida = estado.imagenExpandida,
                        modoRecorte = estado.modoRecorte,
                        seleccion = estado.seleccionImagen,
                        onAlternar = vm::alternarImagen,
                        onEmpezarRecorte = vm::empezarRecorte,
                        onSeleccion = vm::setSeleccionImagen,
                        onTamanoDibujado = { imagenDibujada = it },
                    )
                }
            }
            if (estado.editando) {
                item {
                    OutlinedTextField(
                        value = estado.textoEditado,
                        onValueChange = vm::setTextoEditado,
                        label = { Text("Text") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                itemsIndexed(estado.planas, key = { indice, _ -> indice }) { indice, plana ->
                    ItemOracion(
                        // Siempre true: el atenuado por foco es del lector paginado; acá
                        // no hay oración "actual" que valga la pena distinguir.
                        esActual = true,
                        plana = plana,
                        furiganaActiva = true,
                        katakanaActiva = true,
                        onTapPalabra = { token -> vm.tapPalabra(indice, token) },
                        onLongPressPalabra = { token -> vm.iniciarSeleccion(indice, token) },
                        // rango de selección SOLO si pertenece a esta oración (mismo
                        // criterio que en el lector, ver doc de ItemOracion): así un
                        // cambio de selección solo recompone los items cuyo param cambió.
                        rangoSeleccion = estado.seleccion
                            ?.takeIf { it.indiceOracion == indice }
                            ?.let { it.inicio until it.fin },
                    )
                }
            }
        }
    }

    estado.consulta?.let { consulta ->
        ModalBottomSheet(onDismissRequest = vm::cerrarSheet, sheetState = estadoSheet) {
            PalabraSheet(consulta, onVerKanji)
        }
    }

    if (confirmarQuitarImagen) {
        AlertDialog(
            onDismissRequest = { confirmarQuitarImagen = false },
            title = { Text("Remove image?") },
            text = { Text("The note keeps its text. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmarQuitarImagen = false
                    vm.quitarImagen()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmarQuitarImagen = false }) { Text("Cancel") }
            },
        )
    }
}

/** Arrastre menor a esto no cuenta como recuadro. Mismo umbral que el overlay de
 *  captura (`SelectionOverlayView.getSelectionRect`): abajo de eso es un toque, no una
 *  selección, y un rectángulo de 2 px no tiene nada adentro para reconocer. */
private const val UMBRAL_ARRASTRE = 10

/** Oscurecido de lo que queda FUERA del recuadro. Mismo papel que el fondo del overlay
 *  de captura: que se vea qué entra y qué no. */
private val VELO = Color.Black.copy(alpha = 0.45f)

/** Fila "Image" + chevron que despliega el JPEG original, y encima el modo recorte:
 *  arrastrar un recuadro sobre la foto para re-correr el OCR de ese pedazo.
 *
 *  El bitmap se decodifica DENTRO de la rama expandida a propósito: colapsado no ocupa
 *  memoria (una captura de pantalla completa son varios MB descomprimida), que es
 *  justamente para lo que sirve que arranque colapsada. Sin librería de imágenes: es un
 *  archivo local y una sola foto por pantalla. decodeFile devuelve null si el archivo
 *  está corrupto — ahí simplemente no se dibuja nada.
 *
 *  El recuadro se mide en píxeles del área DIBUJADA (`onSizeChanged`), no del bitmap: la
 *  imagen va con ContentScale.FillWidth, así que los dos tamaños difieren y el escalado
 *  lo hace el recortador con `escalarRecorte` — el mismo camino, y los mismos 8 tests,
 *  que el recorte de la captura. */
@Composable
private fun Miniatura(
    archivo: File,
    expandida: Boolean,
    modoRecorte: Boolean,
    seleccion: RectanguloOcr?,
    onAlternar: () -> Unit,
    onEmpezarRecorte: () -> Unit,
    onSeleccion: (RectanguloOcr?) -> Unit,
    onTamanoDibujado: (IntSize) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // El clickable va en el texto y no en la Row entera: la Row ahora also
            // contiene el botón de recorte, y colapsar la imagen al tocarlo sería
            // exactamente lo contrario de lo que el usuario pidió.
            Row(
                Modifier.clickable(onClick = onAlternar),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Image", style = MaterialTheme.typography.labelLarge)
                Text(if (expandida) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
            }
            if (expandida && !modoRecorte) {
                TextButton(onClick = onEmpezarRecorte) { Text("Rescan area") }
            }
        }
        if (!expandida) return@Column
        val bitmap = remember(archivo) { BitmapFactory.decodeFile(archivo.path) } ?: return@Column

        var ancla by remember { mutableStateOf(Offset.Zero) }

        // En modo recorte la imagen se achica hasta entrar ENTERA en pantalla: si se
        // dibuja a ancho completo, una captura de pantalla queda más alta que el viewport
        // y la mitad de abajo no se puede ni ver ni recortar. Se limita el alto y se fija
        // la proporción del bitmap, así el área dibujada sigue siendo exactamente el Box
        // —sin bandas negras— y el recuadro mapea 1 a 1 contra lo que se ve. El OCR corre
        // igual sobre el bitmap original en resolución completa: la vista chica no le
        // quita calidad.
        val aspecto = bitmap.width.toFloat() / bitmap.height
        val medida = if (modoRecorte) {
            Modifier.heightIn(max = 440.dp).aspectRatio(aspecto, matchHeightConstraintsFirst = true)
        } else {
            Modifier.fillMaxWidth()
        }

        Box(medida.onSizeChanged(onTamanoDibujado)) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured image",
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.FillBounds,
            )
            if (modoRecorte) {
                Canvas(
                    Modifier
                        .matchParentSize()
                        // pointerInput keyeado en modoRecorte: si la key fuera Unit, el
                        // gesto quedaría registrado con el callback de la composición
                        // vieja al salir y volver a entrar al modo.
                        .pointerInput(modoRecorte) {
                            detectDragGestures(
                                onDragStart = { inicio ->
                                    ancla = inicio
                                    onSeleccion(null)
                                },
                                onDrag = { cambio, _ ->
                                    // Consumido para que el drag no se lo lleve el scroll
                                    // del LazyColumn de arriba.
                                    cambio.consume()
                                    onSeleccion(recuadroEntre(ancla, cambio.position))
                                },
                            )
                        },
                ) {
                    val r = seleccion
                    if (r == null) {
                        drawRect(VELO)
                        return@Canvas
                    }
                    // El velo se dibuja en cuatro pedazos ALREDEDOR del recuadro en vez de
                    // taparlo entero y "borrar" el centro con BlendMode.Clear: Clear sólo
                    // hace lo que uno espera dentro de una capa de composición propia, y
                    // sin ella se come también la imagen de abajo. Cuatro rectángulos no
                    // dependen de nada.
                    val izquierda = r.left.toFloat()
                    val arriba = r.top.toFloat()
                    val derecha = izquierda + r.ancho
                    val abajo = arriba + r.alto
                    drawRect(VELO, Offset.Zero, Size(size.width, arriba))
                    drawRect(VELO, Offset(0f, abajo), Size(size.width, size.height - abajo))
                    drawRect(VELO, Offset(0f, arriba), Size(izquierda, abajo - arriba))
                    drawRect(VELO, Offset(derecha, arriba), Size(size.width - derecha, abajo - arriba))
                    drawRect(
                        Color.White,
                        Offset(izquierda, arriba),
                        Size(r.ancho.toFloat(), r.alto.toFloat()),
                        style = Stroke(width = 3f),
                    )
                }
            }
        }
        if (modoRecorte) {
            Text(
                "Drag a box over the text, then tap Scan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Dos puntos de la pantalla → recuadro normalizado, o null si el arrastre fue tan corto
 *  que no puede haber texto adentro. */
private fun recuadroEntre(desde: Offset, hasta: Offset): RectanguloOcr? {
    val left = minOf(desde.x, hasta.x).toInt()
    val top = minOf(desde.y, hasta.y).toInt()
    val ancho = (maxOf(desde.x, hasta.x).toInt() - left)
    val alto = (maxOf(desde.y, hasta.y).toInt() - top)
    return if (ancho > UMBRAL_ARRASTRE && alto > UMBRAL_ARRASTRE) {
        RectanguloOcr(left = left, top = top, ancho = ancho, alto = alto)
    } else {
        null
    }
}
