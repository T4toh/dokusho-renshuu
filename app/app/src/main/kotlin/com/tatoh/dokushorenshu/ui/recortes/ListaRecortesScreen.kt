package com.tatoh.dokushorenshu.ui.recortes

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val LARGO_PREVIEW = 40

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListaRecortesScreen(
    vm: RecortesViewModel,
    onAbrirRecorte: (String) -> Unit,
    onScan: () -> Unit,
) {
    val recortes by vm.recortes.collectAsState()

    LaunchedEffect(Unit) { vm.cargar() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Notes") },
            actions = { TextButton(onClick = onScan) { Text("Scan") } },
        )
    }) { relleno ->
        if (recortes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(relleno), contentAlignment = Alignment.Center) {
                Text(
                    "No notes yet. Tap Scan to capture text from another app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(relleno),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(recortes, key = { it.id }) { recorte ->
                    var mostrarConfirmacion by remember(recorte.id) { mutableStateOf(false) }
                    val preview = recorte.texto.take(LARGO_PREVIEW)
                    val fechaRelativa = DateUtils.getRelativeTimeSpanString(
                        recorte.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    )
                    Card(
                        Modifier.fillMaxWidth().combinedClickable(
                            onClick = { onAbrirRecorte(recorte.id) },
                            onLongClick = { mostrarConfirmacion = true },
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                fechaRelativa.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    if (mostrarConfirmacion) {
                        AlertDialog(
                            onDismissRequest = { mostrarConfirmacion = false },
                            title = { Text("Delete note?") },
                            text = { Text("This also deletes its image.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    mostrarConfirmacion = false
                                    vm.borrar(recorte.id)
                                }) { Text("Delete") }
                            },
                            dismissButton = {
                                TextButton(onClick = { mostrarConfirmacion = false }) { Text("Cancel") }
                            },
                        )
                    }
                }
            }
        }
    }
}
