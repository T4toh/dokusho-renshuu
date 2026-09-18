package com.tatoh.dokushorenshu.ui.captura

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tatoh.dokushorenshu.captura.FloatingBubbleService
import com.tatoh.dokushorenshu.captura.CapturaService

/** MediaProjection con recorte por overlay necesita Android 10+. minSdk sigue en
 *  26 porque el lector anda perfecto sin esto: la feature se deshabilita, no se
 *  sube el piso de la app. */
internal val SOPORTADO = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

/** El Intent del diálogo de consentimiento del sistema. */
internal fun intentDeProyeccion(context: Context): Intent =
    (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
        .createScreenCaptureIntent()

/** Guarda las credenciales para el bubble y arranca el Service de captura.
 *  Compartido entre el botón "Capture now" de esta pantalla y el pedido que dispara
 *  el tap del bubble, que se atiende en MainActivity. */
internal fun iniciarCaptura(context: Context, resultCode: Int, datos: Intent) {
    // El bubble guarda las credenciales para poder disparar capturas sin pasar por la
    // Activity. Android 14+ las invalida después de cada sesión y el Service las limpia
    // solo (sólo cuando la sesión existió de verdad: cancelar el overlay no las toca).
    FloatingBubbleService.captureResultCode = resultCode
    FloatingBubbleService.captureResultData = datos
    ContextCompat.startForegroundService(
        context,
        Intent(context, CapturaService::class.java).apply {
            action = CapturaService.ACTION_START_CAPTURE
            putExtra(CapturaService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CapturaService.EXTRA_RESULT_DATA, datos)
        },
    )
}

private data class EstadoPermisos(val overlay: Boolean, val notificaciones: Boolean)

private fun leerPermisos(context: Context) = EstadoPermisos(
    overlay = Settings.canDrawOverlays(context),
    notificaciones = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapturaScreen(onCerrar: () -> Unit) {
    val context = LocalContext.current
    var permisos by remember { mutableStateOf(leerPermisos(context)) }
    var bubbleActivo by remember { mutableStateOf(FloatingBubbleService.isRunning) }

    // El permiso de overlay se concede en Ajustes del sistema, no con un diálogo:
    // se lanza como Activity y se releen los permisos cuando el usuario vuelve.
    // Esto evita depender de lifecycle-runtime-compose solo para un ON_RESUME.
    val lanzadorOverlay = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permisos = leerPermisos(context) }

    val lanzadorNotificaciones = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permisos = leerPermisos(context) }

    val lanzadorProyeccion = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        val datos = resultado.data
        if (resultado.resultCode == Activity.RESULT_OK && datos != null) {
            iniciarCaptura(context, resultado.resultCode, datos)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Scan") },
            actions = { TextButton(onClick = onCerrar) { Text("Close") } },
        )
    }) { relleno ->
        Column(
            Modifier.fillMaxSize().padding(relleno).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!SOPORTADO) {
                Text("Screen capture needs Android 10 or newer.", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }

            Text(
                "Capture any part of the screen and turn it into a story you can read with furigana and lookups.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!permisos.overlay) {
                Text("Draw over other apps: not granted", style = MaterialTheme.typography.titleSmall)
                Button(
                    onClick = {
                        lanzadorOverlay.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant overlay permission") }
            }

            if (!permisos.notificaciones) {
                Text("Notifications: not granted", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The floating button runs as a foreground service and Android requires a visible notification for it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { lanzadorNotificaciones.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant notification permission") }
            }

            val listo = permisos.overlay && permisos.notificaciones

            Button(
                onClick = { lanzadorProyeccion.launch(intentDeProyeccion(context)) },
                enabled = listo,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Capture now") }

            OutlinedButton(
                onClick = {
                    val intent = Intent(context, FloatingBubbleService::class.java)
                    if (bubbleActivo) {
                        // startService, no startForegroundService: el flag bubbleActivo
                        // es solo un espejo del estado real del Service y puede
                        // desincronizarse (el Service puede morir solo, o el usuario
                        // puede pararlo desde el "Stop" de su propia notificación).
                        // stopBubble() nunca promueve a foreground, así que si acá
                        // usáramos startForegroundService y el Service ya estuviera
                        // muerto, Android lo mata a los ~5s por no llamar
                        // startForeground() ("did not then call
                        // Service.startForeground()"). Con startService el peor caso
                        // es un no-op inofensivo.
                        intent.action = FloatingBubbleService.ACTION_STOP_BUBBLE
                        context.startService(intent)
                    } else {
                        intent.action = FloatingBubbleService.ACTION_START_BUBBLE
                        ContextCompat.startForegroundService(context, intent)
                    }
                    bubbleActivo = !bubbleActivo
                },
                enabled = listo,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (bubbleActivo) "Stop floating button" else "Start floating button") }

            Text(
                "On Android 14 and newer, Android asks for capture permission every time — that is an OS rule, not a bug.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
