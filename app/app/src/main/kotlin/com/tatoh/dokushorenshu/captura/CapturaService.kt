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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.tatoh.dokushorenshu.App
import com.tatoh.dokushorenshu.MainActivity
import com.tatoh.dokushorenshu.datos.RecortesRepo
import com.tatoh.dokushorenshu.dominio.captura.AccionTap
import com.tatoh.dokushorenshu.dominio.captura.apagarTrasCaptura
import com.tatoh.dokushorenshu.dominio.captura.decidirTap
import com.tatoh.dokushorenshu.dominio.ocr.Recorte
import com.tatoh.dokushorenshu.dominio.ocr.escalarRecorte
import java.io.File
import java.nio.ByteBuffer

class CapturaService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var selectionView: SelectionOverlayView? = null
    private var burbuja: BurbujaFlotante? = null

    /** La sesión de MediaProjection, viva mientras la burbuja exista. Sobrevive a cada
     *  captura individual — ese es el punto de la tarea 5 — y sólo muere en
     *  detenerTodo(), onDestroy() o el Callback.onStop() de más abajo. */
    private var sesion: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var isCapturing = false

    companion object {
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "captura_channel"

        /** Encender la burbuja (desde la pantalla Scan). No trae credenciales. */
        const val ACTION_INICIAR = "com.tatoh.dokushorenshu.captura.INICIAR"
        /** Abrir la sesión de MediaProjection con el resultado del consentimiento. */
        const val ACTION_ABRIR_SESION = "com.tatoh.dokushorenshu.captura.ABRIR_SESION"
        /** Apagar todo: sesión, burbuja y Service. */
        const val ACTION_DETENER = "com.tatoh.dokushorenshu.captura.DETENER"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** Contrato con MainActivity: el Service ya hizo el OCR y manda solo el texto.
         *  La versión Flutter mandaba el PNG crudo por MethodChannel con un callback
         *  estático; nativo no lo necesita y el texto entra holgado en un Intent. */
        const val ACTION_TEXTO_OCR = "com.tatoh.dokushorenshu.captura.TEXTO_OCR"
        const val EXTRA_TEXTO_OCR = "texto_ocr"

        /** Ruta absoluta del JPEG de la captura. Ausente si el guardado falló —
         *  perder la imagen nunca puede costar el texto. */
        const val EXTRA_RUTA_IMAGEN = "ruta_imagen"
        /** Lo que el Service le manda a MainActivity cuando hace falta consentimiento. */
        const val ACTION_PEDIR_PERMISO = "com.tatoh.dokushorenshu.captura.PEDIR_PERMISO"

        /** Lo lee la pantalla Scan para el rótulo del botón. */
        @Volatile var isRunning: Boolean = false
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INICIAR -> {
                if (burbuja == null) {
                    arrancarEnForeground(conProyeccion = false)
                    val nuevaBurbuja = BurbujaFlotante(this, windowManager!!, ::onTapBurbuja)
                    // isRunning sólo pasa a true si la ventana quedó efectivamente puesta:
                    // ponerlo siempre (aunque addView() falle) dejaba mintiendo el rótulo
                    // del botón en la pantalla Scan.
                    if (nuevaBurbuja.mostrar()) {
                        burbuja = nuevaBurbuja
                        isRunning = true
                    } else {
                        stopSelf()
                    }
                }
            }
            ACTION_ABRIR_SESION -> {
                if (isCapturing) {
                    android.util.Log.w("ScreenCapture", "Ya hay una captura en curso, ignorando")
                    return START_NOT_STICKY
                }
                // La promoción del tipo de foreground puede ser rechazada por el sistema
                // (ForegroundServiceStartNotAllowedException y parientes). Sin este runCatching
                // la excepción sale del onStartCommand y se lleva puesto el proceso, con la
                // burbuja adentro; atrapada, cae en el mismo camino que un token inválido.
                val promovido = runCatching { arrancarEnForeground(conProyeccion = true) }
                if (promovido.isFailure) {
                    android.util.Log.e("ScreenCapture", "El sistema rechazó el foreground de proyección", promovido.exceptionOrNull())
                    avisar("Screen capture failed. Please try again")
                    // Sin burbuja (camino "Capture now") nadie va a reintentar tocando
                    // nada: sin este detenerTodo() el Service queda en foreground
                    // promovido, con notificación, y sin forma de pararlo salvo el
                    // botón "Stop" de la propia notificación.
                    if (burbuja == null) detenerTodo()
                    return START_NOT_STICKY
                }
                val codigo = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val datos: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (!abrirSesion(codigo, datos)) {
                    if (burbuja == null) detenerTodo()
                    return START_NOT_STICKY
                }
                isCapturing = true
                showOverlay()
            }
            ACTION_DETENER -> detenerTodo()
        }
        // START_NOT_STICKY y no START_STICKY: en un reinicio el sistema reentrega un
        // intent null, que no cae en ninguna de las ramas — o sea el Service revivía
        // sin foreground y sin burbuja, un fantasma. Mejor no revivir.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** El tipo de foreground no es cosmético: getMediaProjection() EXIGE que ya esté
     *  corriendo un FGS de tipo mediaProjection. Se arranca sin él (la burbuja sola no
     *  proyecta nada) y se promueve antes de crear la sesión. */
    private fun arrancarEnForeground(conProyeccion: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val tipos = if (conProyeccion) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIFICATION_ID, createNotification(), tipos)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    /** Consume el token y deja la sesión viva para las capturas que vengan. El token sirve
     *  para UNA llamada a getMediaProjection(); el MediaProjection que sale de ahí sirve
     *  para muchas. Devuelve false si el token no servía. */
    private fun abrirSesion(codigo: Int, datos: Intent?): Boolean {
        if (codigo == 0 || datos == null) {
            android.util.Log.e("ScreenCapture", "ACTION_ABRIR_SESION sin código o sin datos de resultado")
            avisar("Screen capture failed. Please try again")
            return false
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // "Capture now" con la burbuja (y su sesión) ya viva llega hasta acá con
        // isCapturing en false entre capturas: sin este stop() se apilaría una segunda
        // proyección sobre la vieja, que quedaría huérfana (o la plataforma la para sola
        // y el indicador de "grabando pantalla" no tiene con qué sincronizarse). stop()
        // dispara el onStop() de la sesión vieja de forma asíncrona (posteado al main
        // looper) — para cuando corra, `sesion` ya puede apuntar a la nueva; de ahí el
        // chequeo de identidad de más abajo, sin el cual ese callback tardío pisaría la
        // sesión recién creada.
        sesion?.stop()
        return try {
            val proyeccion = manager.getMediaProjection(codigo, datos)
            if (proyeccion == null) {
                android.util.Log.e("ScreenCapture", "getMediaProjection() devolvió null")
                avisar("Screen capture failed. Please try again")
                sesion = null
                return false
            }
            proyeccion.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    // El usuario frenó la proyección desde el panel del sistema, o el
                    // sistema la cortó. La burbuja NO se apaga: el próximo tap pide
                    // consentimiento de nuevo, que es el camino de recuperación.
                    // Chequeo de identidad: este callback quedó registrado sobre ESTA
                    // proyección. Si para cuando corre (puede llegar tarde, posteado al
                    // main looper) `sesion` ya apunta a una más nueva, no hay que
                    // pisarla — si no, la P1 vieja anularía la P2 viva.
                    if (sesion === proyeccion) {
                        android.util.Log.d("ScreenCapture", "La sesión se cerró desde afuera")
                        sesion = null
                    } else {
                        android.util.Log.d("ScreenCapture", "onStop() de una sesión vieja: se ignora")
                    }
                }
            }, Handler(Looper.getMainLooper()))
            sesion = proyeccion
            true
        } catch (e: SecurityException) {
            android.util.Log.e("ScreenCapture", "Token inválido al abrir la sesión", e)
            sesion = null
            avisar("Screen capture failed. Please try again")
            false
        }
    }

    /** Se tocó la burbuja: si hay sesión viva, va directo al overlay; si no, hay que
     *  pedirle consentimiento de nuevo al usuario. */
    private fun onTapBurbuja() {
        when (decidirTap(haySesion = sesion != null)) {
            AccionTap.MostrarOverlay -> {
                if (isCapturing) {
                    android.util.Log.w("ScreenCapture", "Ya hay una captura en curso, ignorando")
                    return
                }
                isCapturing = true
                showOverlay()
            }
            AccionTap.PedirConsentimiento -> startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = ACTION_PEDIR_PERMISO
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
        }
    }

    /** Apaga sesión, burbuja y Service. Único punto de baja completa: lo dispara tanto
     *  el botón "Stop" de la notificación como ACTION_DETENER desde la pantalla Scan. */
    private fun detenerTodo() {
        cleanup()
        burbuja?.ocultar()
        burbuja = null
        isRunning = false
        isCapturing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Quick capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Floating button and screen capture status"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val detener = PendingIntent.getService(
            this, 0,
            Intent(this, CapturaService::class.java).apply { action = ACTION_DETENER },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quick capture active")
            .setContentText("Tap the floating button to capture")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", detener)
            .build()
    }

    private fun showOverlay() {
        android.util.Log.d("ScreenCapture", "=== showOverlay() INICIADO ===")
        
        // IMPORTANTE: Ocultar la burbuja flotante mientras se muestra el overlay de captura
        burbuja?.visible(false)
        
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
        
        val container = FrameLayout(this).apply {
            // La ventana va SIN FLAG_NOT_FOCUSABLE a propósito (workaround de MIUI, ver
            // arriba), así que se queda con el foco de teclas: si este contenedor no
            // maneja KEYCODE_BACK, el botón atrás queda MUERTO en todo el sistema
            // mientras el overlay esté arriba, y la única salida es el botón Cancel.
            // Para que lleguen los eventos de tecla hace falta foco en modo táctil:
            // de ahí el isFocusableInTouchMode + el requestFocus() de más abajo.
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    android.util.Log.d("ScreenCapture", "Back en el overlay: se trata como Cancel")
                    stopOverlay()
                    true
                } else {
                    false
                }
            }
        }

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
            setPadding(dp(16), dp(8), dp(16), dp(8))
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
            bottomMargin = margenInferiorBotones()
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
            setPadding(dp(16), dp(8), dp(16), dp(8))
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
            bottomMargin = margenInferiorBotones()
            marginEnd = dp(8)
        }
        container.addView(cancelButton, cancelParams)
        
        overlayView = container
        // addView puede lanzar BadTokenException: permiso de overlay revocado a mitad de
        // sesión, o MIUI negando un popup desde background — justo los teléfonos para los
        // que existen los workarounds de acá arriba. Sin atrapar, la excepción sale por
        // onStartCommand y mata el proceso DESPUÉS de ocultar la burbuja de más arriba, o
        // sea se la lleva puesta también. BurbujaFlotante.mostrar() ya envuelve su propio
        // addView por esta misma razón.
        try {
            windowManager?.addView(overlayView, layoutParams)
        } catch (e: Exception) {
            android.util.Log.e("ScreenCapture", "Error añadiendo el overlay a WindowManager", e)
            overlayView = null
            selectionView = null
            avisar("Could not show the capture overlay")
            terminarCaptura()
            return
        }
        // Después de addView: sólo una vista adjunta puede tomar el foco de teclas, que es
        // lo que hace que llegue el KEYCODE_BACK del listener de arriba.
        container.requestFocus()
        android.util.Log.d("ScreenCapture", "Overlay añadido a WindowManager")
        android.util.Log.d("ScreenCapture", "=== showOverlay() FINALIZADO ===")
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density).toInt()

    /** Margen inferior seguro para los botones del overlay. La ventana usa
     *  FLAG_LAYOUT_NO_LIMITS + LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, así que se
     *  dibuja POR DEBAJO de la barra de navegación / franja de gestos: con el margen
     *  crudo de 50 px que había antes, Cancel podía quedar tapado y sin forma de tocarlo
     *  — y con un overlay a pantalla completa, oscuro y con KEEP_SCREEN_ON encima, eso
     *  deja al usuario encerrado.
     *  El inset real sólo se puede leer sin vista adjunta desde API 30; en 26-29 se usa
     *  el piso fijo de 48dp, que es el alto estándar de la barra de 3 botones (puede
     *  sobrar algún dp, nunca faltar).
     *  La lectura del inset va atrapada: esto corre ANTES del try/catch del addView y
     *  DESPUÉS de haber ocultado el bubble, o sea que una excepción acá sería exactamente
     *  el crash-con-el-bubble-oculto que el try/catch del addView existe para evitar. Y
     *  currentWindowMetrics sobre un WindowManager de un Service sin UI es justo el tipo
     *  de cosa que una ROM OEM (MIUI, el objetivo declarado de este archivo) puede romper.
     *  Si falla se cae al mismo piso de 48dp que usa API 26-29, que es un valor sano: la
     *  captura sigue andando con los botones alcanzables en vez de abortarse. */
    private fun margenInferiorBotones(): Int {
        val insetNavegacion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                windowManager?.currentWindowMetrics?.windowInsets
                    ?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
            } catch (e: Throwable) {
                android.util.Log.e("ScreenCapture", "No se pudo leer el inset de navegación", e)
                0
            }
        } else {
            0
        }
        return maxOf(insetNavegacion, dp(48)) + dp(8)
    }

    /** Aviso visible al usuario desde el Service. Toast exige main thread y acá se llama
     *  tanto desde el hilo del OCR como desde handlers, así que siempre se postea.
     *  Los strings de UI van en inglés, como el resto de la app. */
    private fun avisar(mensaje: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun captureScreen() {
        android.util.Log.d("ScreenCapture", "=== captureScreen() INICIADO ===")
        
        // Primero ocultar el overlay y esperar un momento
        hideOverlayTemporarily()
        android.util.Log.d("ScreenCapture", "Overlay ocultado temporalmente")
        
        // Delay para que el overlay se oculte completamente
        Handler(Looper.getMainLooper()).postDelayed({
            // Este trabajo ya estaba encolado cuando el usuario pudo haber cancelado: el
            // overlay queda INVISIBLE pero adjunto y con el foco, así que Back durante esta
            // ventana de ~300 ms dispara stopOverlay() y el Service se da de baja.
            if (!isCapturing) {
                android.util.Log.d("ScreenCapture", "Captura cancelada antes del delay: no se arma la captura")
                return@postDelayed
            }

            val metrics = DisplayMetrics()
            windowManager?.defaultDisplay?.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val proyeccion = sesion
            if (proyeccion == null) {
                // La sesión murió entre el tap y el disparo (el usuario la frenó desde el panel).
                android.util.Log.w("ScreenCapture", "Sin sesión al capturar: se pide consentimiento")
                avisar("Screen capture failed. Please try again")
                stopOverlay()
                return@postDelayed
            }
            android.util.Log.d("ScreenCapture", "Creando ImageReader: ${width}x${height}, density=$density")
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = proyeccion.createVirtualDisplay(
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
                // Misma historia: si cancelaron entre medio, esto ya estaba encolado.
                // Acá hay que soltar lo que el runnable anterior alcanzó a crear —
                // VirtualDisplay e ImageReader— porque se armaron DESPUÉS de que Cancel
                // ya haya corrido terminarCaptura(). OJO: NO se llama a cleanup() acá,
                // que además pararía la sesión — el punto entero de la tarea 5 es que un
                // Cancel de una captura puntual no se lleve puesta la sesión completa.
                if (!isCapturing) {
                    android.util.Log.d("ScreenCapture", "Captura cancelada antes de procesar: se libera y se sale")
                    virtualDisplay?.release()
                    imageReader?.close()
                    virtualDisplay = null
                    imageReader = null
                    return@postDelayed
                }

                android.util.Log.d("ScreenCapture", "Llamando a processCapture()...")
                processCapture()
            }, 200)
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
            // Camino real: el postDelayed fijo de 200 ms de captureScreen() se queda corto
            // en un teléfono frío o lento y el VirtualDisplay todavía no produjo un frame.
            // La timing NO se cambia acá (es código portado y probado en dispositivo, y no
            // hay hardware para validar un cambio), pero la falla sí se hace ruidosa: antes
            // el overlay se desvanecía a los 500 ms sin decir nada y parecía un cuelgue.
            android.util.Log.e("ScreenCapture", "No se pudo obtener imagen del ImageReader")
            avisar("Screen capture failed. Please try again")
            cerrarConDemora()
            return
        }

        // Preparar el bitmap puede fallar: un ARGB_8888 de pantalla completa son
        // decenas de MB y el OOM es realista. Si la excepción se escapa, corre por el
        // main thread, crashea la app, deja la Image sin cerrar y sobre todo deja la
        // ventana del overlay pegada a pantalla completa, o sea el teléfono inusable.
        // El finally cierra la Image pase lo que pase (el original tenía el mismo
        // try/finally alrededor de todo este tramo).
        // Throwable y no Exception a propósito: la falla realista acá es un
        // OutOfMemoryError al pedir el ARGB_8888 de pantalla completa, y OOM es Error,
        // no Exception — con catch (e: Exception) se escapaba justo el caso para el que
        // existe este try. No se relanza: perder una captura es mucho mejor que dejar
        // el overlay clavado a pantalla completa encima de todo.
        val bitmaps = try {
            prepararBitmaps(image)
        } catch (e: Throwable) {
            android.util.Log.e("ScreenCapture", "Error preparando el bitmap de la captura", e)
            null
        } finally {
            image.close()
        }

        if (bitmaps == null) {
            avisar("Screen capture failed. Please try again")
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
        // El bubble ya volvió y parece vivo, pero los taps se descartan mientras haya una
        // captura en curso (isCapturing). Sin este aviso, el OCR se ve como "no pasó nada".
        avisar("Recognizing text...")

        Thread {
            try {
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

                // Antes de reciclar: el bitmap recortado es lo que el usuario eligió, y es
                // lo que queremos conservar como imagen del recorte. Guardar acá y no
                // después de los recycle() no es opcional: compress() sobre un bitmap
                // reciclado tira IllegalStateException.
                val rutaImagen = if (texto.isNotBlank()) guardarImagen(recortado) else null

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

                // Un texto vacío NO se entrega: entregarTexto() trae la app al frente, y del
                // otro lado crear() rechaza el texto en blanco — el usuario vería la app
                // saltar y un error, en vez de nada. Avisar y desarmar es lo correcto.
                if (texto.isBlank()) {
                    android.util.Log.w("ScreenCapture", "OCR sin texto: no se abre la app")
                    avisar("No text found in the selected area")
                } else {
                    entregarTexto(texto, rutaImagen)
                }
            } catch (e: Throwable) {
                // Sin este catch, cualquier excepción acá adentro mata el proceso: corre en un
                // hilo propio, nadie la atrapa, y terminarCaptura() nunca se ejecuta (Service
                // en foreground colgado + captura sin cerrar). El caso concreto es
                // startActivity() lanzando si revocaron SYSTEM_ALERT_WINDOW mientras tanto.
                android.util.Log.e("ScreenCapture", "Fallo no controlado en el hilo de OCR", e)
                avisar("Capture failed")
            } finally {
                Handler(Looper.getMainLooper()).post { terminarCaptura() }
            }
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
        // Se loguean los tres: con "Recorte escalado: null" solo no se distingue
        // "el usuario no arrastró" (seleccion=null, arrastre < 10 px) de "la
        // selección era inusable" (escalarRecorte devolvió null).
        android.util.Log.d(
            "ScreenCapture",
            "Selección: $seleccion, overlay: ${anchoOverlay}x$altoOverlay, recorte escalado: $recorte",
        )

        val recortado = if (recorte != null) {
            Bitmap.createBitmap(bitmap, recorte.left, recorte.top, recorte.ancho, recorte.alto)
        } else {
            bitmap
        }
        return bitmap to recortado
    }

    /** Guarda la captura como JPEG 90 en el slot fijo de RecortesRepo.
     *
     *  JPEG y no PNG: el PNG de una captura de pantalla pesa 2-5 MB y se guarda una
     *  por nota. En JPEG 90 una pantalla completa queda en ~700 KB, con calidad de
     *  sobra para releer el original cuando el OCR salió ilegible.
     *
     *  Throwable y no Exception, por lo mismo que prepararBitmaps: comprimir un bitmap
     *  de pantalla completa puede tirar OutOfMemoryError, que es Error y no Exception.
     *
     *  Devuelve null si falla: el recorte se crea igual, sin imagen. */
    private fun guardarImagen(bitmap: Bitmap): File? = try {
        val destino = RecortesRepo.imagenPendiente(this)
        // compress() avisa que falló DEVOLVIENDO false, no lanzando: el catch de abajo no
        // cubre este caso. Sin mirar el retorno, outputStream() ya creó el archivo y
        // devolveríamos un File apuntando a un JPEG vacío o truncado — peor que no tener
        // imagen, porque el recorte muestra una imagen rota en vez de ninguna.
        val ok = destino.outputStream().use { salida ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, salida)
        }
        if (ok) {
            destino
        } else {
            android.util.Log.e("ScreenCapture", "compress() devolvió false: JPEG inservible")
            null
        }
    } catch (e: Throwable) {
        android.util.Log.e("ScreenCapture", "no se pudo guardar la imagen de la captura", e)
        null
    }

    /** Abre la app con el texto reconocido. Arrancar una Activity desde background
     *  está bloqueado desde Android 10, pero la app queda exenta por tener
     *  SYSTEM_ALERT_WINDOW concedido — que es el permiso del propio overlay. */
    private fun entregarTexto(texto: String, rutaImagen: File?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_TEXTO_OCR
            putExtra(EXTRA_TEXTO_OCR, texto)
            if (rutaImagen != null) putExtra(EXTRA_RUTA_IMAGEN, rutaImagen.absolutePath)
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
        // Mostrar la burbuja de nuevo
        burbuja?.visible(true)

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

    /** Cierra la captura, no la sesión: suelta el VirtualDisplay y el ImageReader, que se
     *  crean de nuevo en la próxima, y vuelve a mostrar la burbuja. La sesión sigue viva —
     *  ese es el punto del plan. Sin burbuja (camino de `Capture now`) no hay nada que
     *  sostener y el Service se apaga, como siempre. */
    private fun terminarCaptura() {
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
        isCapturing = false
        burbuja?.visible(true)
        if (apagarTrasCaptura(hayBurbuja = burbuja != null)) detenerTodo()
    }

    private fun stopOverlay() {
        android.util.Log.d("ScreenCapture", "=== stopOverlay() INICIADO ===")

        liberarVentanaOverlay()
        terminarCaptura()

        android.util.Log.d("ScreenCapture", "=== stopOverlay() FINALIZADO ===")
    }

    /** Apagado de verdad: acá sí se cierra la sesión. Corre desde detenerTodo() (que
     *  terminarCaptura() puede disparar), y otra vez desde onDestroy() por si el sistema
     *  mata el Service directo. */
    private fun cleanup() {
        virtualDisplay?.release()
        imageReader?.close()
        sesion?.stop()

        virtualDisplay = null
        imageReader = null
        sesion = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        // Si el sistema mata el Service con la burbuja arriba (o si detenerTodo() no
        // corrió, p.ej. onDestroy directo), no puede quedar una ventana flotante sin
        // dueño ni el rótulo del botón de Scan mintiendo "Stop floating button". El
        // FloatingBubbleService viejo garantizaba esto con onDestroy → stopBubble(); la
        // fusión de la tarea 4 lo había perdido.
        burbuja?.ocultar()
        burbuja = null
        isRunning = false
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
