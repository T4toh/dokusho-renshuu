package com.tatoh.dokushorenshu.dominio.anki

import org.junit.Assert.*
import org.junit.Test

class ModeloNotasTest {

    // --- guidDe: determinístico estilo genanki (SHA-256 sobre una CLAVE estable,
    // no sobre los campos completos — así el guid no cambia si cambian las
    // oraciones en un re-export). ---

    @Test
    fun `guid estable entre llamadas para la misma clave`() {
        val a = ModeloNotas.guidDe("words:物語")
        val b = ModeloNotas.guidDe("words:物語")
        assertEquals(a, b)
    }

    @Test
    fun `guid distinto para claves distintas`() {
        assertNotEquals(ModeloNotas.guidDe("words:物語"), ModeloNotas.guidDe("words:犬"))
        assertNotEquals(ModeloNotas.guidDe("words:犬"), ModeloNotas.guidDe("kanji:犬"))
    }

    @Test
    fun `guid no esta vacio y usa solo el alfabeto base91 de Anki`() {
        val alfabeto = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
            "!#\$%&()*+,-./:;<=>?@[]^_`{|}~").toSet()
        val guid = ModeloNotas.guidDe("words:物語")
        assertTrue(guid.isNotEmpty())
        assertTrue(guid.all { it in alfabeto })
    }

    @Test
    fun `guidDe coincide con el valor de referencia de genanki`() {
        // Valor calculado con genanki real (guid_for) — tripwire contra regresiones
        // silenciosas del algoritmo base91/SHA-256.
        assertEquals("PXyc82SG{C", ModeloNotas.guidDe("words:物語"))
    }

    // --- NotaWords/NotaKanji: orden de campos y cap de 5 oraciones ---

    @Test
    fun `NotaWords campos en el orden del modelo, oraciones faltantes quedan vacias`() {
        val nota = NotaWords(
            palabra = "物語", lectura = "ものがたり", significados = "tale",
            oraciones = listOf("oración 1", "oración 2"),
        )
        assertEquals(
            listOf("物語", "ものがたり", "tale", "", "oración 1", "oración 2", "", "", ""),
            nota.campos(),
        )
        assertEquals("words:物語", nota.claveGuid)
    }

    @Test
    fun `NotaKanji campos en el orden del modelo`() {
        val nota = NotaKanji(
            kanji = "犬", onYomi = "ケン", kunYomi = "いぬ", significados = "dog",
            dificultad = "easy", oraciones = listOf("oración kanji"),
        )
        assertEquals(
            listOf("犬", "ケン", "いぬ", "dog", "easy", "oración kanji", "", "", "", ""),
            nota.campos(),
        )
        assertEquals("kanji:犬", nota.claveGuid)
    }

    @Test
    fun `mas de 5 oraciones lanza excepcion en ambas notas`() {
        val seis = List(6) { "o$it" }
        assertThrows(IllegalArgumentException::class.java) {
            NotaWords(palabra = "x", lectura = "x", significados = "x", oraciones = seis)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotaKanji(kanji = "x", onYomi = "x", kunYomi = "x", significados = "x", dificultad = "x", oraciones = seis)
        }
    }

    // --- Templates: contienen los marcadores de campo y el mecanismo de rotación ---

    @Test
    fun `template Words referencia sus campos y tiene el div de rotacion`() {
        assertTrue(ModeloNotas.QFMT_WORDS.contains("{{Palabra}}"))
        assertTrue(ModeloNotas.AFMT_WORDS.contains("{{Lectura}}"))
        assertTrue(ModeloNotas.AFMT_WORDS.contains("{{Significados}}"))
        assertTrue(ModeloNotas.AFMT_WORDS.contains("{{Tag}}"))
        for (i in 1..5) assertTrue(ModeloNotas.AFMT_WORDS.contains("{{Oracion$i}}"))
        assertTrue(ModeloNotas.AFMT_WORDS.contains("id=\"oracion\""))
        assertTrue(ModeloNotas.AFMT_WORDS.contains("<script>"))
    }

    @Test
    fun `template Kanji referencia sus campos y tiene el div de rotacion`() {
        assertTrue(ModeloNotas.QFMT_KANJI.contains("{{Kanji}}"))
        assertTrue(ModeloNotas.AFMT_KANJI.contains("{{OnYomi}}"))
        assertTrue(ModeloNotas.AFMT_KANJI.contains("{{KunYomi}}"))
        assertTrue(ModeloNotas.AFMT_KANJI.contains("{{Significados}}"))
        assertTrue(ModeloNotas.AFMT_KANJI.contains("{{Dificultad}}"))
        for (i in 1..5) assertTrue(ModeloNotas.AFMT_KANJI.contains("{{Oracion$i}}"))
        assertTrue(ModeloNotas.AFMT_KANJI.contains("id=\"oracion\""))
        assertTrue(ModeloNotas.AFMT_KANJI.contains("<script>"))
    }

    @Test
    fun `afmt kanji muestra kun antes que on, en lineas etiquetadas y condicionales`() {
        val afmt = ModeloNotas.AFMT_KANJI
        assertTrue(afmt.indexOf("{{KunYomi}}") < afmt.indexOf("{{OnYomi}}"))
        assertTrue(afmt.contains("{{#KunYomi}}"))
        assertTrue(afmt.contains("{{#OnYomi}}"))
        assertTrue(afmt.contains("""<span class="etiqueta-lectura">kun</span>"""))
        assertTrue(afmt.contains("""<span class="etiqueta-lectura">on</span>"""))
    }

    @Test
    fun `css define objetivo y etiqueta-lectura con overrides de modo claro`() {
        assertTrue(ModeloNotas.CSS.contains(".objetivo"))
        assertTrue(ModeloNotas.CSS.contains(".etiqueta-lectura"))
        assertTrue(ModeloNotas.CSS.contains(".card:not(.night_mode) #oracion .objetivo"))
    }

    // --- feedback de uso 2026-08-18: el inglés no aparece solo; se revela a pedido ---

    @Test
    fun `ambos templates traen el checkbox del ingles antes del contenido y el boton despues`() {
        for (afmt in listOf(ModeloNotas.AFMT_WORDS, ModeloNotas.AFMT_KANJI)) {
            val checkbox = afmt.indexOf("id=\"en-check\"")
            val significados = afmt.indexOf("{{Significados}}")
            val oracion = afmt.indexOf("id=\"oracion\"")
            assertTrue("falta el checkbox", checkbox >= 0)
            assertTrue("falta el boton EN", afmt.contains("for=\"en-check\""))
            // el selector `:checked ~` solo alcanza hermanos POSTERIORES
            assertTrue("el checkbox va antes de los significados", checkbox < significados)
            assertTrue("el checkbox va antes de la oracion", checkbox < oracion)
        }
    }

    @Test
    fun `el toggle del ingles no depende de javascript`() {
        for (afmt in listOf(ModeloNotas.AFMT_WORDS, ModeloNotas.AFMT_KANJI)) {
            // el unico script que queda es el de rotacion de oraciones
            assertEquals(1, afmt.split("<script>").size - 1)
            assertFalse(afmt.contains("classList"))
        }
    }

    @Test
    fun `el css oculta significados y traduccion hasta tildar el checkbox, sin mover el layout`() {
        assertTrue(ModeloNotas.CSS.contains(".significados, .traduccion"))
        assertTrue(ModeloNotas.CSS.contains("visibility: hidden"))
        assertTrue(ModeloNotas.CSS.contains(".en-check:checked ~ .significados"))
        assertTrue(ModeloNotas.CSS.contains(".en-check:checked ~ #oracion .traduccion"))
        assertTrue(ModeloNotas.CSS.contains("visibility: visible"))
        assertTrue(ModeloNotas.CSS.contains(".en-toggle"))
    }

    @Test
    fun `IDs de modelo y mazo son distintos entre si (nunca colisionan)`() {
        val ids = setOf(
            ModeloNotas.MODEL_ID_WORDS, ModeloNotas.MODEL_ID_KANJI,
            ModeloNotas.DECK_ID_WORDS, ModeloNotas.DECK_ID_KANJI,
        )
        assertEquals(4, ids.size)
    }

    // --- Plan 4a.1: GUID por historia+kanji y deck IDs de subdecks ---

    @Test
    fun `claveGuid de NotaKanji usa claveGuidPropia si esta presente`() {
        val base = NotaKanji("洗", "セン", "あら.う", "wash", "hard")
        assertEquals("kanji:洗", base.claveGuid)
        val deHistoria = base.copy(claveGuidPropia = "story:momotaro:洗")
        assertEquals("story:momotaro:洗", deHistoria.claveGuid)
        // GUIDs resultantes disjuntos: misma nota, mazos distintos, notas Anki distintas
        assertNotEquals(ModeloNotas.guidDe(base.claveGuid), ModeloNotas.guidDe(deHistoria.claveGuid))
    }

    @Test
    fun `deckIdDeHistoria es estable, de 13 digitos y disjunto de los IDs fijos`() {
        val id = ModeloNotas.deckIdDeHistoria("momotaro")
        assertEquals(id, ModeloNotas.deckIdDeHistoria("momotaro"))  // determinístico
        assertTrue("13 dígitos: $id", id in 1_000_000_000_000L..9_999_999_999_999L)
        assertNotEquals(ModeloNotas.DECK_ID_WORDS, id)
        assertNotEquals(ModeloNotas.DECK_ID_KANJI, id)
        // historias distintas → decks distintos
        assertNotEquals(id, ModeloNotas.deckIdDeHistoria("urashima_taro"))
    }

    @Test
    fun `nombreDeckHistoria arma el subdeck con la sintaxis de dos puntos de Anki`() {
        assertEquals("Dokusho — Stories::桃太郎", ModeloNotas.nombreDeckHistoria("桃太郎"))
    }
}
