package com.tatoh.dokushorenshu

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tatoh.dokushorenshu.captura.CapturaService
import com.tatoh.dokushorenshu.ui.acerca.AcercaScreen
import com.tatoh.dokushorenshu.ui.biblioteca.BibliotecaScreen
import com.tatoh.dokushorenshu.ui.biblioteca.BibliotecaViewModel
import com.tatoh.dokushorenshu.ui.captura.CapturaScreen
import com.tatoh.dokushorenshu.ui.captura.SOPORTADO
import com.tatoh.dokushorenshu.ui.captura.intentDeProyeccion
import com.tatoh.dokushorenshu.ui.captura.iniciarCaptura
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
import com.tatoh.dokushorenshu.update.UpdateBanner
import com.tatoh.dokushorenshu.update.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    /** Texto + ruta de la imagen que dejó CapturaService en el Intent,
     *  esperando a convertirse en recorte. La ruta es opcional: si el guardado del
     *  JPEG falló, el recorte se crea igual pero sin imagen. Es estado de la Activity
     *  y no del NavHost porque puede llegar por onNewIntent con la app ya abierta
     *  (launchMode singleTop). */
    private val capturaPendiente = mutableStateOf<Pair<String, String?>?>(null)

    /** El bubble se tocó sin sesión de MediaProjection: hay que abrir la
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
                // A nivel Activity, no dentro del NavHost: el banner es global y su estado
                // (descarga en curso) tiene que sobrevivir a la navegación y a rotar.
                val updateVm: UpdateViewModel = viewModel(factory = viewModelFactory {
                    initializer { UpdateViewModel(contenedor.updateChecker, contenedor.instalador) }
                })
                val pedirPermiso by pedirPermisoPendiente
                // Llegó una captura: se vuelve recorte y se abre. Si ya había un recorte
                // abierto se lo reemplaza, para no apilar pantallas si llegan varias capturas.
                //
                // La key es Unit y NO `capturaPendiente`: keyeado en el valor, el propio
                // `= null` de abajo cambia la key y LaunchedEffect cancela su job mientras
                // sigue suspendido en el withContext esperando a Kuromoji — el recorte se
                // crea pero el navigate nunca corre. Con Unit, la identidad del efecto no
                // depende del valor y consumirlo no cancela nada. No volver a
                // LaunchedEffect(captura) "para simplificar".
                LaunchedEffect(Unit) {
                    snapshotFlow { capturaPendiente.value }.filterNotNull().collect { actual ->
                        // Consumido ANTES de la IO: si se limpiara recién después de navegar,
                        // rotar en el medio dejaría el valor y la nueva composición crearía un
                        // segundo recorte de la misma captura.
                        capturaPendiente.value = null
                        val (texto, ruta) = actual
                        // crear() bloquea en Kuromoji: jamás en el main thread.
                        val recorte = withContext(Dispatchers.IO) {
                            runCatching { contenedor.creadorRecortes.crear(texto, ruta?.let(::File)) }
                        }
                        recorte.onSuccess { nuevo ->
                            // Con un recorte abierto NO sirve launchSingleTop: la ruta es la
                            // misma, así que reusaba la entrada de arriba con su ViewModel y su
                            // id viejo, y la pantalla seguía mostrando la captura anterior.
                            // Se guarda el borrador del que está abierto y se lo reemplaza.
                            val arriba = nav.currentBackStackEntry
                            if (arriba?.destination?.route == "recorte/{id}") {
                                val guardado = runCatching {
                                    ViewModelProvider(arriba)[RecorteViewModel::class.java].guardarPendiente()
                                }
                                // Si no se pudo guardar, el viejo queda abajo con su borrador
                                // intacto (Back vuelve a él) en vez de cerrarse y perderlo.
                                if (guardado.isSuccess) {
                                    nav.popBackStack()
                                } else {
                                    android.util.Log.e("MainActivity", "no se pudo guardar el recorte abierto", guardado.exceptionOrNull())
                                    Toast.makeText(this@MainActivity, "Could not save the note", Toast.LENGTH_LONG).show()
                                }
                            }
                            nav.navigate("recorte/${nuevo.id}")
                        }
                        recorte.onFailure {
                            // Sin aviso el usuario se queda mirando la biblioteca sin saber que
                            // su captura se perdió: el texto original ya no existe en ningún lado.
                            android.util.Log.e("MainActivity", "no se pudo crear el recorte", it)
                            Toast.makeText(this@MainActivity, "Could not save the capture", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                // El diálogo del sistema se lanza desde acá y NO desde CapturaScreen:
                // con la pantalla Scan ya arriba, launchSingleTop reusa su entrada del
                // backstack, así que un LaunchedEffect de la pantalla no vuelve a correr
                // y el segundo tap del bubble se quedaba sin diálogo — la app pasaba al
                // frente y no hacía nada. Acá el launcher es de la Activity y no depende
                // de qué pantalla esté arriba.
                val lanzadorProyeccion = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { resultado ->
                    val datos = resultado.data
                    if (resultado.resultCode == Activity.RESULT_OK && datos != null) {
                        iniciarCaptura(this@MainActivity, resultado.resultCode, datos)
                    }
                }
                LaunchedEffect(pedirPermiso) {
                    if (pedirPermiso) {
                        // Consumido antes de lanzar nada: el flag es de la Activity y se
                        // rearma sólo con un Intent nuevo, así que rotar no repite el
                        // diálogo (leerIntent() ya borra la action del Intent consumido).
                        pedirPermisoPendiente.value = false
                        nav.navigate("captura") { launchSingleTop = true }
                        // Sin permiso de overlay no hay captura posible: la pantalla Scan
                        // que se acaba de abrir muestra el botón para concederlo.
                        if (SOPORTADO && Settings.canDrawOverlays(this@MainActivity)) {
                            lanzadorProyeccion.launch(intentDeProyeccion(this@MainActivity))
                        }
                    }
                }
                val bannerVisible = updateVm.info != null && updateVm.fase != UpdateViewModel.Fase.OCULTO
                Column(Modifier.fillMaxSize()) {
                    UpdateBanner(updateVm)
                    // Con el banner arriba, él ya absorbió el inset de la status bar: las
                    // pantallas de abajo no tienen que volver a padear (evita el doble hueco).
                    Box(
                        Modifier
                            .weight(1f)
                            .consumeWindowInsets(if (bannerVisible) WindowInsets.statusBars else WindowInsets(0, 0, 0, 0))
                    ) {
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
                                            onScan = { nav.navigate("captura") },
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
                                            contenedor.progresoDb.dao(), contenedor.recortadorOcr,
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
                            composable("captura") {
                                CapturaScreen(onCerrar = { nav.popBackStack() })
                            }
                            composable("acerca") { AcercaScreen() }
                        }
                    }
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
            CapturaService.ACTION_TEXTO_OCR ->
                capturaPendiente.value = intent.getStringExtra(CapturaService.EXTRA_TEXTO_OCR)
                    ?.let { it to intent.getStringExtra(CapturaService.EXTRA_RUTA_IMAGEN) }
            CapturaService.ACTION_PEDIR_PERMISO -> pedirPermisoPendiente.value = true
            else -> return
        }
        intent.action = null
        intent.removeExtra(CapturaService.EXTRA_TEXTO_OCR)
        intent.removeExtra(CapturaService.EXTRA_RUTA_IMAGEN)
    }
}
