package com.tatoh.dokushorenshu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tatoh.dokushorenshu.captura.FloatingBubbleService
import com.tatoh.dokushorenshu.captura.ScreenCaptureService
import com.tatoh.dokushorenshu.ui.acerca.AcercaScreen
import com.tatoh.dokushorenshu.ui.biblioteca.BibliotecaScreen
import com.tatoh.dokushorenshu.ui.biblioteca.BibliotecaViewModel
import com.tatoh.dokushorenshu.ui.captura.CapturaScreen
import com.tatoh.dokushorenshu.ui.export.ExportScreen
import com.tatoh.dokushorenshu.ui.export.ExportViewModel
import com.tatoh.dokushorenshu.ui.importar.ImportScreen
import com.tatoh.dokushorenshu.ui.importar.ImportViewModel
import com.tatoh.dokushorenshu.ui.kanji.DetalleKanjiScreen
import com.tatoh.dokushorenshu.ui.kanji.DetalleKanjiViewModel
import com.tatoh.dokushorenshu.ui.lector.LectorScreen
import com.tatoh.dokushorenshu.ui.lector.LectorViewModel
import com.tatoh.dokushorenshu.ui.tema.TemaDokusho
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    /** Texto que dejó ScreenCaptureService en el Intent, esperando a que la ruta
     *  "importar" lo consuma. Es estado de la Activity y no del NavHost porque
     *  puede llegar por onNewIntent con la app ya abierta (launchMode singleTop). */
    private val textoOcrPendiente = mutableStateOf<String?>(null)

    /** El bubble se tocó sin credenciales de MediaProjection: hay que abrir la
     *  pantalla de captura pidiendo el permiso. Mismo ciclo de vida que el de arriba. */
    private val pedirPermisoPendiente = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ (targetSdk 36) impone edge-to-edge por defecto y deprecó
        // statusBarColor/navigationBarColor: en vez de fijar colores a mano se usa
        // enableEdgeToEdge(), que detecta modo claro/oscuro del sistema (mismo criterio
        // que isSystemInDarkTheme() de Compose, vía TemaDokusho) y ajusta el contraste
        // de los íconos de status/navigation bar automáticamente. Los flags
        // windowLightStatusBar/windowLightNavigationBar en themes.xml solo cubren el
        // primer frame, antes de que corra esta línea.
        enableEdgeToEdge()
        val contenedor = (application as App).contenedor
        leerIntent(intent)
        setContent {
            TemaDokusho {
                val nav = rememberNavController()
                val textoOcr by textoOcrPendiente
                val pedirPermiso by pedirPermisoPendiente
                // Llegó una captura: saltar al import. El texto se consume dentro de
                // la ruta "importar" (no acá) para que sobreviva a la navegación.
                // singleTop para no apilar pantallas de import si llegan varias capturas.
                LaunchedEffect(textoOcr) {
                    if (textoOcr != null) nav.navigate("importar") { launchSingleTop = true }
                }
                LaunchedEffect(pedirPermiso) {
                    if (pedirPermiso) {
                        pedirPermisoPendiente.value = false
                        nav.navigate("captura?permiso=true") { launchSingleTop = true }
                    }
                }
                NavHost(navController = nav, startDestination = "biblioteca") {
                    composable("biblioteca") {
                        val vm: BibliotecaViewModel = viewModel(factory = viewModelFactory {
                            initializer { BibliotecaViewModel(contenedor.historias, contenedor.progresoDb.dao(), contenedor.diccionario) }
                        })
                        // BibliotecaScreen dispara vm.cargar() con LaunchedEffect (Task 9).
                        BibliotecaScreen(
                            vm = vm,
                            onAbrirHistoria = { id -> nav.navigate("lector/$id") },
                            onAcerca = { nav.navigate("acerca") },
                            onVerKanji = { k -> nav.navigate("kanji/$k") },
                            onExport = { nav.navigate("export") },
                            onImportar = { nav.navigate("importar") },
                            onScan = { nav.navigate("captura?permiso=false") },
                        )
                    }
                    composable("lector/{id}") { entrada ->
                        val id = entrada.arguments!!.getString("id")!!
                        val vm: LectorViewModel = viewModel(factory = viewModelFactory {
                            initializer {
                                LectorViewModel(
                                    id, contenedor.historias, contenedor.progresoDb.dao(),
                                    contenedor.prefs, contenedor.tokenizador, contenedor.buscador,
                                )
                            }
                        })
                        // LectorScreen dispara vm.cargar() con LaunchedEffect (Task 10).
                        LectorScreen(vm = vm, onVerKanji = { k -> nav.navigate("kanji/$k") })
                    }
                    composable("kanji/{kanji}") { entrada ->
                        val kanji = entrada.arguments!!.getString("kanji")!!
                        val vm: DetalleKanjiViewModel = viewModel(factory = viewModelFactory {
                            initializer {
                                DetalleKanjiViewModel(kanji, contenedor.diccionario, contenedor.progresoDb.dao())
                            }
                        })
                        // DetalleKanjiScreen dispara vm.cargar() con LaunchedEffect (mismo patrón).
                        DetalleKanjiScreen(vm)
                    }
                    composable("export") {
                        val vm: ExportViewModel = viewModel(factory = viewModelFactory {
                            initializer {
                                ExportViewModel(contenedor.progresoDb.dao(), contenedor.armadorMazos, contenedor.dirExportMazos)
                            }
                        })
                        ExportScreen(vm = vm, onCerrar = { nav.popBackStack() })
                    }
                    composable("importar") {
                        val vm: ImportViewModel = viewModel(factory = viewModelFactory {
                            initializer { ImportViewModel(contenedor.importador) }
                        })
                        // El texto de una captura se vuelca acá y se consume (se pone en
                        // null) para que no reaparezca al rotar ni al volver desde la
                        // biblioteca. La key es el propio texto y no Unit: con
                        // launchSingleTop una segunda captura reusa esta entrada del
                        // backstack, así que el efecto tiene que volver a correr cuando
                        // cambia el valor. Queda editable a propósito: el OCR de texto
                        // vertical puede equivocar el orden de las columnas.
                        val texto by textoOcrPendiente
                        LaunchedEffect(texto) {
                            texto?.let {
                                vm.setTexto(it)
                                vm.setTitulo(tituloDeCaptura())
                                textoOcrPendiente.value = null
                            }
                        }
                        ImportScreen(
                            vm = vm,
                            onImportado = { nav.popBackStack() },
                            onCerrar = { nav.popBackStack() },
                        )
                    }
                    composable(
                        "captura?permiso={permiso}",
                        arguments = listOf(navArgument("permiso") {
                            type = NavType.BoolType
                            defaultValue = false
                        }),
                    ) { entrada ->
                        CapturaScreen(
                            pedirPermisoAlEntrar = entrada.arguments!!.getBoolean("permiso"),
                            onCerrar = { nav.popBackStack() },
                        )
                    }
                    composable("acerca") { AcercaScreen() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        leerIntent(intent)
    }

    /** Vuelca el Intent de los Services al estado y lo marca como consumido. Sin
     *  limpiarlo, al rotar (o al volver de background) onCreate lo vuelve a leer y
     *  el texto reaparece pisando lo que el usuario haya editado en el import. */
    private fun leerIntent(intent: Intent?) {
        when (intent?.action) {
            ScreenCaptureService.ACTION_TEXTO_OCR ->
                textoOcrPendiente.value = intent.getStringExtra(ScreenCaptureService.EXTRA_TEXTO_OCR)
            FloatingBubbleService.ACTION_PEDIR_PERMISO -> pedirPermisoPendiente.value = true
            else -> return
        }
        intent.action = null
        intent.removeExtra(ScreenCaptureService.EXTRA_TEXTO_OCR)
    }
}

/** Título por defecto de una captura. Con fecha y hora porque se generan muchas
 *  seguidas y el id de la historia sale del título (colisión → sufijo -2, -3…). */
private fun tituloDeCaptura(): String =
    "Scan " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
