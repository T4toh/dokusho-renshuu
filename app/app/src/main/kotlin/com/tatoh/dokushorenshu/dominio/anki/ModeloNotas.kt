package com.tatoh.dokushorenshu.dominio.anki

import java.security.MessageDigest

/** Nota del mazo "Dokusho — Words". Campos en el orden exacto de
 *  [ModeloNotas.CAMPOS_WORDS] (9): Palabra, Lectura, Significados, Tag (vacío para
 *  este mazo — el tag va en el mazo Kanji), Oracion1..5 (cap 5, historias primero,
 *  Tatoeba después — armado en `ArmadorMazos`, fuera de esta tarea). Palabra sin
 *  definición en el db llega igual acá con `significados` vacío (el spec nunca aborta
 *  el export por eso). */
data class NotaWords(
    val palabra: String,
    val lectura: String,
    val significados: String,
    val tag: String = "",
    val oraciones: List<String> = emptyList(),
) {
    init {
        require(oraciones.size <= 5) { "NotaWords: máximo 5 oraciones, llegaron ${oraciones.size}" }
    }

    /** Campos en el orden exacto del modelo Anki: Palabra, Lectura, Significados, Tag,
     *  Oracion1..5 — las oraciones faltantes quedan como campo vacío (nunca se
     *  reordena ni se comprime la lista, así el JS de rotación siempre sabe qué
     *  campo mira). */
    fun campos(): List<String> =
        listOf(palabra, lectura, significados, tag) + List(5) { i -> oraciones.getOrElse(i) { "" } }

    /** Clave estable para el GUID: solo el término. A propósito NO incluye
     *  significados/oraciones — si el diccionario o las oraciones de ejemplo cambian
     *  en un re-export, el guid no debe cambiar (spec: "re-export actualiza, no
     *  duplica"). */
    val claveGuid: String get() = "words:$palabra"
}

/** Nota del mazo "Dokusho — Kanji" (Kanji, OnYomi, KunYomi, Significados, Dificultad,
 *  Oracion1..5 — 10 campos). Solo entran acá kanjis TAGGEADOS (easy/medium/hard);
 *  tocados-sin-tag se excluyen antes de llegar (decisión de `ArmadorMazos`, fuera de
 *  esta tarea). */
data class NotaKanji(
    val kanji: String,
    val onYomi: String,
    val kunYomi: String,
    val significados: String,
    val dificultad: String,
    val oraciones: List<String> = emptyList(),
    // GUID alternativo para mazos por historia (Plan 4a.1): "story:<id>:<kanji>".
    // Si compartiera el guid "kanji:<kanji>" con el mazo Dokusho — Kanji, el import
    // de Anki (match global por guid) pisaría esa nota en vez de crear la carta en
    // el subdeck de la historia.
    val claveGuidPropia: String? = null,
) {
    init {
        require(oraciones.size <= 5) { "NotaKanji: máximo 5 oraciones, llegaron ${oraciones.size}" }
    }

    fun campos(): List<String> =
        listOf(kanji, onYomi, kunYomi, significados, dificultad) +
            List(5) { i -> oraciones.getOrElse(i) { "" } }

    /** Clave estable para el GUID: solo el kanji (mismo criterio que [NotaWords.claveGuid]). */
    val claveGuid: String get() = claveGuidPropia ?: "kanji:$kanji"
}

/** Constantes de modelo/mazo, GUID determinístico y templates HTML+CSS+JS del Plan 4a
 *  (mazos Anki). Schema/formato validados contra el código fuente de genanki
 *  (kerrickstaley/genanki, referencia Python) — ver
 *  `docs/superpowers/specs/2026-07-09-plan-4a-mazos-anki-design.md` para el detalle de
 *  la investigación. Los IDs son CONSTANTES FIJAS: si cambiaran entre ejecuciones,
 *  cada export crearía un modelo/mazo nuevo en vez de actualizar el existente. */
object ModeloNotas {
    // Estilo Anki: enteros de 13 dígitos (parecen timestamps epoch-ms), pero FIJOS
    // en el código — nunca se recalculan en tiempo de ejecución.
    const val MODEL_ID_WORDS: Long = 1720000000001L
    const val MODEL_ID_KANJI: Long = 1720000000002L
    const val DECK_ID_WORDS: Long = 1720000000101L
    const val DECK_ID_KANJI: Long = 1720000000102L

    const val NOMBRE_DECK_WORDS: String = "Dokusho — Words"
    const val NOMBRE_DECK_KANJI: String = "Dokusho — Kanji"
    const val NOMBRE_DECK_STORIES: String = "Dokusho — Stories"
    const val NOMBRE_MODELO_WORDS: String = "Dokusho Words"
    const val NOMBRE_MODELO_KANJI: String = "Dokusho Kanji"

