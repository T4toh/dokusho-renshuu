package com.tatoh.dokushorenshu.ui.biblioteca

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BibliotecaScreen(
    vm: BibliotecaViewModel,
    onAbrirHistoria: (String) -> Unit,
    onAcerca: () -> Unit,
) {
    val locales by vm.locales.collectAsState()
    val catalogo by vm.catalogo.collectAsState()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Dokusho Renshū") },
            actions = { TextButton(onClick = onAcerca) { Text("Acerca de") } },
        )
    }) { relleno ->
        // grid adaptativo: 1 columna en teléfono vertical, 2-3 en tablet/landscape
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 300.dp),
            modifier = Modifier.padding(relleno).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(locales, key = { it.historia.id }) { item ->
                Card(Modifier.fillMaxWidth().clickable { onAbrirHistoria(item.historia.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(item.historia.titulo, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${item.historia.autor} · ${item.historia.dificultad}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (item.progresoPct > 0) {
                            LinearProgressIndicator(
                                progress = { item.progresoPct / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Catálogo", style = MaterialTheme.typography.titleMedium)
            }
            when (val estado = catalogo) {
                is EstadoCatalogo.Cargando -> item { CircularProgressIndicator() }
                is EstadoCatalogo.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(estado.mensaje)
                        TextButton(onClick = vm::refrescarCatalogo) { Text("Reintentar") }
                    }
                }
                is EstadoCatalogo.Ok -> items(estado.disponibles, key = { it.id }) { entrada ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(entrada.titulo, style = MaterialTheme.typography.titleMedium)
                                Text(entrada.dificultad, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { vm.descargar(entrada.id) }) { Text("Descargar") }
                        }
                    }
                }
            }
        }
    }
}
