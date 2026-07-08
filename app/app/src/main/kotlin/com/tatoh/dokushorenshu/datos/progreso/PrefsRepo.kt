package com.tatoh.dokushorenshu.datos.progreso

/** Preferencias sobre la tabla prefs. El toggle de furigana es global y persiste (spec). */
class PrefsRepo(private val dao: ProgresoDao) {
    suspend fun furiganaActiva(): Boolean = dao.pref(CLAVE_FURIGANA) != "off"

    suspend fun setFuriganaActiva(activa: Boolean) {
        dao.guardarPref(Pref(CLAVE_FURIGANA, if (activa) "on" else "off"))
    }

    private companion object {
        const val CLAVE_FURIGANA = "furigana"
    }
}
