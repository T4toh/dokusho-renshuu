package com.tatoh.dokushorenshu.ui.export

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
            BotonExport(
                titulo = "Export Words deck",
                habilitado = contadores.words > 0,
                hint = "Read and tap words first",
                generando = estado is EstadoExport.Generando,
                onClick = { vm.exportar(TipoExport.WORDS) },
            )
            Spacer(Modifier.height(12.dp))
            BotonExport(
                titulo = "Export Kanji deck",
                habilitado = contadores.kanjisTaggeados > 0,
                hint = "Tag kanji as easy/medium/hard first",
                generando = estado is EstadoExport.Generando,
                onClick = { vm.exportar(TipoExport.KANJI) },
            )
            Spacer(Modifier.height(12.dp))
            BotonExport(
                titulo = "Export Stories deck",
                habilitado = contadores.historias > 0,
                hint = "No local stories",
                generando = estado is EstadoExport.Generando,
                onClick = { vm.exportar(TipoExport.STORIES) },
            )

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
    generando: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Button(onClick = onClick, enabled = habilitado && !generando) {
            if (generando) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(titulo)
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