    val CAMPOS_WORDS: List<String> =
        listOf("Palabra", "Lectura", "Significados", "Tag", "Oracion1", "Oracion2", "Oracion3", "Oracion4", "Oracion5")
    val CAMPOS_KANJI: List<String> =
        listOf("Kanji", "OnYomi", "KunYomi", "Significados", "Dificultad", "Oracion1", "Oracion2", "Oracion3", "Oracion4", "Oracion5")

    // Alfabeto base91 EXACTO de Anki/genanki (91 símbolos, orden verbatim de
    // genanki/util.py BASE91_TABLE) — usado para convertir el hash del guid.
    private val TABLA_BASE91: List<Char> = listOf(
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's',
        't', 'u', 'v', 'w', 'x', 'y', 'z', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L',
        'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '0', '1', '2', '3', '4',
        '5', '6', '7', '8', '9', '!', '#', '$', '%', '&', '(', ')', '*', '+', ',', '-', '.', '/', ':',
        ';', '<', '=', '>', '?', '@', '[', ']', '^', '_', '`', '{', '|', '}', '~',
    )

    /** GUID determinístico estilo genanki (`guid_for` en genanki/util.py): SHA-256 de
     *  [clave] → primeros 8 bytes como entero SIN signo → convertido a la base91 de
     *  Anki. A diferencia de genanki (que hashea todos los campos de la nota),
     *  [clave] es solo el término/kanji — ver doc de [NotaWords.claveGuid]. */
    fun guidDe(clave: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(clave.toByteArray(Charsets.UTF_8))
        var n = 0UL
        for (i in 0 until 8) n = (n shl 8) or hash[i].toUByte().toULong()
        if (n == 0UL) return TABLA_BASE91[0].toString() // caso de borde, no debería darse con SHA-256 real
        val digitos = StringBuilder()
        var resto = n
        while (resto > 0UL) {
            digitos.append(TABLA_BASE91[(resto % 91UL).toInt()])
            resto /= 91UL
        }
        return digitos.reverse().toString()
    }

    fun nombreDeckHistoria(titulo: String): String = "$NOMBRE_DECK_STORIES::$titulo"

    /** Deck ID determinístico por historia: primeros 8 bytes de SHA-256 de
     *  "deck:<idHistoria>" mapeados al rango de 13 dígitos que usa Anki. Constante
     *  entre ejecuciones (mismo requisito que los IDs fijos: si cambiara, cada
     *  export crearía un deck nuevo en vez de actualizar). Los IDs fijos de 4a
     *  terminan en 1xx (1720000000101/102); la colisión con este hash es
     *  teóricamente posible pero despreciable (2 IDs sobre un rango de 9e12). */
    fun deckIdDeHistoria(idHistoria: String): Long {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("deck:$idHistoria".toByteArray(Charsets.UTF_8))
        var n = 0L
        for (i in 0 until 8) n = (n shl 8) or (hash[i].toLong() and 0xff)
        return (n and Long.MAX_VALUE) % 9_000_000_000_000L + 1_000_000_000_000L
    }

