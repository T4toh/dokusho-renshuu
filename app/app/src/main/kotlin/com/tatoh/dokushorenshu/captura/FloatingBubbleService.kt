package com.tatoh.dokushorenshu.captura

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tatoh.dokushorenshu.MainActivity
import com.tatoh.dokushorenshu.R
import kotlin.math.abs

/**
 * Servicio que muestra un ícono flotante persistente (bubble) sobre otras apps
 * Similar a los "chat heads" de Facebook Messenger
 */
class FloatingBubbleService : Service() {
    
    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    
    private var isDragging = false
    private val DRAG_THRESHOLD = 10 // pixels
    private val BUBBLE_SIZE = 56 // dp - tamaño estándar de FAB en Material Design
    
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
            instance?.bubbleView?.visibility = if (visible) View.VISIBLE else View.GONE
            android.util.Log.d("FloatingBubble", "Bubble visible=$visible")
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
                    showBubble()
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
    
    private fun showBubble() {
        val bubbleSize = (BUBBLE_SIZE * resources.displayMetrics.density).toInt()
        
        // Configurar parámetros del bubble
        val layoutParams = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // - FLAG_NOT_FOCUSABLE: la burbuja no toma el foco de entrada. Sin este flag
            //   —se sacaba a propósito, como workaround de MIUI heredado de
            //   Kanji-no-Ryoushi— la ventana se queda con el foco mientras exista: el Back
            //   muere en toda la UI y en HyperOS se van también los toques a la Activity.
            //   La burbuja sólo maneja toques, no teclas, así que no necesita el foco.
            // - FLAG_NOT_TOUCH_MODAL: permite toques fuera del bubble sin bloquear otras apps
            // - FLAG_WATCH_OUTSIDE_TOUCH: recibe notificaciones de toques externos
            // - FLAG_LAYOUT_NO_LIMITS: permite posicionar en cualquier parte de la pantalla
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
            // CRÍTICO para MIUI: configurar correctamente el formato de entrada
            format = PixelFormat.TRANSLUCENT
        }
        
        // Crear vista del bubble (ícono de la app con fondo indigo, cortado en círculo)
        bubbleView = ImageView(this).apply {
            // Usar el ícono de la app
            setImageDrawable(ContextCompat.getDrawable(context, R.mipmap.ic_launcher))
            scaleType = ImageView.ScaleType.CENTER_CROP // Cortar el ícono al círculo
            
            // Fondo circular indigo (color de la AppBar)
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#9FA8DA")) // Indigo Accent (light theme inversePrimary)
            }
            background = drawable
            
            // Sin padding para que el ícono llene todo
            setPadding(0, 0, 0, 0)
            
            // Hacer que el clip sea circular
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            
            alpha = 0.95f
            elevation = 8f
            
            // Configurar listeners para drag y click
            setOnTouchListener(object : View.OnTouchListener {
                private var downTime: Long = 0
                private var moved = false
                
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    android.util.Log.d("FloatingBubble", "onTouch: action=${event.action}, actionString=${event.actionToString()}")
                    
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            android.util.Log.d("FloatingBubble", "ACTION_DOWN recibido")
                            // Guardar estado inicial
                            initialX = layoutParams.x
                            initialY = layoutParams.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            downTime = System.currentTimeMillis()
                            moved = false
                            isDragging = false
                            alpha = 1.0f
                            
                            // IMPORTANTE: Devolver true para reclamar el evento
                            return true
                        }
                        
                        MotionEvent.ACTION_MOVE -> {
                            val deltaX = event.rawX - initialTouchX
                            val deltaY = event.rawY - initialTouchY
                            
                            // Determinar si se movió lo suficiente para considerar drag
                            if (abs(deltaX) > DRAG_THRESHOLD || abs(deltaY) > DRAG_THRESHOLD) {
                                moved = true
                                isDragging = true
                            }
                            
                            if (isDragging) {
                                // Actualizar posición del bubble
                                layoutParams.x = initialX + deltaX.toInt()
                                layoutParams.y = initialY + deltaY.toInt()
                                
                                try {
                                    windowManager?.updateViewLayout(bubbleView, layoutParams)
                                } catch (e: Exception) {
                                    // Ignorar errores durante el drag
                                }
                            }
                            
                            return true
                        }
                        
                        MotionEvent.ACTION_UP -> {
                            android.util.Log.d("FloatingBubble", "ACTION_UP recibido, moved=$moved")
                            alpha = 0.9f
                            val upTime = System.currentTimeMillis()
                            val pressDuration = upTime - downTime
                            
                            // Si no se movió y fue un toque corto, es un click
                            if (!moved && pressDuration < 500) {
                                android.util.Log.d("FloatingBubble", "Click detectado! Ejecutando onBubbleClicked()")
                                // Pequeño delay para evitar conflictos con el sistema
                                v.postDelayed({
                                    onBubbleClicked()
                                }, 50)
                            } else if (isDragging) {
                                // Si fue drag, snap al borde
                                snapToEdge(layoutParams)
                            }
                            
                            return true
                        }
                        
                        MotionEvent.ACTION_CANCEL -> {
                            android.util.Log.w("FloatingBubble", "ACTION_CANCEL recibido - el sistema canceló el toque")
                            // El sistema canceló el toque - resetear estado
                            alpha = 0.9f
                            isDragging = false
                            return true
                        }
                        
                        MotionEvent.ACTION_OUTSIDE -> {
                            android.util.Log.d("FloatingBubble", "ACTION_OUTSIDE recibido")
                            // Toque fuera del bubble - ignorar
                            return false
                        }
                    }
                    return false
                }
                
                // Helper para debug
                private fun MotionEvent.actionToString(): String {
                    return when (action) {
                        MotionEvent.ACTION_DOWN -> "ACTION_DOWN"
                        MotionEvent.ACTION_UP -> "ACTION_UP"
                        MotionEvent.ACTION_MOVE -> "ACTION_MOVE"
                        MotionEvent.ACTION_CANCEL -> "ACTION_CANCEL"
                        MotionEvent.ACTION_OUTSIDE -> "ACTION_OUTSIDE"
                        else -> "UNKNOWN($action)"
                    }
                }
            })
        }
        
        try {
            windowManager?.addView(bubbleView, layoutParams)
            android.util.Log.d("FloatingBubble", "Bubble añadido correctamente a WindowManager")
            android.util.Log.d("FloatingBubble", "Layout params: width=$bubbleSize, height=$bubbleSize, type=${layoutParams.type}, flags=${layoutParams.flags}")
        } catch (e: Exception) {
            android.util.Log.e("FloatingBubble", "ERROR al añadir bubble a WindowManager", e)
            e.printStackTrace()
            stopSelf()
        }
    }
    
    private fun snapToEdge(params: WindowManager.LayoutParams) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val bubbleSize = (BUBBLE_SIZE * resources.displayMetrics.density).toInt()
        
        // Mover al borde más cercano (izquierda o derecha)
        params.x = if (params.x < screenWidth / 2) {
            20 // Margen izquierdo
        } else {
            screenWidth - bubbleSize - 20 // Margen derecho
        }
        
        windowManager?.updateViewLayout(bubbleView, params)
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
        try {
            bubbleView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        bubbleView = null
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
