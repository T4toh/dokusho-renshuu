package com.tatoh.dokushorenshu.ui.kanji

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DetalleKanjiScreen(vm: DetalleKanjiViewModel) {
    val estado by vm.estado.collectAsState()
    LaunchedEffect(Unit) { vm.cargar() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        val info = estado.info
        if (info == null) {
            if (!estado.cargando) {
                Text("Kanji no encontrado en el diccionario", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        Text(
            info.kanji,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 96.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        )

        Text("Significados", style = MaterialTheme.typography.titleSmall)
        for (significado in info.significados) {
            Text("• $significado")
        }

        if (info.onYomi.isNotEmpty()) {
            Text("On'yomi", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Text(info.onYomi.joinToString("、"))
        }

        if (info.kunYomi.isNotEmpty()) {
            Text("Kun'yomi", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Text(info.kunYomi.joinToString("、"))
        }

        info.jlpt?.let {
            Text(
                "JLPT (escala vieja): $it",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        info.strokes?.let {
            Text("Trazos: $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }

        if (estado.ejemplos.isNotEmpty()) {
            Text(
                "Ejemplos",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            for (ejemplo in estado.ejemplos) {
                Text(ejemplo.japones)
                Text(ejemplo.ingles, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
