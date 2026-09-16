package com.tatoh.dokushorenshu.captura

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.tatoh.dokushorenshu.App
import com.tatoh.dokushorenshu.MainActivity
import com.tatoh.dokushorenshu.dominio.ocr.Recorte
import com.tatoh.dokushorenshu.dominio.ocr.escalarRecorte
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {
    
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var selectionView: SelectionOverlayView? = null
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var resultCode: Int = 0
    private var resultData: Intent? = null
    
    private var isCapturing = false
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "screen_capture_channel"
        const val ACTION_START_CAPTURE = "com.tatoh.dokushorenshu.captura.START_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** Contrato con MainActivity: el Service ya hizo el OCR y manda solo el texto.
         *  La versión Flutter mandaba el PNG crudo por MethodChannel con un callback
         *  estático; nativo no lo necesita y el texto entra holgado en un Intent. */
        const val ACTION_TEXTO_OCR = "com.tatoh.dokushorenshu.captura.TEXTO_OCR"
        const val EXTRA_TEXTO_OCR = "texto_ocr"
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("ScreenCapture", "=== onStartCommand INICIADO ===")
        android.util.Log.d("ScreenCapture", "intent.action = ${intent?.action}")
        android.util.Log.d("ScreenCapture", "isCapturing = $isCapturing")
        
        if (intent?.action == ACTION_START_CAPTURE) {
            // Evitar inicios duplicados
            if (isCapturing) {
                android.util.Log.w("ScreenCapture", "Ya hay una captura en curso, ignorando")
                return START_NOT_STICKY
            }
            
            isCapturing = true
            resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA)
            
            android.util.Log.d("ScreenCapture", "resultCode = $resultCode")
            android.util.Log.d("ScreenCapture", "resultData = $resultData")
            
            // Combinar MEDIA_PROJECTION (requerido) y SPECIAL_USE (para evitar restricciones)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                android.util.Log.d("ScreenCapture", "startForeground() llamado con tipos múltiples")
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
                android.util.Log.d("ScreenCapture", "startForeground() llamado (API < 34)")
            }
            
            android.util.Log.d("ScreenCapture", "Llamando a showOverlay()...")
            showOverlay()
        }
        
        android.util.Log.d("ScreenCapture", "=== onStartCommand FINALIZADO ===")
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Screen capture service is running"
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen capture active")
            .setContentText("Select the area to capture")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun showOverlay() {
        android.util.Log.d("ScreenCapture", "=== showOverlay() INICIADO ===")
        
        // IMPORTANTE: Ocultar el bubble flotante mientras se muestra el overlay de captura
        FloatingBubbleService.hideBubble()
        
        // Primero obtener las métricas reales de la pantalla
        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        val screenHeight = metrics.heightPixels
        
        android.util.Log.d("ScreenCapture", "Screen dimensions: ${metrics.widthPixels}x$screenHeight")
        
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            screenHeight, // Usar altura exacta en lugar de MATCH_PARENT
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // IMPORTANTE PARA MIUI: Remover FLAG_NOT_FOCUSABLE para que el overlay aparezca en primer plano
            // El overlay NECESITA recibir foco para mostrarse sobre otras ventanas
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, // Mantener pantalla encendida durante captura
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = 0
            // MIUI: Forzar que la ventana esté al frente
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        
        val container = FrameLayout(this)
        
        // Vista de selección (área transparente con bordes)
        selectionView = SelectionOverlayView(this)
        container.addView(selectionView)
        
        // Botón de captura (estilo Material Design elevado)
        val captureButtonDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#4CAF50"))
            cornerRadius = 24f
        }
        
        val captureButton = Button(this).apply {
            text = "Capture"
            textSize = 16f
            isAllCaps = false
            setPadding(48, 24, 48, 24)
            background = captureButtonDrawable
            setTextColor(Color.WHITE)
            elevation = 8f
            stateListAnimator = null
            
            setOnClickListener {
                android.util.Log.d("ScreenCapture", "Botón CAPTURAR presionado")
                captureScreen()
            }
        }
        val captureParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 50
        }
        container.addView(captureButton, captureParams)
        
        // Botón de cancelar (estilo Material Design elevado)
        val cancelButtonDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#5f6368"))
            cornerRadius = 24f
        }
        
        val cancelButton = Button(this).apply {
            text = "Cancel"
            textSize = 16f
            isAllCaps = false
            setPadding(48, 24, 48, 24)
            background = cancelButtonDrawable
            setTextColor(Color.WHITE)
            elevation = 8f
            stateListAnimator = null
            
            setOnClickListener {
                android.util.Log.d("ScreenCapture", "Botón CANCELAR presionado")
                stopOverlay()
            }
        }
        val cancelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = 50
            marginEnd = 20
        }
        container.addView(cancelButton, cancelParams)
        
        overlayView = container
        windowManager?.addView(overlayView, layoutParams)
        android.util.Log.d("ScreenCapture", "Overlay añadido a WindowManager")
        android.util.Log.d("ScreenCapture", "=== showOverlay() FINALIZADO ===")
    }
    
    private fun captureScreen() {
        android.util.Log.d("ScreenCapture", "=== captureScreen() INICIADO ===")
        
        // Primero ocultar el overlay y esperar un momento
        hideOverlayTemporarily()
        android.util.Log.d("ScreenCapture", "Overlay ocultado temporalmente")
        
        // Delay para que el overlay se oculte completamente
        Handler(Looper.getMainLooper()).postDelayed({
            android.util.Log.d("ScreenCapture", "Iniciando creación de MediaProjection...")
            
            val metrics = DisplayMetrics()
            windowManager?.defaultDisplay?.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            
            android.util.Log.d("ScreenCapture", "Creando ImageReader: ${width}x${height}, density=$density")
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            // Crear NUEVO MediaProjection cada vez (Android no permite reusar el mismo)
            // IMPORTANTE: esto invalida el token anterior
            mediaProjection?.stop()
            mediaProjection = null
            
            try {
                android.util.Log.d("ScreenCapture", "Obteniendo MediaProjection con resultCode=$resultCode")
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData!!)
                android.util.Log.d("ScreenCapture", "MediaProjection obtenido: $mediaProjection")
                
                // Registrar callback requerido en Android 14+
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        android.util.Log.d("ScreenCapture", "MediaProjection.Callback.onStop() llamado")
                    }
                }, Handler(Looper.getMainLooper()))
                
                android.util.Log.d("ScreenCapture", "Creando VirtualDisplay...")
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    null
                )
                android.util.Log.d("ScreenCapture", "VirtualDisplay creado: $virtualDisplay")
                
                // Esperar un poco más para que la captura se complete
                android.util.Log.d("ScreenCapture", "Esperando 200ms antes de procesar captura...")
                Handler(Looper.getMainLooper()).postDelayed({
                    android.util.Log.d("ScreenCapture", "Llamando a processCapture()...")
                    processCapture()
                }, 200)
            } catch (e: SecurityException) {
                android.util.Log.e("ScreenCapture", "SecurityException - Token de MediaProjection expirado o inválido", e)
                // Android 14+ invalida el token después de cada sesión. Se borran las
                // credenciales guardadas: el próximo tap del bubble abrirá la app para
                // pedirlo de nuevo, que es exactamente lo que hace falta.
                FloatingBubbleService.captureResultCode = 0
                FloatingBubbleService.captureResultData = null
                stopOverlay()
            } catch (e: Exception) {
                android.util.Log.e("ScreenCapture", "Error creando MediaProjection", e)
                stopOverlay()
            }
        }, 100)
        
        android.util.Log.d("ScreenCapture", "=== captureScreen() configuración completada, esperando delay ===")
    }
    
    private fun hideOverlayTemporarily() {
        overlayView?.visibility = View.INVISIBLE
    }
    
    private fun processCapture() {
        android.util.Log.d("ScreenCapture", "=== processCapture() INICIADO ===")

        val image = try {
            imageReader?.acquireLatestImage()
        } catch (e: Exception) {
            android.util.Log.e("ScreenCapture", "Error adquiriendo imagen", e)
            null
        }

        if (image == null) {
            android.util.Log.e("ScreenCapture", "No se pudo obtener imagen del ImageReader")
            cerrarConDemora()
            return
        }

        // Preparar el bitmap puede fallar: un ARGB_8888 de pantalla completa son
        // decenas de MB y el OOM es realista. Si la excepción se escapa, corre por el
        // main thread, crashea la app, deja la Image sin cerrar y sobre todo deja la
        // ventana del overlay pegada a pantalla completa, o sea el teléfono inusable.
        // El finally cierra la Image pase lo que pase (el original tenía el mismo
        // try/finally alrededor de todo este tramo).
        val bitmaps = try {
            prepararBitmaps(image)
        } catch (e: Exception) {
            android.util.Log.e("ScreenCapture", "Error preparando el bitmap de la captura", e)
            null
        } finally {
            image.close()
        }

        if (bitmaps == null) {
            cerrarConDemora()
            return
        }
        val (bitmap, recortado) = bitmaps

        // Soltar la ventana del overlay ANTES de arrancar el OCR. Estar INVISIBLE no
        // alcanza: la ventana sigue adjunta, ocupa la pantalla entera y el workaround
        // de MIUI le saca FLAG_NOT_FOCUSABLE a propósito, así que se come los toques y
        // el botón atrás mientras dure el reconocimiento — cientos de ms lo normal,
        // hasta 15 s si el OCR agota el timeout. También devuelve el bubble.
        // El Service NO se cierra acá: lo necesitamos vivo para correr el reconocedor
        // y después abrir la app con el texto.
        liberarVentanaOverlay()

        // Tasks.await() de ML Kit lanza si corre en el main thread, y processCapture()
        // llega acá desde un Handler del main looper. De paso, el OCR de una captura
        // grande tarda cientos de ms y no debe bloquear la UI del overlay.
        Thread {
            var ocrOk = false
            val texto = try {
                val t = (application as App).contenedor.ocr.reconocer(recortado)
                ocrOk = true
                t
            } catch (e: Exception) {
                android.util.Log.e("ScreenCapture", "OCR falló", e)
                ""
            }
            android.util.Log.d("ScreenCapture", "OCR devolvió ${texto.length} chars")

            // `bitmap` nunca se le pasó a ML Kit, así que reciclarlo siempre es seguro
            // (identidad, no equals: si no hubo recorte son el mismo objeto).
            if (recortado !== bitmap) bitmap.recycle()
            // `recortado` SÓLO se recicla si el OCR terminó bien. En el camino de error
            // —sobre todo el TimeoutException— Tasks.await() deja de esperar pero NO
            // cancela la tarea: el reconocedor sigue corriendo y su InputImage todavía
            // apunta a estos píxeles. Reciclarlo acá es liberarle la memoria de abajo a
            // un worker nativo vivo: crash. No reciclarlo no filtra nada, el GC lo
            // levanta cuando ML Kit suelta la referencia.
            if (ocrOk) recortado.recycle()

            entregarTexto(texto)
            Handler(Looper.getMainLooper()).post { terminarServicio() }
        }.start()
    }

    /** Pasa la Image a Bitmap y le aplica el recorte de la selección. Devuelve
     *  (completo, recortado); sin selección usable ambos son el MISMO objeto, que es
     *  lo que después distingue el reciclado. No cierra la Image: de eso se encarga
     *  el finally de quien llama. */
    private fun prepararBitmaps(image: Image): Pair<Bitmap, Bitmap> {
        val bitmap = imageToBitmap(image)
        android.util.Log.d("ScreenCapture", "Bitmap creado: ${bitmap.width}x${bitmap.height}")

        val seleccion = selectionView?.getSelectionRect()
        val anchoOverlay = selectionView?.width ?: 0
        val altoOverlay = selectionView?.height ?: 0

        // El overlay y el bitmap no miden lo mismo (barras de sistema): sin escalar,
        // el recorte cae corrido. escalarRecorte devuelve null si el resultado no sirve
        // y ahí se usa el bitmap entero.
        val recorte = seleccion?.let {
            escalarRecorte(
                Recorte(it.left, it.top, it.width(), it.height()),
                anchoOverlay, altoOverlay, bitmap.width, bitmap.height,
            )
        }
        android.util.Log.d("ScreenCapture", "Recorte escalado: $recorte")

        val recortado = if (recorte != null) {
            Bitmap.createBitmap(bitmap, recorte.left, recorte.top, recorte.ancho, recorte.alto)
        } else {
            bitmap
        }
        return bitmap to recortado
    }

    /** Abre la app con el texto reconocido. Arrancar una Activity desde background
     *  está bloqueado desde Android 10, pero la app queda exenta por tener
     *  SYSTEM_ALERT_WINDOW concedido — que es el permiso del propio overlay. */
    private fun entregarTexto(texto: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_TEXTO_OCR
            putExtra(EXTRA_TEXTO_OCR, texto)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    private fun cerrarConDemora() {
        // demora para que el Intent de apertura alcance a procesarse antes de
        // desmontar el overlay (comportamiento heredado que evita un parpadeo).
        Handler(Looper.getMainLooper()).postDelayed({ stopOverlay() }, 500)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        
        // Crear bitmap con padding extra para acomodar el rowStride
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        
        // Copiar directamente desde el buffer
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        
        // Si hay padding, recortar al tamaño real
        val finalBitmap = if (rowPadding != 0) {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } else {
            bitmap
        }
        
        if (finalBitmap != bitmap) {
            bitmap.recycle()
        }
        
        return finalBitmap
    }
    
    /** Desmonta la ventana del overlay y devuelve el bubble, sin tocar el Service.
     *  Idempotente: processCapture() la llama antes del OCR y después el stopOverlay()
     *  final vuelve a pasar por acá, así que la baja de la vista va guardada. */
    private fun liberarVentanaOverlay() {
        // Mostrar el bubble de nuevo
        FloatingBubbleService.showBubble()

        val vista = overlayView ?: return
        try {
            windowManager?.removeView(vista)
            android.util.Log.d("ScreenCapture", "Overlay removido de WindowManager")
        } catch (e: Exception) {
            android.util.Log.e("ScreenCapture", "Error removiendo overlay", e)
            e.printStackTrace()
        }

        overlayView = null
        selectionView = null
    }

    /** Cierra el Service. Va aparte de liberarVentanaOverlay() porque mientras corre
     *  el OCR la ventana ya está desmontada pero el Service tiene que seguir vivo:
     *  todavía le falta reconocer el texto y abrir la app con el resultado. */
    private fun terminarServicio() {
        cleanup()

        // cleanup() (arriba) ya borró las credenciales de MediaProjection: Android 14+
        // invalida el token después de cada sesión, así que reusarlo no es opción y
        // guardarlo sólo lograría que la próxima captura falle con SecurityException.
        // El próximo tap del bubble vuelve a pedir el permiso, y eso es lo esperado.

        isCapturing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopOverlay() {
        android.util.Log.d("ScreenCapture", "=== stopOverlay() INICIADO ===")

        liberarVentanaOverlay()
        terminarServicio()

        android.util.Log.d("ScreenCapture", "=== stopOverlay() FINALIZADO ===")
    }

    private fun cleanup() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        
        // INVALIDAR credenciales después de cada captura
        // Android 14+ solo permite usar el token UNA vez
        FloatingBubbleService.captureResultCode = 0
        FloatingBubbleService.captureResultData = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        try {
            overlayView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // Vista personalizada para selección de área
    inner class SelectionOverlayView(context: Context) : View(context) {
        
        private val paint = Paint().apply {
            color = Color.parseColor("#40FFFFFF")
            style = Paint.Style.FILL
        }
        
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#FF4CAF50")
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        
        private val dimPaint = Paint().apply {
            color = Color.parseColor("#AA000000")
            style = Paint.Style.FILL
        }
        
        private var startX = 0f
        private var startY = 0f
        private var endX = 0f
        private var endY = 0f
        private var isDragging = false
        
        init {
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.x
                        startY = event.y
                        endX = event.x
                        endY = event.y
                        isDragging = true
                        invalidate()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isDragging) {
                            endX = event.x
                            endY = event.y
                            invalidate()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        isDragging = false
                        invalidate()
                        true
                    }
                    else -> false
                }
            }
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            val left = minOf(startX, endX)
            val top = minOf(startY, endY)
            val right = maxOf(startX, endX)
            val bottom = maxOf(startY, endY)
            
            // Dibujar overlay oscuro en toda la pantalla
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            
            // "Recortar" el área seleccionada (dibujarla clara)
            canvas.drawRect(left, top, right, bottom, paint)
            
            // Dibujar borde del área seleccionada
            if (right - left > 10 && bottom - top > 10) {
                canvas.drawRect(left, top, right, bottom, borderPaint)
            }
        }
        
        fun getSelectionRect(): Rect? {
            val left = minOf(startX, endX).toInt()
            val top = minOf(startY, endY).toInt()
            val right = maxOf(startX, endX).toInt()
            val bottom = maxOf(startY, endY).toInt()
            
            return if (right - left > 10 && bottom - top > 10) {
                Rect(left, top, right, bottom)
            } else {
                null
            }
        }
    }
}
