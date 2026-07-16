package com.tatoh.dokushorenshu.dominio.anki

import com.tatoh.dokushorenshu.datos.Furigana
import com.tatoh.dokushorenshu.datos.Oracion
import org.junit.Assert.*
import org.junit.Test

class OracionARubyHtmlTest {

    @Test
    fun `span de furigana al inicio de la oracion (fixture real momotaro)`() {
        // texto y furigana copiados literales de app/src/test/resources/momotaro.json
        val oracion = Oracion(
            "桃太郎はどこか外国へ出かけて、腕いっぱい、力だめしをしてみたくなりました。",
            listOf(Furigana(0, 3, "ももたろう")),
        )
        assertEquals(
            "<ruby>桃太郎<rt>ももたろう</rt></ruby>はどこか外国へ出かけて、腕いっぱい、力だめしをしてみたくなりました。",
            oracionARubyHtml(oracion),
        )
    }

    @Test
    fun `span en medio y span cerca del final combinados (fixture real momotaro)`() {
        // "まいにち、おじいさんは山へしば刈りに、おばあさんは川へ洗濯に行きました。"
        // furigana real: 刈→か [15,16], 洗濯→せんたく [27,29]
        val oracion = Oracion(
            "まいにち、おじいさんは山へしば刈りに、おばあさんは川へ洗濯に行きました。",
            listOf(Furigana(15, 16, "か"), Furigana(27, 29, "せんたく")),
        )
        assertEquals(
            "まいにち、おじいさんは山へしば<ruby>刈<rt>か</rt></ruby>りに、おばあさんは川へ" +
                "<ruby>洗濯<rt>せんたく</rt></ruby>に行きました。",
            oracionARubyHtml(oracion),
        )
    }

    @Test
    fun `oracion sin furigana devuelve el texto plano (fixture real momotaro)`() {
        val oracion = Oracion("むかし、むかし、あるところに、おじいさんとおばあさんがありました。", emptyList())
        assertEquals(oracion.texto, oracionARubyHtml(oracion))
    }

    @Test
    fun `escapa caracteres HTML en el texto`() {
        val oracion = Oracion("5<10 & mas texto", emptyList())
        assertEquals("5&lt;10 &amp; mas texto", oracionARubyHtml(oracion))
    }

    @Test
    fun `span que llega exactamente al final de la oracion`() {
        val oracion = Oracion("ここに犬", listOf(Furigana(3, 4, "いぬ")))
        assertEquals("ここに<ruby>犬<rt>いぬ</rt></ruby>", oracionARubyHtml(oracion))
    }

    @Test
    fun `spans solapados se resuelven ignorando el segundo (defensivo)`() {
        // bug de datos conocido (ledger Plan 3.6: momotaro.json trajo furigana
        // solapada en algún momento) — nunca debe lanzar, ignora el span que
        // arranca antes de que termine el anterior.
        val oracion = Oracion("かえる", listOf(Furigana(0, 2, "かえ"), Furigana(1, 3, "える")))
        assertEquals("<ruby>かえ<rt>かえ</rt></ruby>る", oracionARubyHtml(oracion))
    }

    @Test
    fun `objetivo en texto plano queda envuelto en b objetivo`() {
        val oracion = Oracion("川へ行った。", emptyList())
        assertEquals(
            """<b class="objetivo">川</b>へ行った。""",
            oracionARubyHtml(oracion, objetivo = "川"),
        )
    }

    @Test
    fun `objetivo dentro de un span de ruby se resalta en la base sin tocar rt`() {
        val oracion = Oracion("桃太郎", listOf(Furigana(0, 3, "ももたろう")))
        assertEquals(
            """<ruby><b class="objetivo">桃</b>太郎<rt>ももたろう</rt></ruby>""",
            oracionARubyHtml(oracion, objetivo = "桃"),
        )
    }

    @Test
    fun `objetivo multichar que cruza el limite de un span se resalta por fragmentos`() {
        // ruby solo sobre 大 [0,1): el objetivo 大人 cruza el límite del span
        val oracion = Oracion("大人だ。", listOf(Furigana(0, 1, "おと")))
        assertEquals(
            """<ruby><b class="objetivo">大</b><rt>おと</rt></ruby><b class="objetivo">人</b>だ。""",
            oracionARubyHtml(oracion, objetivo = "大人"),
        )
    }

    @Test
    fun `ocurrencias multiples se resaltan todas`() {
        val oracion = Oracion("山と山。", emptyList())
        assertEquals(
            """<b class="objetivo">山</b>と<b class="objetivo">山</b>。""",
            oracionARubyHtml(oracion, objetivo = "山"),
        )
    }

    @Test
    fun `objetivo null u ausente no cambia la salida existente`() {
        val oracion = Oracion("桃太郎", listOf(Furigana(0, 3, "ももたろう")))
        assertEquals(oracionARubyHtml(oracion), oracionARubyHtml(oracion, objetivo = null))
    }

    @Test
    fun `objetivo con caracteres html se escapa dentro del resalte`() {
        val oracion = Oracion("a<b", emptyList())
        assertEquals(
            """a<b class="objetivo">&lt;</b>b""",
            oracionARubyHtml(oracion, objetivo = "<"),
        )
    }
}
