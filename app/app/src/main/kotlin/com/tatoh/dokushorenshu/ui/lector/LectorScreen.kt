package com.tatoh.dokushorenshu.ui.lector

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LectorScreen(vm: LectorViewModel, onVerKanji: (String) -> Unit) {
    val estado by vm.estado.collectAsState()
    val listaEstado = rememberLazyListState()
    // skipPartiallyExpanded = true: el sheet de palabra abre directo al alto de su
    // contenido (o al máximo si es largo) en vez de quedar a mitad de pantalla.
    // Sin esto, en dispositivos reales el estado partially-expanded queda "trabado"
    // (el drag-up del handle y el scroll interno no lo expanden) dejando contenido
    // inalcanzable — bug validado en tablet.
    val estadoSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // carga inicial al entrar a la pantalla (mismo patrón que BibliotecaScreen, Task 9)
    LaunchedEffect(Unit) { vm.cargar() }

    // la oración actual siempre visible al fondo de la lista (no aplica en la portada)
    LaunchedEffect(estado.indiceActual) {
        if (!estado.enPortada && estado.oraciones.isNotEmpty()) {
            listaEstado.animateScrollToItem(estado.indiceActual)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(estado.titulo) },
                actions = {
                    if (!estado.enPortada) {
                        TextButton(onClick = vm::alternarFurigana) {
                            Text(if (estado.furiganaActiva) "Furigana ON" else "Furigana OFF")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(onClick = vm::retroceder, enabled = estado.indiceActual > -1) {
                    Text("Previous")
                }
                Button(onClick = vm::avanzar) {
                    Text(if (estado.enPortada) (if (estado.progresoGuardado >= 0) "Continue reading" else "Start reading") else "Next")
                }
            }
        },
    ) { relleno ->
        if (estado.enPortada) {
            Portada(estado, Modifier.padding(relleno))
        } else {
            // ancho de línea limitado: legible en landscape/tablet (uso horizontal)
            LazyColumn(
                state = listaEstado,
                modifier = Modifier
                    .padding(relleno)
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = 700.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(
                    estado.oraciones.subList(0, (estado.indiceActual + 1).coerceAtMost(estado.oraciones.size)),
                ) { indice, plana ->
                    val esActual = indice == estado.indiceActual
                    Box(Modifier.alpha(if (esActual) 1f else 0.4f)) {
                        TextoConFurigana(
                            oracion = plana.oracion,
                            tokens = plana.tokens,
                            furiganaActiva = estado.furiganaActiva,
                            grande = esActual,
                            onTapPalabra = if (esActual) vm::tocarPalabra else null,
                        )
                    }
                }
            }
        }
    }

    estado.consulta?.let { consulta ->
        ModalBottomSheet(onDismissRequest = vm::cerrarSheet, sheetState = estadoSheet) {
            PalabraSheet(consulta, onVerKanji)
        }
    }
}

/** Portada de la historia (Task C3): se muestra cuando indiceActual == -1, antes
 *  de arrancar a leer o al retroceder desde la primera oración. */
@Composable
private fun Portada(estado: EstadoLector, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(estado.titulo, style = MaterialTheme.typography.displaySmall)
        estado.metadata?.tituloLectura?.let {
            Text(
                it, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        estado.metadata?.tituloEn?.let {
            Text(it, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
        }
        Text(estado.autor, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        estado.metadata?.let { meta ->
            Text(
                "${meta.kanjisUnicos} unique kanji · ${meta.oraciones} sentences · ${estado.porcentajeLeido}% read",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
