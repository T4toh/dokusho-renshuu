package com.tatoh.dokushorenshu

import android.app.Application
import com.tatoh.dokushorenshu.datos.Diccionario
import com.tatoh.dokushorenshu.datos.DiccionarioSqlite
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.progreso.PrefsRepo
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDb
import com.tatoh.dokushorenshu.dominio.BuscadorPalabras
import com.tatoh.dokushorenshu.dominio.GeneradorFurigana
import com.tatoh.dokushorenshu.dominio.ImportadorHistoria
import com.tatoh.dokushorenshu.dominio.Tokenizador
import com.tatoh.dokushorenshu.dominio.anki.ArmadorMazos
import java.io.File

/** DI manual. Todo lazy: el primer acceso a `diccionario` copia el db de assets
 *  (79 MB) y el de `tokenizador` carga el diccionario IPADIC (~1s). Ojo: las
 *  factories de los ViewModels los dereferencian en composición, en el main
 *  thread — por eso `App.onCreate()` los calienta en un thread de fondo antes
 *  de que cualquier pantalla los necesite. */
class Contenedor(private val app: Application) {
    val progresoDb by lazy { ProgresoDb.crear(app) }
    val prefs by lazy { PrefsRepo(progresoDb.dao()) }
    val diccionario: Diccionario by lazy { DiccionarioSqlite.abrir(app) }
    val historias by lazy { HistoriasRepo.desde(app) }
    val tokenizador by lazy { Tokenizador() }
    val buscador by lazy { BuscadorPalabras(diccionario) }
    val armadorMazos by lazy { ArmadorMazos(progresoDb.dao(), diccionario, historias) }
    val importador by lazy { ImportadorHistoria(GeneradorFurigana(tokenizador), historias) }
    // cache, no filesDir: el .apkg es descartable, se regenera en cada export
    // (mismo criterio que FileProvider — spec Plan 4a "sin permisos de storage").
    val dirExportMazos by lazy { File(app.cacheDir, "export").apply { mkdirs() } }
}

class App : Application() {
    lateinit var contenedor: Contenedor
        private set

    override fun onCreate() {
        super.onCreate()
        contenedor = Contenedor(this)
        // Calentar las dependencias caras (Kuromoji ~1s, copia del db 79 MB) fuera del
        // main thread: la factory del ViewModel las dereferencia en composición si
        // llegan frías.
        Thread {
            val c = contenedor
            c.buscador
            c.tokenizador
        }.start()
    }
}
