package com.tatoh.dokushorenshu.ui.recortes

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.tatoh.dokushorenshu.ui.comun.ItemOracion
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
    var confirmarQuitarImagen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.cargar() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                actions = {
                    if (estado.editando) {
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
                item { Miniatura(archivo, estado.imagenExpandida, vm::alternarImagen) }
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
                items(estado.planas, key = { "${it.parrafo}:${it.oracionEnParrafo}" }) { plana ->
                    ItemOracion(
                        // Siempre true: el atenuado por foco es del lector paginado; acá
                        // no hay oración "actual" que valga la pena distinguir.
                        esActual = true,
                        plana = plana,
                        furiganaActiva = true,
                        katakanaActiva = true,
                        onTapPalabra = vm::tocarPalabra,
                        // Sin selección libre de rangos: eso es del lector, donde hay
                        // oraciones largas. Acá el texto entero entra en pantalla.
                        onLongPressPalabra = {},
                        rangoSeleccion = null,
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

/** Fila "Image" + chevron que despliega el JPEG original.
 *
 *  El bitmap se decodifica DENTRO de la rama expandida a propósito: colapsado no ocupa
 *  memoria (una captura de pantalla completa son varios MB descomprimida), que es
 *  justamente para lo que sirve que arranque colapsada. Sin librería de imágenes: es un
 *  archivo local y una sola foto por pantalla. decodeFile devuelve null si el archivo
 *  está corrupto — ahí simplemente no se dibuja nada. */
@Composable
private fun Miniatura(archivo: File, expandida: Boolean, onAlternar: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onAlternar).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Image", style = MaterialTheme.typography.labelLarge)
            Text(if (expandida) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
        }
        if (expandida) {
            val bitmap = remember(archivo) { BitmapFactory.decodeFile(archivo.path) }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Captured image",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}
