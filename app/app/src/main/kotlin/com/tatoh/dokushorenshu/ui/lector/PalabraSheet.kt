package com.tatoh.dokushorenshu.ui.lector

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tatoh.dokushorenshu.dominio.ConsultaPalabra

@Composable
fun PalabraSheet(consulta: ConsultaPalabra, onVerKanji: (String) -> Unit) {
    Column(Modifier.padding(16.dp).fillMaxWidth()) {
        Text(consulta.termino, style = MaterialTheme.typography.headlineMedium)
        val lectura = consulta.definiciones.firstOrNull()?.lectura ?: consulta.lecturaFallback
        lectura?.let { Text(it, style = MaterialTheme.typography.titleMedium) }

        if (consulta.sinDefinicion) {
            Text("Sin definición", style = MaterialTheme.typography.bodyMedium)
        } else {
            for (significado in consulta.definiciones.first().significados) {
                Text("• $significado")
            }
        }

        if (consulta.ejemplos.isNotEmpty()) {
            Text("Ejemplos", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp))
            for (ejemplo in consulta.ejemplos) {
                Text(ejemplo.japones)
                Text(ejemplo.ingles, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (consulta.kanjis.isNotEmpty()) {
            Row(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (kanji in consulta.kanjis) {
                    AssistChip(onClick = { onVerKanji(kanji.kanji) }, label = { Text(kanji.kanji) })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
