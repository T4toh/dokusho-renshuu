package com.tatoh.dokushorenshu.ui.biblioteca

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Mapea la dificultad cruda a display en inglés
private fun dificultadDisplay(dificultad: String): String = when (dificultad) {
    "facil" -> "Easy"
    "media" -> "Medium"
    "dificil" -> "Hard"
    else -> dificultad.replaceFirstChar { it.uppercase() }  // Mapear tags en inglés minúsculas (easy/medium/hard) a capitalizadas
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BibliotecaScreen(
    vm: BibliotecaViewModel,
    onAbrirHistoria: (String) -> Unit,
    onAcerca: () -> Unit,
    onVerKanji: (String) -> Unit,
    onExport: () -> Unit,
    onImportar: () -> Unit,
) {
    val locales by vm.locales.collectAsState()
    val catalogo by vm.catalogo.collectAsState()
    val review by vm.review.collectAsState()

    // carga inicial al entrar a la pantalla
    LaunchedEffect(Unit) { vm.cargar() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Dokusho Renshū") },
            actions = {
                TextButton(onClick = onImportar) { Text("Import") }
                TextButton(onClick = onExport) { Text("Export") }
                TextButton(onClick = onAcerca) { Text("About") }
            },
        )
    }) { relleno ->
        Column(Modifier.padding(relleno)) {
            if (catalogo is EstadoCatalogo.Error) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text((catalogo as EstadoCatalogo.Error).mensaje, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::refrescarCatalogo) { Text("Retry") }
                }
            }
            // grid adaptativo: 1 columna en teléfono vertical, 2-3 en tablet/landscape.
            // unificado (Task 12): locales descargadas + remotas sin descargar (con botón
            // Download en la card) — ya no hay una sección "Catálogo" separada.
            // weight(1f): limita el grid al espacio restante para que la sección Review
            // (fuera de este LazyColumn) no quede empujada fuera de pantalla cuando el
            // catálogo crece.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(locales, key = { it.historia.id }) { item ->
                    var mostrarConfirmacion by remember(item.historia.id) { mutableStateOf(false) }
                    Card(Modifier.fillMaxWidth().clickable { onAbrirHistoria(item.historia.id) }) {
                        Column(Modifier.padding(16.dp)) {
                            item.metadata?.tituloLectura?.let {
                                Text(
                                    it, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(item.historia.titulo, style = MaterialTheme.typography.titleLarge)
                                    if (item.importada) {
                                        AssistChip(onClick = {}, enabled = false, label = { Text("Imported") })
                                    }
                                }
                                if (item.importada) {
                                    IconButton(onClick = { mostrarConfirmacion = true }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete imported story")
                                    }
                                }
                            }
                            item.metadata?.tituloEn?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                "${item.historia.autor} · ${dificultadDisplay(item.historia.dificultad)}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            item.metadata?.let { meta ->
                                Text(
                                    "${meta.kanjisUnicos} kanji · ${meta.oraciones} sentences",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (item.progresoPct > 0) {
                                LinearProgressIndicator(
                                    progress = { item.progresoPct / 100f },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            }
                        }
                    }
                    if (mostrarConfirmacion) {
                        AlertDialog(
                            onDismissRequest = { mostrarConfirmacion = false },
                            text = { Text("Delete this imported story? Reading progress will be kept.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    mostrarConfirmacion = false
                                    vm.borrarImportada(item.historia.id)
                                }) { Text("Delete") }
                            },
                            dismissButton = {
                                TextButton(onClick = { mostrarConfirmacion = false }) { Text("Cancel") }
                            },
                        )
                    }
                }
                if (catalogo is EstadoCatalogo.Ok) {
                    items((catalogo as EstadoCatalogo.Ok).disponibles, key = { it.id }) { entrada ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                entrada.tituloLectura.let {
                                    Text(
                                        it, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(entrada.titulo, style = MaterialTheme.typography.titleLarge)
                                entrada.tituloEn?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                Text(
                                    "${entrada.autor} · ${dificultadDisplay(entrada.dificultad)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                Text(
                                    "${entrada.kanjisUnicos} kanji · ${entrada.oraciones} sentences",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(onClick = { vm.descargar(entrada.id) }, modifier = Modifier.padding(top = 8.dp)) {
                                    Text("Download")
                                }
                            }
                        }
                    }
                }
            }
            if (review.isNotEmpty()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Review", style = MaterialTheme.typography.titleMedium)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (tarjeta in review) {
                            Card(
                                Modifier.weight(1f).let { m ->
                                    when (tarjeta) {
                                        is TarjetaReview.ConKanji -> m.clickable { onVerKanji(tarjeta.kanji.kanji) }
                                        is TarjetaReview.Vacia -> m
                                    }
                                },
                            ) {
                                Column(
                                    Modifier.padding(12.dp).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        dificultadDisplay(tarjeta.dificultad),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    when (tarjeta) {
                                        is TarjetaReview.ConKanji -> {
                                            Text(tarjeta.kanji.kanji, style = MaterialTheme.typography.displaySmall)
                                            tarjeta.lecturaPrincipal?.let {
                                                Text(it, style = MaterialTheme.typography.bodySmall)
                                            }
                                            tarjeta.kanji.significados.firstOrNull()?.let {
                                                Text(it, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        is TarjetaReview.Vacia -> Text(
                                            "Tag kanji from their detail view",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
