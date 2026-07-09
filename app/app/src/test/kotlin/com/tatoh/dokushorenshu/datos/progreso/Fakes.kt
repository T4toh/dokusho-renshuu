package com.tatoh.dokushorenshu.datos.progreso

/** ProgresoDao in-memory: suficiente para ViewModels, no ejercita Room.
 *  Compartido entre BibliotecaViewModelTest (Task 9) y LectorViewModelTest (Task 10). */
class ProgresoDaoFake : ProgresoDao {
    private val progresos = mutableMapOf<String, ProgresoHistoria>()
    private val palabras = mutableListOf<PalabraTocada>()
    private val prefs = mutableMapOf<String, String>()
    private val kanjisTocados = mutableMapOf<String, KanjiTocado>()

    override suspend fun progreso(id: String): ProgresoHistoria? = progresos[id]

    override suspend fun todos(): List<ProgresoHistoria> = progresos.values.toList()

    override suspend fun guardarProgreso(progreso: ProgresoHistoria) {
        progresos[progreso.idHistoria] = progreso
    }

    override suspend fun registrarPalabra(palabra: PalabraTocada) {
        palabras.add(palabra)
    }

    override suspend fun palabrasDe(id: String): List<PalabraTocada> =
        palabras.filter { it.idHistoria == id }

    override suspend fun pref(clave: String): String? = prefs[clave]

    override suspend fun guardarPref(pref: Pref) {
        prefs[pref.clave] = pref.valor
    }

    override suspend fun registrarAperturaKanji(kanji: String, timestamp: Long) {
        val existente = kanjisTocados[kanji]
        kanjisTocados[kanji] = KanjiTocado(kanji, existente?.dificultad, timestamp)
    }

    override suspend fun setDificultadKanji(kanji: String, dificultad: String?) {
        kanjisTocados[kanji]?.let { kanjisTocados[kanji] = it.copy(dificultad = dificultad) }
    }

    override suspend fun kanjiTocado(kanji: String): KanjiTocado? = kanjisTocados[kanji]

    override suspend fun kanjisPorDificultad(dificultad: String): List<KanjiTocado> =
        kanjisTocados.values.filter { it.dificultad == dificultad }.sortedBy { it.timestamp }
}