    // --- CSS estilo Kaishi: palabra/kanji grande, dark-friendly. Anki desktop marca
    // el modo noche con la clase `.night_mode` en <body> (no con
    // prefers-color-scheme); AnkiDroid respeta esa misma clase. Por eso el estilo
    // "oscuro" acá es el default (`.card`) y el claro es la EXCEPCIÓN
    // (`.night_mode` invertido no hace falta: ver comentario abajo). ---
    val CSS: String = """
        .card {
            font-family: "Noto Sans JP", "Hiragino Sans", "Yu Gothic", sans-serif;
            text-align: center;
            background-color: #1e1e1e;
            color: #f0f0f0;
        }
        .palabra, .kanji {
            font-size: 64px;
            font-weight: bold;
            margin: 24px 0 8px 0;
        }
        .lectura, .lecturas {
            font-size: 22px;
            color: #b0b0b0;
            margin-bottom: 10px;
        }
        .significados {
            font-size: 20px;
            margin: 10px 0;
        }
        .tag, .dificultad {
            display: inline-block;
            font-size: 14px;
            padding: 2px 10px;
            border-radius: 10px;
            background-color: #333333;
            color: #cccccc;
            margin-bottom: 10px;
        }
        hr#answer {
            margin: 18px auto;
            width: 60%;
            border: none;
            border-top: 1px solid #444444;
        }
        #oracion {
            font-size: 20px;
            line-height: 2;
            margin-top: 16px;
        }
        #oracion ruby rt {
            font-size: 11px;
            color: #999999;
            user-select: none;
        }
        #oracion .traduccion {
            display: block;
            font-size: 15px;
            color: #a0a0a0;
            margin-top: 4px;
        }
        #oracion .objetivo {
            color: #ffb74d;
            font-weight: bold;
        }
        .linea-lectura {
            margin: 2px 0;
        }
        .etiqueta-lectura {
            display: inline-block;
            font-size: 12px;
            color: #888888;
            min-width: 34px;
            text-align: right;
            margin-right: 8px;
        }
        /* Anki desktop en modo claro NO agrega clase al body (el modo oscuro sí
           agrega `.night_mode`) — por eso el override es ":not(.night_mode)" y no al
           revés. AnkiDroid respeta la misma convención de clase. */
        .card:not(.night_mode) {
            background-color: #ffffff;
            color: #111111;
        }
        .card:not(.night_mode) .lectura, .card:not(.night_mode) .lecturas {
            color: #555555;
        }
        .card:not(.night_mode) .tag, .card:not(.night_mode) .dificultad {
            background-color: #eeeeee;
            color: #333333;
        }
        .card:not(.night_mode) hr#answer {
            border-top-color: #cccccc;
        }
        .card:not(.night_mode) #oracion ruby rt, .card:not(.night_mode) #oracion .traduccion {
            color: #777777;
        }
        .card:not(.night_mode) #oracion .objetivo {
            color: #e65100;
        }
        .card:not(.night_mode) .etiqueta-lectura {
            color: #999999;
        }
    """.trimIndent()

    // --- Mecanismo de rotación (spec Plan 4a): Oracion1 se renderiza SIEMPRE en
    // #oracion (fallback garantizado sin JS); las 5 van también en divs ocultos; un
    // script al final junta las no-vacías y elige una al azar para reemplazar el
    // contenido de #oracion. El HTML de cada Oracion ya trae <ruby> embebida (armado
    // en ArmadorMazos, fuera de esta tarea) — el script mueve innerHTML tal cual, sin
    // tocar el marcado. ---
    private fun scriptRotacion(): String = """
        <div style="display:none">
            <div class="o1">{{Oracion1}}</div>
            <div class="o2">{{Oracion2}}</div>
            <div class="o3">{{Oracion3}}</div>
            <div class="o4">{{Oracion4}}</div>
            <div class="o5">{{Oracion5}}</div>
        </div>
        <script>
        (function() {
            var candidatas = [];
            var ocultos = document.querySelectorAll('.o1, .o2, .o3, .o4, .o5');
            for (var i = 0; i < ocultos.length; i++) {
                if (ocultos[i].innerHTML.trim() !== '') candidatas.push(ocultos[i].innerHTML);
            }
            if (candidatas.length > 0) {
                var elegido = candidatas[Math.floor(Math.random() * candidatas.length)];
                document.getElementById('oracion').innerHTML = elegido;
            }
        })();
        </script>
    """.trimIndent()

    val QFMT_WORDS: String = """<div class="palabra">{{Palabra}}</div>"""

    val AFMT_WORDS: String = """
        {{FrontSide}}
        <hr id="answer">
        <div class="lectura">{{Lectura}}</div>
        <div class="significados">{{Significados}}</div>
        {{#Tag}}<div class="tag">{{Tag}}</div>{{/Tag}}
        <div id="oracion">{{Oracion1}}</div>
        ${scriptRotacion()}
    """.trimIndent()

    val QFMT_KANJI: String = """<div class="kanji">{{Kanji}}</div>"""

    val AFMT_KANJI: String = """
        {{FrontSide}}
        <hr id="answer">
        <!-- kun/hiragana primero — feedback de uso 2026-07-13: es la lectura de uso más común, también en doblajes. -->
        <div class="lecturas">
            {{#KunYomi}}<div class="linea-lectura"><span class="etiqueta-lectura">kun</span><span class="kun">{{KunYomi}}</span></div>{{/KunYomi}}
            {{#OnYomi}}<div class="linea-lectura"><span class="etiqueta-lectura">on</span><span class="on">{{OnYomi}}</span></div>{{/OnYomi}}
        </div>
        <div class="significados">{{Significados}}</div>
        {{#Dificultad}}<div class="dificultad">[{{Dificultad}}]</div>{{/Dificultad}}
        <div id="oracion">{{Oracion1}}</div>
        ${scriptRotacion()}
    """.trimIndent()
}
