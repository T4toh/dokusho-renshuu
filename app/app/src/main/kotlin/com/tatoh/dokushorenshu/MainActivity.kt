package com.tatoh.dokushorenshu

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
import com.tatoh.dokushorenshu.ui.recortes.ListaRecortesScreen
import com.tatoh.dokushorenshu.ui.recortes.RecorteScreen
import com.tatoh.dokushorenshu.ui.recortes.RecorteViewModel
import com.tatoh.dokushorenshu.ui.recortes.RecortesViewModel
import com.tatoh.dokushorenshu.ui.tema.TemaDokusho
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    /** Texto + ruta de la imagen que dejó ScreenCaptureService en el Intent,
     *  esperando a convertirse en recorte. La ruta es opcional: si el guardado del
     *  JPEG falló, el recorte se crea igual pero sin imagen. Es estado de la Activity
     *  y no del NavHost porque puede llegar por onNewIntent con la app ya abierta
     *  (launchMode singleTop). */
    private val capturaPendiente = mutableStateOf<Pair<String, String?>?>(null)

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
                val captura by capturaPendiente
                val pedirPermiso by pedirPermisoPendiente
                // Llegó una captura: se vuelve recorte y se abre. Se consume ANTES de la
                // IO para que rotar en el medio no la vuelva a crear. singleTop para no
                // apilar pantallas si llegan varias capturas.
                LaunchedEffect(captura) {
                    val actual = captura ?: return@LaunchedEffect
                    capturaPendiente.value = null
                    val (texto, ruta) = actual
                    // crear() bloquea en Kuromoji: jamás en el main thread.
                    val recorte = withContext(Dispatchers.IO) {
                        runCatching { contenedor.creadorRecortes.crear(texto, ruta?.let(::File)) }
                    }
                    recorte.onSuccess { nav.navigate("recorte/${it.id}") { launchSingleTop = true } }
                    recorte.onFailure {
                        // Sin aviso el usuario se queda mirando la biblioteca sin saber que
                        // su captura se perdió: el texto original ya no existe en ningún lado.
                        android.util.Log.e("MainActivity", "no se pudo crear el recorte", it)
                        Toast.makeText(this@MainActivity, "Could not save the capture", Toast.LENGTH_LONG).show()
                    }
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
                        val recortesVm: RecortesViewModel = viewModel(factory = viewModelFactory {
                            initializer { RecortesViewModel(contenedor.recortes) }
                        })
                        // BibliotecaScreen dispara vm.cargar() con LaunchedEffect (Task 9).
                        BibliotecaScreen(
                            vm = vm,
                            onAbrirHistoria = { id -> nav.navigate("lector/$id") },
                            onAcerca = { nav.navigate("acerca") },
                            onVerKanji = { k -> nav.navigate("kanji/$k") },
                            onExport = { nav.navigate("export") },
                            onImportar = { nav.navigate("importar") },
                            contenidoNotas = {
                                ListaRecortesScreen(
                                    vm = recortesVm,
                                    onAbrirRecorte = { id -> nav.navigate("recorte/$id") },
                                    onScan = { nav.navigate("captura?permiso=false") },
                                )
                            },
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
                    composable("recorte/{id}") { entrada ->
                        val id = entrada.arguments!!.getString("id")!!
                        val vm: RecorteViewModel = viewModel(factory = viewModelFactory {
                            initializer {
                                RecorteViewModel(
                                    id, contenedor.recortes, contenedor.creadorRecortes,
                                    contenedor.tokenizador, contenedor.buscador,
                                    contenedor.progresoDb.dao(),
                                )
                            }
                        })
                        // RecorteScreen dispara vm.cargar() con LaunchedEffect (mismo patrón).
                        RecorteScreen(
                            vm = vm,
                            onVerKanji = { k -> nav.navigate("kanji/$k") },
                            onCerrar = { nav.popBackStack() },
                        )
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
     *  la misma captura genera un recorte duplicado. */
    private fun leerIntent(intent: Intent?) {
        when (intent?.action) {
            ScreenCaptureService.ACTION_TEXTO_OCR ->
                capturaPendiente.value = intent.getStringExtra(ScreenCaptureService.EXTRA_TEXTO_OCR)
                    ?.let { it to intent.getStringExtra(ScreenCaptureService.EXTRA_RUTA_IMAGEN) }
            FloatingBubbleService.ACTION_PEDIR_PERMISO -> pedirPermisoPendiente.value = true
            else -> return
        }
        intent.action = null
        intent.removeExtra(ScreenCaptureService.EXTRA_TEXTO_OCR)
        intent.removeExtra(ScreenCaptureService.EXTRA_RUTA_IMAGEN)
    }
}
