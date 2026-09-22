package com.tatoh.dokushorenshu.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tatoh.dokushorenshu.update.UpdateViewModel.Fase

/** Aviso de versión nueva arriba de la navegación. No es modal: no interrumpe la
 *  lectura. Toda la lógica está en [UpdateViewModel]; acá solo se pinta la fase. */
@Composable
fun UpdateBanner(vm: UpdateViewModel) {
    val info = vm.info ?: return
    if (vm.fase == Fase.OCULTO) return

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.alVolverDeAjustes() }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
        ) {
            when (vm.fase) {
                Fase.AVISO -> Fila("New version available: ${info.version}", "Update", vm::actualizar, vm::cerrar)
                Fase.SIN_PERMISO -> Fila("To update, allow \"Install unknown apps\" for Dokusho.", "Open Settings", vm::abrirAjustes, vm::cerrar)
                Fase.DESCARGANDO, Fase.VERIFICANDO -> {
                    Text(
                        if (vm.fase == Fase.DESCARGANDO) "Downloading ${info.version}…" else "Verifying the download…",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val progreso = vm.progreso
                    val modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp)
                    if (vm.fase == Fase.DESCARGANDO && progreso != null) {
                        LinearProgressIndicator(progress = { progreso }, modifier = modifier)
                    } else {
                        LinearProgressIndicator(modifier = modifier)
                    }
                    // Solo en DESCARGANDO: VERIFICANDO dura segundos y cancelar a mitad
                    // podría dejar la fase inconsistente.
                    if (vm.fase == Fase.DESCARGANDO) {
                        TextButton(onClick = vm::cerrar, modifier = Modifier.align(Alignment.End)) { Text("Not now") }
                    }
                }
                Fase.LISTO -> Fila("Update ready to install.", "Install", vm::instalar, vm::cerrar)
                Fase.ERROR -> Fila(vm.error, "Retry", vm::actualizar, vm::cerrar)
                Fase.OCULTO -> Unit
            }
        }
    }
}

@Composable
private fun Fila(texto: String, accion: String, onAccion: () -> Unit, onCerrar: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(texto, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAccion) { Text(accion) }
        // TextButton y no un ícono: el repo no usa material-icons y no vale sumarlo por una ✕.
        if (onCerrar != null) TextButton(onClick = onCerrar) { Text("Not now") }
    }
}
