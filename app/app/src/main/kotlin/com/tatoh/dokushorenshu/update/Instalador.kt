package com.tatoh.dokushorenshu.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

enum class EstadoDescarga { PENDIENTE, CORRIENDO, PAUSADA, EXITOSA, FALLIDA }

/** Foto del estado de una descarga en `DownloadManager`. */
data class Descarga(val estado: EstadoDescarga, val bytesSoFar: Long, val bytesTotal: Long) {
    /** 0..1, o `null` mientras no se conoce el total. */
    val progreso: Float?
        get() = if (bytesTotal > 0) (bytesSoFar.toFloat() / bytesTotal).coerceIn(0f, 1f) else null
}

/** Lado Android del updater: DownloadManager, permiso de instalación e intent del
 *  instalador. Port de `UpdaterPlugin.kt` de Pulpero sin MethodChannel. Sin
 *  FileProvider: DownloadManager ya expone la descarga como `content://`. */
class Instalador(private val context: Context) {

    private val downloadManager
        get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** minSdk 26: el permiso de "instalar apps desconocidas" es por app, no global. */
    fun puedeInstalar(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun abrirAjustesInstalacion() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Encola la descarga y devuelve su id. Lanza si DownloadManager no está disponible. */
    fun encolarDescarga(url: String): Long {
        // DownloadManager falla con ERROR_FILE_ALREADY_EXISTS si el destino existe
        // (una descarga anterior que no se instaló).
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), ARCHIVO).delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Dokusho Renshū")
            .setDescription("Downloading the update")
            .setMimeType(MIME_APK)
            // NOTIFY_COMPLETED, no VISIBLE: si el sistema mata la app durante la
            // descarga, la notificación al completar sigue ahí para instalar igual.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, ARCHIVO)
        return downloadManager.enqueue(request)
    }

    /** Descarta una descarga en curso: sin esto, un Retry encolaría una segunda descarga
     *  escribiendo el mismo `update.apk` que la anterior. Nunca lanza. */
    fun cancelarDescarga(id: Long) {
        runCatching { downloadManager.remove(id) }
    }

    fun consultarDescarga(id: Long): Descarga =
        downloadManager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) {
                // La descarga desapareció (la canceló el usuario desde la notificación,
                // o el sistema la limpió).
                return Descarga(EstadoDescarga.FALLIDA, 0, 0)
            }
            val estado = when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_PENDING -> EstadoDescarga.PENDIENTE
                DownloadManager.STATUS_RUNNING -> EstadoDescarga.CORRIENDO
                DownloadManager.STATUS_PAUSED -> EstadoDescarga.PAUSADA
                DownloadManager.STATUS_SUCCESSFUL -> EstadoDescarga.EXITOSA
                else -> EstadoDescarga.FALLIDA
            }
            Descarga(
                estado,
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }

    /** Verifica el sha256 de la descarga y, si coincide, abre el instalador de Android.
     *  Si no coincide (o cualquier cosa falla) borra la descarga y devuelve false: no se
     *  instala nada corrupto. El hash de ~85 MB tarda segundos: corre en IO. */
    suspend fun verificarEInstalar(id: Long, sha256: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = downloadManager.getUriForDownloadedFile(id)
            val ok = uri != null && sha256(uri) == sha256.lowercase()
            if (ok) {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, MIME_APK)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                downloadManager.remove(id)
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "verificarEInstalar falló", e)
            runCatching { downloadManager.remove(id) }
            false
        }
    }

    private fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val leidos = input.read(buffer)
                if (leidos < 0) break
                digest.update(buffer, 0, leidos)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "Updater"
        const val ARCHIVO = "update.apk"
        const val MIME_APK = "application/vnd.android.package-archive"
    }
}
