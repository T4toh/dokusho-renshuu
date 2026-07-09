package com.tatoh.dokushorenshu.ui.kanji

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DIFICULTADES = listOf("easy", "medium", "hard")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalleKanjiScreen(vm: DetalleKanjiViewModel) {
    val estado by vm.estado.collectAsState()
    LaunchedEffect(Unit) { vm.cargar() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val info = estado.info
            if (info == null) {
                if (!estado.cargando) {
                    Text("Kanji not found in the dictionary", style = MaterialTheme.typography.bodyMedium)
                }
                return@Column
            }

            Text(
                info.kanji,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 24.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (valor in DIFICULTADES) {
                    FilterChip(
                        selected = estado.dificultad == valor,
                        onClick = { vm.alternarDificultad(valor) },
                        label = { Text(valor.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            Seccion("Meanings") {
                Column(horizontalAlignment = Alignment.Start) {
                    for (significado in info.significados) Text("• $significado")
                }
            }

            if (info.onYomi.isNotEmpty() || info.kunYomi.isNotEmpty()) {
                Seccion("Readings") {
                    Column(Modifier.fillMaxWidth()) {
                        if (info.onYomi.isNotEmpty()) FilaEtiquetada("On'yomi", info.onYomi.joinToString("、"))
                        if (info.kunYomi.isNotEmpty()) FilaEtiquetada("Kun'yomi", info.kunYomi.joinToString("、"))
                    }
                }
            }

            if (info.jlpt != null || info.strokes != null) {
                Seccion("Stats") {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        info.jlpt?.let { Text("JLPT (old scale): $it", style = MaterialTheme.typography.bodyMedium) }
                        info.strokes?.let { Text("Strokes: $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }

            if (estado.ejemplos.isNotEmpty()) {
                Seccion("Examples") {
                    Column(Modifier.fillMaxWidth()) {
                        estado.ejemplos.forEachIndexed { indice, ejemplo ->
                            Text(ejemplo.japones, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                ejemplo.ingles, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (indice != estado.ejemplos.lastIndex) {
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Seccion(titulo: String, contenido: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(titulo, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        contenido()
    }
}

@Composable
private fun FilaEtiquetada(etiqueta: String, valor: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(etiqueta, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(90.dp))
        Text(valor, style = MaterialTheme.typography.bodyMedium)
    }
}
