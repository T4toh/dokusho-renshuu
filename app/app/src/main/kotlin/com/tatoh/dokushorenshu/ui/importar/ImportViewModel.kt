package com.tatoh.dokushorenshu.ui.importar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tatoh.dokushorenshu.dominio.DetectorJapones
import com.tatoh.dokushorenshu.dominio.ImportadorHistoria
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class FormImport(
    val texto: String = "",
    val titulo: String = "",
    val autor: String = "",
    val dificultad: String = "medium",  // UI: easy|medium|hard
)

sealed interface EstadoImport {
    data object Idle : EstadoImport
    /** El texto no parece japonés (spec: avisar ANTES de guardar). La UI
     *  muestra diálogo continuar/cancelar; continuar = importar(forzar=true). */
    data object ConfirmarNoJapones : EstadoImport
    data object Importando : EstadoImport
    data class Listo(val id: String) : EstadoImport
    data class Error(val mensaje: String) : EstadoImport
}

/** UI easy/medium/hard → schema facil/media/dificil (contrato catálogo v2). */
private val DIFICULTAD_SCHEMA = mapOf("easy" to "facil", "medium" to "media", "hard" to "dificil")

class ImportViewModel(
    private val importador: ImportadorHistoria,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // inyectable: android.util.Log no existe en los tests de JVM plano
    private val log: (String, Throwable) -> Unit = { msg, t -> android.util.Log.e("ImportViewModel", msg, t) },
) : ViewModel() {
    private val _form = MutableStateFlow(FormImport())
    val form: StateFlow<FormImport> = _form

    private val _estado = MutableStateFlow<EstadoImport>(EstadoImport.Idle)
    val estado: StateFlow<EstadoImport> = _estado

    val puedeImportar: Boolean
        get() {
            val f = _form.value
            return f.texto.isNotBlank() && f.titulo.isNotBlank() && _estado.value !is EstadoImport.Importando
        }

    fun setTexto(v: String) { _form.value = _form.value.copy(texto = v) }
    fun setTitulo(v: String) { _form.value = _form.value.copy(titulo = v) }
    fun setAutor(v: String) { _form.value = _form.value.copy(autor = v) }
    fun setDificultad(v: String) { _form.value = _form.value.copy(dificultad = v) }

    fun descartarAviso() { _estado.value = EstadoImport.Idle }

    fun importar(forzar: Boolean = false) {
        val f = _form.value
        if (f.texto.isBlank() || f.titulo.isBlank()) return
        if (_estado.value is EstadoImport.Importando) return  // guard doble-tap
        if (!forzar && !DetectorJapones.pareceJapones(f.texto)) {
            _estado.value = EstadoImport.ConfirmarNoJapones
            return
        }
        _estado.value = EstadoImport.Importando
        viewModelScope.launch {
            try {
                val historia = withContext(ioDispatcher) {
                    importador.importar(
                        titulo = f.titulo,
                        autor = f.autor,
                        dificultad = DIFICULTAD_SCHEMA.getValue(f.dificultad),
                        texto = f.texto,
                    )
                }
                _estado.value = EstadoImport.Listo(historia.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("importar() falló", e)
                _estado.value = EstadoImport.Error("Import failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Lee el archivo elegido por el usuario en `ioDispatcher` (la lectura SAF puede
     *  bloquear) y decodifica UTF-8 estricto: bytes inválidos → Error, nunca mojibake
     *  silencioso (String(bytes) reemplazaría con U+FFFD sin avisar). `leerBytes` puede
     *  devolver null o lanzar (stream cerrado, permiso revocado, etc.) → Error visible
     *  en vez de fallar en silencio. */
    fun cargarArchivo(leerBytes: () -> ByteArray?) {
        viewModelScope.launch {
            val bytes = try {
                withContext(ioDispatcher) { leerBytes() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (bytes == null) {
                _estado.value = EstadoImport.Error("Could not read file")
                return@launch
            }
            try {
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val texto = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                _form.value = _form.value.copy(texto = texto)
                _estado.value = EstadoImport.Idle
            } catch (e: Exception) {
                _estado.value = EstadoImport.Error("File is not valid UTF-8 text")
            }
        }
    }
}
