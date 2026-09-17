package com.tatoh.dokushorenshu

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pin del mecanismo de `capturaPendiente` en MainActivity, que NO se puede testear
 *  desde acá (vive dentro de setContent) pero cuyo idioma sí: un snapshotFlow que se
 *  limpia a sí mismo adentro del collect y se suspende en el medio.
 *
 *  Existe porque acá ya se escondió un bug que ningún test agarraba: keyeando el
 *  LaunchedEffect en el valor, el `= null` del cuerpo cancelaba el propio efecto
 *  mientras esperaba a Kuromoji y el navigate nunca corría. */
@OptIn(ExperimentalCoroutinesApi::class)
class CapturaPendienteTest {

    /** Limpiar el estado adentro del collect no corta la colecta ni pierde la entrega:
     *  es exactamente lo que hace el cuerpo antes de irse a Dispatchers.IO. */
    @Test
    fun `consumir el valor adentro del collect no cancela la entrega`() = runTest {
        val pendiente = mutableStateOf<Pair<String, String?>?>(null)
        val entregados = mutableListOf<Pair<String, String?>>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            snapshotFlow { pendiente.value }.filterNotNull().collect { actual ->
                pendiente.value = null   // consumido antes de la "IO"
                yield()                  // suspensión: acá es donde moría el efecto keyeado
                entregados += actual
            }
        }

        pendiente.value = "犬が走った。" to "/tmp/a.jpg"
        Snapshot.sendApplyNotifications(); testScheduler.advanceUntilIdle()

        assertEquals(listOf("犬が走った。" to "/tmp/a.jpg"), entregados)
        job.cancel()
    }

    /** Una segunda captura que llega con la primera todavía en vuelo también se entrega:
     *  snapshotFlow conflaciona contra el valor ACTUAL, y el null intermedio garantiza
     *  que la segunda no se descarte por igual a la anterior. */
    @Test
    fun `una segunda captura durante la primera tambien se entrega`() = runTest {
        val pendiente = mutableStateOf<Pair<String, String?>?>(null)
        val entregados = mutableListOf<Pair<String, String?>>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            snapshotFlow { pendiente.value }.filterNotNull().collect { actual ->
                pendiente.value = null
                yield()
                entregados += actual
            }
        }

        // Sin advanceUntilIdle en el medio: con el dispatcher unconfined el collect
        // arranca y queda frenado en el yield(), así que la segunda captura se escribe
        // con la primera TODAVÍA en vuelo — que es la superposición real que importa.
        pendiente.value = "uno" to null
        Snapshot.sendApplyNotifications()
        pendiente.value = "dos" to null
        Snapshot.sendApplyNotifications()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("uno" to null, "dos" to null), entregados)
        job.cancel()
    }
}
