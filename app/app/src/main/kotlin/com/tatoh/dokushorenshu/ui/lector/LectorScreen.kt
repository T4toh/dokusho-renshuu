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

    // carga inicial al entrar a la pantalla (mismo patrón que BibliotecaScreen, Task 9)
    LaunchedEffect(Unit) { vm.cargar() }

    // la oración actual siempre visible al fondo de la lista
    LaunchedEffect(estado.indiceActual) {
        if (estado.oraciones.isNotEmpty()) listaEstado.animateScrollToItem(estado.indiceActual)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(estado.titulo) },
                actions = {
                    TextButton(onClick = vm::alternarFurigana) {
                        Text(if (estado.furiganaActiva) "ふ ON" else "ふ OFF")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(onClick = vm::retroceder) { Text("Anterior") }
                Button(onClick = vm::avanzar) { Text("Siguiente") }
            }
        },
    ) { relleno ->
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

    estado.consulta?.let { consulta ->
        ModalBottomSheet(onDismissRequest = vm::cerrarSheet) {
            PalabraSheet(consulta, onVerKanji)
        }
    }
}
