package com.tatoh.dokushorenshu.datos.progreso

/** Preferencias sobre la tabla prefs. Los toggles de furigana y katakana son
 *  globales y persisten (spec Plan 3.5 / Plan 3.7); ambos independientes entre sí. */
class PrefsRepo(private val dao: ProgresoDao) {
    suspend fun furiganaActiva(): Boolean = dao.pref(CLAVE_FURIGANA) != "off"

    suspend fun setFuriganaActiva(activa: Boolean) {
        dao.guardarPref(Pref(CLAVE_FURIGANA, if (activa) "on" else "off"))
    }

    /** Katakana-ruby (Plan 3.7): default ON, misma semántica `!= "off"` que furigana. */
    suspend fun katakanaActiva(): Boolean = dao.pref(CLAVE_KATAKANA) != "off"

    suspend fun setKatakanaActiva(activa: Boolean) {
        dao.guardarPref(Pref(CLAVE_KATAKANA, if (activa) "on" else "off"))
    }

    private companion object {
        const val CLAVE_FURIGANA = "furigana"
        const val CLAVE_KATAKANA = "katakana"
    }
}
