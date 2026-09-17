package com.tatoh.dokushorenshu.datos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecorteTest {

    private val ejemplo = Recorte(
        id = "1758035000000",
        texto = "昔々、ある所におじいさんがいました。",
        parrafos = listOf(
            Parrafo(
                listOf(
                    Oracion(
                        texto = "昔々、ある所におじいさんがいました。",
                        furigana = listOf(Furigana(0, 2, "むかしむかし"), Furigana(6, 7, "ところ")),
                    )
                )
            )
        ),
        timestamp = 1758035000000L,
        tieneImagen = true,
    )

    @Test
    fun `round-trip conserva todos los campos`() {
        val vuelto = ParserRecorte.parsear(SerializadorRecorte.serializar(ejemplo))
        assertEquals(ejemplo, vuelto)
    }

    @Test
    fun `round-trip conserva tieneImagen en false`() {
        val sinImagen = ejemplo.copy(tieneImagen = false)
        assertEquals(sinImagen, ParserRecorte.parsear(SerializadorRecorte.serializar(sinImagen)))
    }

    @Test
    fun `round-trip conserva varios parrafos y oraciones`() {
        val multiple = ejemplo.copy(
            texto = "一行目。\n二行目です。",
            parrafos = listOf(
                Parrafo(listOf(Oracion("一行目。", emptyList()))),
                Parrafo(listOf(Oracion("二行目です。", listOf(Furigana(0, 1, "に"))))),
            ),
        )
        assertEquals(multiple, ParserRecorte.parsear(SerializadorRecorte.serializar(multiple)))
    }

    @Test
    fun `json invalido lanza IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { ParserRecorte.parsear("{no es json") }
    }

    @Test
    fun `json sin campo obligatorio lanza IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ParserRecorte.parsear("""{"id":"1","texto":"あ"}""")
        }
    }

    @Test
    fun `furigana no es terna lanza IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ParserRecorte.parsear("""{"id":"1","texto":"test","timestamp":1000,"tieneImagen":false,"parrafos":[{"oraciones":[{"texto":"test","furigana":[[0,2]]}]}]}""")
        }
    }

    @Test
    fun `furigana fuera de rango lanza IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ParserRecorte.parsear("""{"id":"1","texto":"test","timestamp":1000,"tieneImagen":false,"parrafos":[{"oraciones":[{"texto":"test","furigana":[[0,10,"abc"]]}]}]}""")
        }
    }

    @Test
    fun `timestamp con tipo incorrecto lanza IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ParserRecorte.parsear("""{"id":"1","texto":"test","timestamp":"abc","tieneImagen":false,"parrafos":[]}""")
        }
    }
}
