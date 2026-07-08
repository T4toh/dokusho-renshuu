package com.tatoh.dokushorenshu

import android.app.Application
import com.tatoh.dokushorenshu.datos.Diccionario
import com.tatoh.dokushorenshu.datos.DiccionarioSqlite
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.progreso.PrefsRepo
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDb
import com.tatoh.dokushorenshu.dominio.BuscadorPalabras
import com.tatoh.dokushorenshu.dominio.Tokenizador

/** DI manual. Todo lazy: el primer acceso a `diccionario` copia el db de assets
 *  (79 MB) y el de `tokenizador` carga el diccionario IPADIC (~1s) — accederlos
 *  SIEMPRE desde Dispatchers.IO (los ViewModels lo hacen), nunca del main thread. */
class Contenedor(private val app: Application) {
    val progresoDb by lazy { ProgresoDb.crear(app) }
    val prefs by lazy { PrefsRepo(progresoDb.dao()) }
    val diccionario: Diccionario by lazy { DiccionarioSqlite.abrir(app) }
    val historias by lazy { HistoriasRepo.desde(app) }
    val tokenizador by lazy { Tokenizador() }
    val buscador by lazy { BuscadorPalabras(diccionario) }
}

class App : Application() {
    lateinit var contenedor: Contenedor
        private set

    override fun onCreate() {
        super.onCreate()
        contenedor = Contenedor(this)
    }
}
