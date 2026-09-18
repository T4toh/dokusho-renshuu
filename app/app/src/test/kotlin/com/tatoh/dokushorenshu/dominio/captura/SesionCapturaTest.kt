package com.tatoh.dokushorenshu.dominio.captura

import org.junit.Assert.assertEquals
import org.junit.Test

class SesionCapturaTest {

    @Test
    fun `con sesion abierta el tap va derecho al overlay`() {
        assertEquals(AccionTap.MostrarOverlay, decidirTap(haySesion = true))
    }

    @Test
    fun `sin sesion el tap tiene que pedir consentimiento`() {
        assertEquals(AccionTap.PedirConsentimiento, decidirTap(haySesion = false))
    }

    @Test
    fun `sin burbuja una captura es una sesion y el Service se apaga`() {
        // El camino de "Capture now" desde la pantalla Scan: nadie va a tocar una
        // burbuja después, así que no hay sesión que sostener.
        assertEquals(true, apagarTrasCaptura(hayBurbuja = false))
    }

    @Test
    fun `con burbuja el Service sigue vivo despues de capturar`() {
        // Es el corazón del plan: la sesión sobrevive a la captura.
        assertEquals(false, apagarTrasCaptura(hayBurbuja = true))
    }
}
