package com.tatoh.dokushorenshu.ui.export

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(vm: ExportViewModel, onCerrar: () -> Unit) {
    val contadores by vm.contadores.collectAsState()
    val estado by vm.estado.collectAsState()
    val historiasStories by vm.historiasStories.collectAsState()
    val seleccionadas by vm.seleccionadas.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.cargar() }
    LaunchedEffect(estado) {
        val actual = estado
        if (actual is EstadoExport.Error) snackbarHostState.showSnackbar(actual.mensaje)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export") },
                navigationIcon = { TextButton(onClick = onCerrar) { Text("Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { relleno ->
        Column(Modifier.padding(relleno).padding(24.dp).fillMaxSize()) {
            Text(
                "${contadores.words} words · ${contadores.kanjisTaggeados} tagged kanji · ${contadores.historias} stories",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(24.dp))
            // FlowRow: los botones van en fila cuando el ancho alcanza (tablet,
            // landscape) y bajan de línea solos en angosto — sin ramas por tamaño
            // de pantalla. Los tres comparten ancho: el texto invisible con el
            // label más largo dentro de BotonExport define el intrínseco común.
            val tipoGenerando = (estado as? EstadoExport.Generando)?.tipo
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BotonExport(
                    titulo = "Export Words deck",
                    habilitado = contadores.words > 0,
                    hint = "Read and tap words first",
                    generandoEste = tipoGenerando == TipoExport.WORDS,
                    generandoOtro = tipoGenerando != null && tipoGenerando != TipoExport.WORDS,
                    onClick = { vm.exportar(TipoExport.WORDS) },
                    modifier = Modifier.width(IntrinsicSize.Max),
                )
                BotonExport(
                    titulo = "Export Kanji deck",
                    habilitado = contadores.kanjisTaggeados > 0,
                    hint = "Tag kanji as easy/medium/hard first",
                    generandoEste = tipoGenerando == TipoExport.KANJI,
                    generandoOtro = tipoGenerando != null && tipoGenerando != TipoExport.KANJI,
                    onClick = { vm.exportar(TipoExport.KANJI) },
                    modifier = Modifier.width(IntrinsicSize.Max),
                )
                BotonExport(
                    titulo = "Export Stories deck",
                    habilitado = contadores.historias > 0 && seleccionadas.isNotEmpty(),
                    hint = if (contadores.historias > 0) "Select at least one story" else "No local stories",
                    generandoEste = tipoGenerando == TipoExport.STORIES,
                    generandoOtro = tipoGenerando != null && tipoGenerando != TipoExport.STORIES,
                    onClick = { vm.exportar(TipoExport.STORIES) },
                    modifier = Modifier.width(IntrinsicSize.Max),
                )
            }

            if (historiasStories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // weight(1f, fill = false): la lista scrollea en el espacio del medio
                // sin empujar el bloque Exported/Share fuera de pantalla, y con pocas
                // historias no se estira (el bloque queda pegado a la lista).
                // Adaptive(300.dp): mismo minSize que la grilla de biblioteca — en
                // tablet rinde ~2 columnas portrait / ~3 landscape; en teléfono 1.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 300.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    items(historiasStories, key = { it.id }) { historia ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = historia.id in seleccionadas,
                                onCheckedChange = { vm.toggleHistoria(historia.id) },
                            )
                            Column {
                                Text(historia.titulo, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    detalleHistoria(historia.autor, historia.dificultad),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            val listo = estado as? EstadoExport.Listo
            if (listo != null) {
                Spacer(Modifier.height(24.dp))
                Text(listo.resumen, style = MaterialTheme.typography.bodyMedium)
                val context = LocalContext.current
                Button(
                    onClick = { compartirMazo(context, listo.archivo) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Share") }
            }
        }
    }
}

// El más largo de los 3 títulos de BotonExport — define el ancho común de los
// botones vía el texto invisible del Box. Si se agrega/cambia un título, revisar.
private const val TITULO_BOTON_MAS_LARGO = "Export Stories deck"

@Composable
private fun BotonExport(
    titulo: String,
    habilitado: Boolean,
    hint: String,
    generandoEste: Boolean,
    generandoOtro: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Button(
            onClick = onClick,
            enabled = habilitado && !generandoEste && !generandoOtro,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Mientras genera, el spinner REEMPLAZA al texto del botón tapeado; el
            // texto queda invisible (alpha 0) pero compuesto, así sigue definiendo
            // el ancho y el botón no cambia de tamaño.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Texto invisible con el label más largo: iguala el ancho intrínseco
                // de los 3 botones con cualquier fuente del sistema (en dp fijo la
                // fuente del POCO los desparejaba); mismo truco que el del spinner.
                Text(TITULO_BOTON_MAS_LARGO, Modifier.alpha(0f))
                Text(titulo, Modifier.alpha(if (generandoEste) 0f else 1f))
                if (generandoEste) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        }
        if (!habilitado) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Share intent hacia AnkiDroid (u otro receptor del chooser: Drive, Gmail,
 *  Bluetooth...) vía FileProvider — sin permisos de storage (spec Plan 4a).
 *  "application/apkg" es uno de los mimeTypes que el manifest real de
 *  AnkiDroid declara para su intent-filter de ACTION_SEND (junto con
 *  application/octet-stream, application/x-apkg, application/vnd.anki — ver
 *  "Investigación del mimeType" en el plan), así el share sheet puede
 *  ofrecerlo como destino directo. */
private fun compartirMazo(context: Context, archivo: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archivo)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/apkg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share Anki deck"))
}

// Mapea la dificultad cruda a display en inglés (duplicado a propósito del helper
// privado de BibliotecaScreen — convención del repo: no acoplar screens entre sí).
private fun dificultadDisplay(dificultad: String): String = when (dificultad) {
    "facil" -> "Easy"
    "media" -> "Medium"
    "dificil" -> "Hard"
    else -> dificultad.replaceFirstChar { it.uppercase() }
}

/** Línea secundaria de la fila: solo partes no vacías — una importada sin autor
 *  muestra "Medium", nunca " · Medium" con separador colgante. */
private fun detalleHistoria(autor: String, dificultad: String): String =
    listOf(autor, dificultadDisplay(dificultad))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
