package com.tatoh.dokushorenshu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tatoh.dokushorenshu.ui.acerca.AcercaScreen
import com.tatoh.dokushorenshu.ui.biblioteca.BibliotecaScreen
import com.tatoh.dokushorenshu.ui.biblioteca.BibliotecaViewModel
import com.tatoh.dokushorenshu.ui.kanji.DetalleKanjiScreen
import com.tatoh.dokushorenshu.ui.kanji.DetalleKanjiViewModel
import com.tatoh.dokushorenshu.ui.lector.LectorScreen
import com.tatoh.dokushorenshu.ui.lector.LectorViewModel
import com.tatoh.dokushorenshu.ui.tema.TemaDokusho

class MainActivity : ComponentActivity() {
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
        setContent {
            TemaDokusho {
                val nav = rememberNavController()
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
                    composable("acerca") { AcercaScreen() }
                }
            }
        }
    }
}
