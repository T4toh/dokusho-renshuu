package com.tatoh.dokushorenshu.ui.importar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

private val DIFICULTADES = listOf("easy", "medium", "hard")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    vm: ImportViewModel,
    onImportado: (String) -> Unit,
    onCerrar: () -> Unit,
) {
    val form by vm.form.collectAsState()
    val estado by vm.estado.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val abrirArchivo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult  // cancelado por el usuario
        vm.cargarArchivo { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
    }

    LaunchedEffect(estado) {
        val actual = estado
        if (actual is EstadoImport.Listo) onImportado(actual.id)
        if (actual is EstadoImport.Error) {
            snackbarHostState.showSnackbar(actual.mensaje)
            vm.descartarAviso()
        }
    }

    val puedeImportar = form.titulo.isNotBlank() && form.texto.isNotBlank() && estado !is EstadoImport.Importando

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import") },
                navigationIcon = { TextButton(onClick = onCerrar) { Text("Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { relleno ->
        Column(
            Modifier
                .padding(relleno)
                .padding(24.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = form.titulo,
                onValueChange = vm::setTitulo,
                label = { Text("Title *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = form.autor,
                onValueChange = vm::setAutor,
                label = { Text("Author") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (valor in DIFICULTADES) {
                    FilterChip(
                        selected = form.dificultad == valor,
                        onClick = { vm.setDificultad(valor) },
                        label = { Text(valor.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = form.texto,
                onValueChange = vm::setTexto,
                label = { Text("Japanese text — paste here") },
                minLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { abrirArchivo.launch(arrayOf("text/plain")) },
                enabled = estado !is EstadoImport.Importando,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open .txt") }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { vm.importar() },
                enabled = puedeImportar,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Mismo patrón que ExportScreen: el spinner reemplaza al texto (alpha 0,
                // pero sigue compuesto) para que el botón no cambie de ancho/alto.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Import", Modifier.alpha(if (estado is EstadoImport.Importando) 0f else 1f))
                    if (estado is EstadoImport.Importando) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }

    if (estado is EstadoImport.ConfirmarNoJapones) {
        AlertDialog(
            onDismissRequest = vm::descartarAviso,
            text = { Text("This doesn't look like Japanese text. Import anyway?") },
            confirmButton = {
                TextButton(onClick = { vm.importar(forzar = true) }) { Text("Import anyway") }
            },
            dismissButton = {
                TextButton(onClick = vm::descartarAviso) { Text("Cancel") }
            },
        )
    }
}
