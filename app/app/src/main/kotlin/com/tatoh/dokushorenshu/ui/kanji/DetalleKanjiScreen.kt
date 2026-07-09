package com.tatoh.dokushorenshu.ui.kanji

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.safeDrawingPadding
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
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.OracionEjemplo

private val DIFICULTADES = listOf("easy", "medium", "hard")

// Umbral a partir del cual hay suficiente ancho para un layout de 2 columnas (tablet/landscape).
private val ANCHO_MINIMO_DOS_COLUMNAS = 600.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalleKanjiScreen(vm: DetalleKanjiViewModel) {
    val estado by vm.estado.collectAsState()
    LaunchedEffect(Unit) { vm.cargar() }

    Surface(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),  // edge-to-edge de Task 1
        color = MaterialTheme.colorScheme.background
    ) {
        val info = estado.info
        if (info == null) {
            // Estado degradado: kanji no encontrado (o todavía cargando, sin mensaje).
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!estado.cargando) {
                    Text("Kanji not found in the dictionary", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Surface
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Se capturan como vals locales: dentro de los lambdas anidados de Row/Column
            // ya no hay receiver implícito de BoxWithConstraintsScope para leer maxWidth.
            val dosColumnas = maxWidth >= ANCHO_MINIMO_DOS_COLUMNAS
            val anchoColumnaIzquierda = maxWidth * 0.4f
            if (dosColumnas) {
                // Ancho generoso: 2 columnas, cada una con su propio scroll independiente.
                Row(Modifier.fillMaxSize().padding(24.dp)) {
                    Column(
                        Modifier
                            .width(anchoColumnaIzquierda)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ColumnaKanji(info, estado.dificultad, vm::alternarDificultad)
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 24.dp),
                    ) {
                        SeccionesInfo(info, estado.ejemplos)
                    }
                }
            } else {
                // Ancho chico: 1 columna, todo en el mismo scroll (layout actual).
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ColumnaKanji(info, estado.dificultad, vm::alternarDificultad)
                    SeccionesInfo(info, estado.ejemplos)
                }
            }
        }
    }
}

/** Kanji gigante + chips de dificultad + Stats (JLPT/strokes). */
@Composable
private fun ColumnaKanji(info: KanjiInfo, dificultadActual: String?, onDificultadClick: (String) -> Unit) {
    Text(
        info.kanji,
        style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 24.dp),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (valor in DIFICULTADES) {
            FilterChip(
                selected = dificultadActual == valor,
                onClick = { onDificultadClick(valor) },
                label = { Text(valor.replaceFirstChar { it.uppercase() }) },
            )
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
}

/** Meanings + Readings + Examples. */
@Composable
private fun SeccionesInfo(info: KanjiInfo, ejemplos: List<OracionEjemplo>) {
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

    if (ejemplos.isNotEmpty()) {
        Seccion("Examples") {
            Column(Modifier.fillMaxWidth()) {
                ejemplos.forEachIndexed { indice, ejemplo ->
                    Text(ejemplo.japones, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        ejemplo.ingles, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (indice != ejemplos.lastIndex) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
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
