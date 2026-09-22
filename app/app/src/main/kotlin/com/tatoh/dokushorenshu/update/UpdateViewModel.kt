package com.tatoh.dokushorenshu.update

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Máquina de estados del updater: aviso → permiso → descarga → verificación →
 *  instalador. Vive a nivel Activity para sobrevivir rotaciones: el banner solo pinta. */
class UpdateViewModel(
    private val checker: UpdateChecker,
    private val instalador: Instalador,
) : ViewModel() {

    enum class Fase { OCULTO, AVISO, SIN_PERMISO, DESCARGANDO, VERIFICANDO, LISTO, ERROR }

    var info by mutableStateOf<ReleaseInfo?>(null); private set
    var fase by mutableStateOf(Fase.OCULTO); private set
    /** 0..1, o null si no se conoce el total. */
    var progreso by mutableStateOf<Float?>(null); private set
    var error by mutableStateOf(""); private set

    private var downloadId: Long? = null
    private var seguimiento: Job? = null

    init {
        viewModelScope.launch {
            checker.chequear()?.let { info = it; fase = Fase.AVISO }
        }
    }

    /** "Not now": oculta hasta el próximo chequeo (24 h). Una descarga en curso sigue en
     *  DownloadManager con su notificación del sistema. */
    fun cerrar() {
        seguimiento?.cancel()
        fase = Fase.OCULTO
    }

    /** Vuelve de Ajustes: si ya habilitó la instalación, arranca solo. */
    fun alVolverDeAjustes() {
        if (fase == Fase.SIN_PERMISO) actualizar()
    }

    fun actualizar() {
        val url = info?.apkUrl ?: return
        try {
            if (!instalador.puedeInstalar()) {
                fase = Fase.SIN_PERMISO
                return
            }
            downloadId?.let { instalador.cancelarDescarga(it) }
            seguimiento?.cancel()
            progreso = null
            val id = instalador.encolarDescarga(url)
            downloadId = id
            fase = Fase.DESCARGANDO
            seguimiento = viewModelScope.launch { seguir(id) }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo encolar la descarga", e)
            fallar("Download failed.")
        }
    }

    fun abrirAjustes() {
        try {
            instalador.abrirAjustesInstalacion()
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo abrir Ajustes", e)
            fallar("Could not open Settings.")
        }
    }

    /** Botón "Install" de la fase LISTO: el usuario canceló el instalador (o Android lo
     *  rechazó) y hay que poder reintentar sin volver a descargar. */
    fun instalar() {
        val id = downloadId ?: return
        if (fase == Fase.VERIFICANDO) return // doble tap: una sola verificación a la vez
        fase = Fase.VERIFICANDO // sincrónico: que el guard de arriba corte el segundo tap
        viewModelScope.launch { verificarEInstalar(id) }
    }

    private suspend fun seguir(id: Long) {
        while (true) {
            val d = try {
                instalador.consultarDescarga(id)
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo consultar la descarga", e)
                fallar("Download failed.")
                return
            }
            when (d.estado) {
                EstadoDescarga.EXITOSA -> { verificarEInstalar(id); return }
                EstadoDescarga.FALLIDA -> { fallar("Download failed."); return }
                EstadoDescarga.PENDIENTE, EstadoDescarga.CORRIENDO, EstadoDescarga.PAUSADA -> progreso = d.progreso
            }
            delay(1_000)
        }
    }

    private suspend fun verificarEInstalar(id: Long) {
        val sha256 = info?.sha256 ?: return
        fase = Fase.VERIFICANDO
        if (instalador.verificarEInstalar(id, sha256)) fase = Fase.LISTO
        else fallar("The download was corrupted, try again.")
    }

    private fun fallar(mensaje: String) {
        error = mensaje
        fase = Fase.ERROR
    }

    private companion object {
        const val TAG = "Updater"
    }
}
