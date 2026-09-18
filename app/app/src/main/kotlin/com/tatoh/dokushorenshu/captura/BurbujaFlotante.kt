package com.tatoh.dokushorenshu.captura

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.tatoh.dokushorenshu.R
import kotlin.math.abs

/**
 * Ventana flotante (bubble) sobre otras apps, con drag, snap al borde y detección de tap.
 * Extraída de FloatingBubbleService (que la hospeda) para que en la tarea 4 pueda vivir
 * dentro de otro Service sin duplicar este código.
 */
class BurbujaFlotante(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onTap: () -> Unit
) {

    private var bubbleView: View? = null

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    private var isDragging = false
    private val DRAG_THRESHOLD = 10 // pixels
    private val BUBBLE_SIZE = 56 // dp - tamaño estándar de FAB en Material Design

    /** @return false si falló al agregar la ventana al WindowManager (el Service decide
     *  qué hacer, por ejemplo detenerse); true si la burbuja quedó puesta. */
    fun mostrar(): Boolean {
        val bubbleSize = (BUBBLE_SIZE * context.resources.displayMetrics.density).toInt()

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
        bubbleView = ImageView(context).apply {
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
                                    windowManager.updateViewLayout(bubbleView, layoutParams)
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
                                android.util.Log.d("FloatingBubble", "Click detectado! Ejecutando onTap()")
                                // Pequeño delay para evitar conflictos con el sistema
                                v.postDelayed({
                                    onTap()
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
            windowManager.addView(bubbleView, layoutParams)
            android.util.Log.d("FloatingBubble", "Bubble añadido correctamente a WindowManager")
            android.util.Log.d("FloatingBubble", "Layout params: width=$bubbleSize, height=$bubbleSize, type=${layoutParams.type}, flags=${layoutParams.flags}")
        } catch (e: Exception) {
            android.util.Log.e("FloatingBubble", "ERROR al añadir bubble a WindowManager", e)
            e.printStackTrace()
            return false
        }
        return true
    }

    private fun snapToEdge(params: WindowManager.LayoutParams) {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val bubbleSize = (BUBBLE_SIZE * context.resources.displayMetrics.density).toInt()

        // Mover al borde más cercano (izquierda o derecha)
        params.x = if (params.x < screenWidth / 2) {
            20 // Margen izquierdo
        } else {
            screenWidth - bubbleSize - 20 // Margen derecho
        }

        windowManager.updateViewLayout(bubbleView, params)
    }

    fun ocultar() {
        try {
            bubbleView?.let {
                windowManager.removeView(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        bubbleView = null
    }

    fun visible(visible: Boolean) {
        bubbleView?.visibility = if (visible) View.VISIBLE else View.GONE
        android.util.Log.d("FloatingBubble", "Bubble visible=$visible")
    }
}
