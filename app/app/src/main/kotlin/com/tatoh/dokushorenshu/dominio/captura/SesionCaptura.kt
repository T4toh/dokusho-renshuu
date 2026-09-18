package com.tatoh.dokushorenshu.dominio.captura

/** Qué hacer cuando el usuario toca la burbuja. */
sealed interface AccionTap {
    /** Hay sesión de MediaProjection viva: se dibuja el overlay de selección y listo. */
    data object MostrarOverlay : AccionTap

    /** No hay sesión: hay que abrir la app para que el sistema pida el consentimiento.
     *  Es el camino de siempre, que con este plan pasa a ser el de recuperación. */
    data object PedirConsentimiento : AccionTap
}

/** La sesión es la única fuente de verdad: si está viva no se molesta al usuario. */
fun decidirTap(haySesion: Boolean): AccionTap =
    if (haySesion) AccionTap.MostrarOverlay else AccionTap.PedirConsentimiento

/** Si no hay burbuja, la captura vino de `Capture now` en la pantalla Scan: nadie va a
 *  tocar una burbuja después, así que no hay sesión que sostener y el Service se apaga
 *  al terminar — el comportamiento de siempre. Con burbuja, el Service sigue vivo con su
 *  sesión, que es el punto de todo el plan. */
fun apagarTrasCaptura(hayBurbuja: Boolean): Boolean = !hayBurbuja
