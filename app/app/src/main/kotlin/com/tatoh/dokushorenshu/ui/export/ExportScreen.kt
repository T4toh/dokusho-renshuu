package com.tatoh.dokushorenshu.ui.export

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(vm: ExportViewModel, onCerrar: () -> Unit) {
    val contadores by vm.contadores.collectAsState()
    val estado by vm.estado.collectAsState()
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
            // width(IntrinsicSize.Max): los tres botones toman el ancho del más
            // largo en vez de ajustarse cada uno a su texto
            val tipoGenerando = (estado as? EstadoExport.Generando)?.tipo
            Column(Modifier.width(IntrinsicSize.Max)) {
                BotonExport(
                    titulo = "Export Words deck",
                    habilitado = contadores.words > 0,
                    hint = "Read and tap words first",
                    generandoEste = tipoGenerando == TipoExport.WORDS,
                    generandoOtro = tipoGenerando != null && tipoGenerando != TipoExport.WORDS,
                    onClick = { vm.exportar(TipoExport.WORDS) },
                )
                Spacer(Modifier.height(12.dp))
                BotonExport(
                    titulo = "Export Kanji deck",
                    habilitado = contadores.kanjisTaggeados > 0,
                    hint = "Tag kanji as easy/medium/hard first",
                    generandoEste = tipoGenerando == TipoExport.KANJI,
                    generandoOtro = tipoGenerando != null && tipoGenerando != TipoExport.KANJI,
                    onClick = { vm.exportar(TipoExport.KANJI) },
                )
                Spacer(Modifier.height(12.dp))
                BotonExport(
                    titulo = "Export Stories deck",
                    habilitado = contadores.historias > 0,
                    hint = "No local stories",
                    generandoEste = tipoGenerando == TipoExport.STORIES,
                    generandoOtro = tipoGenerando != null && tipoGenerando != TipoExport.STORIES,
                    onClick = { vm.exportar(TipoExport.STORIES) },
                )
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

@Composable
private fun BotonExport(
    titulo: String,
    habilitado: Boolean,
    hint: String,
    generandoEste: Boolean,
    generandoOtro: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Button(
            onClick = onClick,
            enabled = habilitado && !generandoEste && !generandoOtro,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // El spinner va superpuesto (Box) y solo en el botón tapeado: el texto
            // queda siempre centrado y los tres botones se ven idénticos entre sí.
            Box(Modifier.fillMaxWidth()) {
                if (generandoEste) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).align(Alignment.CenterStart),
                        strokeWidth = 2.dp,
                    )
                }
                Text(titulo, Modifier.align(Alignment.Center))
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
