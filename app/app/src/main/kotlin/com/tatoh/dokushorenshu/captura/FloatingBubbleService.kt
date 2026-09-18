package com.tatoh.dokushorenshu.captura

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.tatoh.dokushorenshu.MainActivity

/**
 * Servicio que muestra un ícono flotante persistente (bubble) sobre otras apps
 * Similar a los "chat heads" de Facebook Messenger
 */
class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var burbuja: BurbujaFlotante? = null

    companion object {
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "floating_bubble_channel"
        const val ACTION_START_BUBBLE = "com.tatoh.dokushorenshu.captura.START_BUBBLE"
        const val ACTION_STOP_BUBBLE = "com.tatoh.dokushorenshu.captura.STOP_BUBBLE"

        /** Contrato con MainActivity: el bubble se tocó sin credenciales de
         *  MediaProjection vigentes; hay que pedirlas. */
        const val ACTION_PEDIR_PERMISO = "com.tatoh.dokushorenshu.captura.PEDIR_PERMISO"

        var isRunning = false
        var captureResultCode: Int = 0
        var captureResultData: Intent? = null
        
        // Referencia estática para poder ocultar/mostrar el bubble desde otros servicios
        private var instance: FloatingBubbleService? = null
        
        /** Oculta o devuelve el bubble ya creado (lo usa ScreenCaptureService mientras
         *  el overlay de selección está arriba). NO se llama setBubbleVisible por gusto:
         *  el nombre showBubble ya lo usa el método de instancia que CREA la vista, y
         *  tener los dos colisionando era una trampa esperando a que alguien llame al
         *  equivocado. */
        fun setBubbleVisible(visible: Boolean) {
            instance?.burbuja?.visible(visible)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BUBBLE -> {
                if (!isRunning) {
                    // Usar el tipo correcto de foreground service en Android 14+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(
                            NOTIFICATION_ID,
                            createNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, createNotification())
                    }
                    burbuja = BurbujaFlotante(this, windowManager!!, ::onBubbleClicked).also { it.mostrar() }
                    isRunning = true
                }
            }
            ACTION_STOP_BUBBLE -> {
                stopBubble()
            }
        }
        // START_NOT_STICKY y no START_STICKY: en un reinicio el sistema reentrega un
        // intent null, que no cae en ninguna de las dos ramas — o sea el Service revivía
        // sin foreground y sin bubble, un fantasma. Mejor no revivir.
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Floating button",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Always-visible floating capture button"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun createNotification(): Notification {
        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP_BUBBLE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quick capture active")
            .setContentText("Tap the floating button to capture")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }
    
    private fun onBubbleClicked() {
        android.util.Log.d("FloatingBubble", "=== onBubbleClicked() INICIADO ===")
        android.util.Log.d("FloatingBubble", "captureResultCode = $captureResultCode")
        android.util.Log.d("FloatingBubble", "captureResultData = $captureResultData")
        
        // Verificar si tenemos los datos de MediaProjection guardados
        if (captureResultCode != 0 && captureResultData != null) {
            android.util.Log.d("FloatingBubble", "Tenemos credenciales - iniciando captura SIN abrir app")
            
            // Iniciar servicio de captura DIRECTAMENTE sin abrir la app
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START_CAPTURE
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, captureResultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, captureResultData)
            }
            
            try {
                startForegroundService(serviceIntent)
                android.util.Log.d("FloatingBubble", "startForegroundService() llamado exitosamente")
            } catch (e: Exception) {
                android.util.Log.e("FloatingBubble", "ERROR al iniciar ScreenCaptureService", e)
            }
        } else {
            android.util.Log.d("FloatingBubble", "NO hay credenciales - abriendo MainActivity para pedir permisos")
            // Un solo Intent con acción propia: MainActivity abre la pantalla de captura
            // y dispara el diálogo de MediaProjection. El original mandaba dos Intents
            // con un postDelayed de 500 ms entre medio, que era una carrera.
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = ACTION_PEDIR_PERMISO
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
        
        android.util.Log.d("FloatingBubble", "=== onBubbleClicked() FINALIZADO ===")
    }
    
    private fun stopBubble() {
        burbuja?.ocultar()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopBubble()
    }
}
