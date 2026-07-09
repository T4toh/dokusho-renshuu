package com.tatoh.dokushorenshu.ui.lector

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tatoh.dokushorenshu.dominio.ConsultaPalabra

@Composable
fun PalabraSheet(consulta: ConsultaPalabra, onVerKanji: (String) -> Unit) {
    Column(
        // heightIn(max) en vez de fillMaxHeight: con skipPartiallyExpanded, el sheet ya
        // sizea al contenido — si es corto (sin ejemplos/kanjis) se ve envuelto y compacto;
        // si es largo, este máximo lo cap-ea y el verticalScroll interno lo hace navegable.
        // 600.dp (subido de 480.dp) da más margen a antes de cortar contenido corto/medio
        // en tablets, sin arriesgar overflow: ModalBottomSheet ya limita el alto disponible
        // a la pantalla, así que el scroll cubre cualquier resto.
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .heightIn(max = 600.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(consulta.termino, style = MaterialTheme.typography.headlineMedium)
        val lectura = consulta.definiciones.firstOrNull()?.lectura ?: consulta.lecturaFallback
        lectura?.let { Text(it, style = MaterialTheme.typography.titleMedium) }

        if (consulta.sinDefinicion) {
            Text("No definition", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
        } else {
            Column(Modifier.padding(top = 8.dp)) {
                consulta.definiciones.first().significados.forEachIndexed { indice, significado ->
                    Text("${indice + 1}. $significado", modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }

        if (consulta.ejemplos.isNotEmpty()) {
            Text("Examples", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            for (ejemplo in consulta.ejemplos) {
                Text(ejemplo.japones, modifier = Modifier.padding(top = 8.dp))
                Text(ejemplo.ingles, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (consulta.kanjis.isNotEmpty()) {
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kanji in consulta.kanjis) {
                    AssistChip(onClick = { onVerKanji(kanji.kanji) }, label = { Text(kanji.kanji) })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
