package com.tatoh.dokushorenshu

import android.app.Application

/** Contenedor de dependencias (DI manual). Se puebla en las tasks 2-8. */
class Contenedor(app: Application)

class App : Application() {
    lateinit var contenedor: Contenedor
        private set

    override fun onCreate() {
        super.onCreate()
        contenedor = Contenedor(this)
    }
}
