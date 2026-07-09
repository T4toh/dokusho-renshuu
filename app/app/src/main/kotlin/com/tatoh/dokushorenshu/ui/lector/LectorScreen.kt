package com.tatoh.dokushorenshu.ui.lector

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LectorScreen(vm: LectorViewModel, onVerKanji: (String) -> Unit) {
    val estado by vm.estado.collectAsState()
    // skipPartiallyExpanded = true: el sheet de palabra abre directo al alto de su
    // contenido (o al máximo si es largo) en vez de quedar a mitad de pantalla.
    // Sin esto, en dispositivos reales el estado partially-expanded queda "trabado"
    // (el drag-up del handle y el scroll interno no lo expanden) dejando contenido
    // inalcanzable — bug validado en tablet.
    val estadoSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // carga inicial al entrar a la pantalla (mismo patrón que BibliotecaScreen, Task 9)
    LaunchedEffect(Unit) { vm.cargar() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(estado.titulo) },
                actions = {
                    if (!estado.enPortada) {
                        TextButton(onClick = vm::alternarFurigana) {
                            Text(if (estado.furiganaActiva) "Furigana ON" else "Furigana OFF")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(onClick = vm::retroceder, enabled = estado.indiceActual > -1) {
                    Text("Previous")
                }
                Button(onClick = vm::avanzar) {
                    Text(if (estado.enPortada) (if (estado.progresoGuardado >= 0) "Continue reading" else "Start reading") else "Next")
                }
            }
        },
    ) { relleno ->
        if (estado.enPortada) {
            Portada(estado, Modifier.padding(relleno))
        } else {
            ListaOracionesLibre(estado, vm, Modifier.padding(relleno))
        }
    }

    estado.consulta?.let { consulta ->
        ModalBottomSheet(onDismissRequest = vm::cerrarSheet, sheetState = estadoSheet) {
            PalabraSheet(consulta, onVerKanji)
        }
    }
}

/** Lector estilo letras de reproductor (Plan 3.6 Task 2): TODAS las oraciones viven en
 *  una única `LazyColumn` con scroll libre (el dedo manda), no una ventana acotada como
 *  antes. La oración [EstadoLector.indiceActual] se resalta (opaca, alpha 1f) y queda
 *  centrada en el viewport; el resto se ve atenuada (alpha 0.35f, con fundido animado de
 *  ~250ms) pero sigue siendo tappeable: tocar cualquier palabra de contenido de CUALQUIER
 *  oración visible la enfoca y abre el sheet (mismo `vm.tocarPalabra`, comportamiento del
 *  sheet intacto). TODAS las oraciones se renderizan al MISMO tamaño (ver
 *  [TextoConFurigana]) — el foco nunca cambia el alto del item, así el reflow de la
 *  `LazyColumn` a mitad de scroll (el bug de "la animación es confusa" reportado) queda
 *  eliminado de raíz: solo el scroll mueve cosas, el foco solo cambia de color.
 *
 *  Hay dos flujos que mueven el foco y hace falta que NUNCA se disparen entre sí (fix
 *  del "rubberbanding" reportado: la oración saltaba de posición sola justo después de
 *  soltar el dedo):
 *  - foco EXPLÍCITO → scroll: keyeado en [EstadoLector.centradoPedido] (no en
 *    `indiceActual`). Este contador solo lo incrementan `mover()` (Previous/Next,
 *    incluido el salto Start/Continue desde la portada) y la carga inicial — nunca
 *    `enfocar()`. Centramos la oración `indiceActual` vigente al momento del cambio. La
 *    primera vez que esta lista se monta usamos `scrollToItem` (salto instantáneo, sin
 *    animación: así restaurar el progreso guardado no arranca con un scroll largo
 *    animado); de ahí en más, `animateScrollToItem`. En ambos casos el centrado se hace
 *    en dos fases: fase 1 lleva el TECHO del item exactamente al ancla `scrollOffset = 0`
 *    (la única posición garantizada sin conocer el alto real del item, que todavía no
 *    está layouteado); fase 2, ya con el item layouteado, lo corre para que su CENTRO
 *    —calculado con `viewportStartOffset`/`viewportEndOffset`, el mismo sistema de
 *    coordenadas que usa `item.offset`— quede exactamente en el centro real del viewport,
 *    mismo resultado final que `SnapPosition.Center`.
 *  - scroll del usuario → foco: la `LazyColumn` usa `rememberSnapFlingBehavior` con
 *    `SnapPosition.Center`, así que soltar el dedo YA asienta la oración más cercana
 *    exactamente centrada (sin necesidad de corrección posterior). Cuando ese asentado
 *    termina (`isScrollInProgress` pasa a `false`) buscamos en `layoutInfo` la oración
 *    cuyo centro quedó más cerca del centro del viewport y llamamos `vm.enfocar(i)` —
 *    que NO toca `centradoPedido`, así este foco jamás dispara una segunda corrección de
 *    scroll (esa doble corrección era exactamente el bug reportado).
 *
 *  Para que el auto-scroll programático del primer flujo no dispare de vuelta el
 *  segundo (reenfocando en pleno movimiento, o "peleando" con el centrado), usamos el
 *  flag [scrollProgramatico]: se prende antes de pedir el scroll y solo lo apaga el
 *  detector de asentado al consumirlo (nunca el propio scroll al terminar) — así no hay
 *  carrera entre la corrutina que anima el scroll y la que observa `isScrollInProgress`.
 *  Esto solo hace falta para la navegación explícita: el asentado del snap del usuario
 *  nunca prende este flag, así que siempre reenfoca.
 */
@Composable
private fun ListaOracionesLibre(estado: EstadoLector, vm: LectorViewModel, modifier: Modifier = Modifier) {
    val listaEstado = rememberLazyListState()
    var primeraVez by remember { mutableStateOf(true) }
    var scrollProgramatico by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // padding grande arriba/abajo (mitad del viewport): así hasta la primera y la
        // última oración pueden llegar a centrarse; sin este aire, el borde de la lista
        // se lo impediría.
        val paddingCentrado = maxHeight / 2

        // foco EXPLÍCITO -> scroll: keyeado en centradoPedido (NO en indiceActual), así
        // el foco por asentado de scroll o tap (enfocar(), que no toca centradoPedido)
        // nunca dispara este efecto — solo Previous/Next/salto de portada/carga inicial.
        LaunchedEffect(estado.centradoPedido, estado.oraciones) {
            if (estado.oraciones.isEmpty() || estado.indiceActual !in estado.oraciones.indices) {
                return@LaunchedEffect
            }
            scrollProgramatico = true
            try {
                // Esperamos a que el viewport tenga alto medido: en el primer frame
                // (carga inicial o navegación explícita justo al entrar a la pantalla)
                // el layout todavía no corrió y viewportSize.height es 0. Este await
                // queda DENTRO del try para que una cancelación durante la espera
                // también limpie el flag (catch de abajo).
                snapshotFlow { listaEstado.layoutInfo.viewportSize.height }.first { it > 0 }
                // Fase 1: llevamos el TECHO del item exactamente al techo del viewport
                // (`scrollOffset = 0`, el valor por default). A diferencia de una
                // aproximación "a ojo" con un offset estimado (medio viewport hacia
                // arriba, el intento anterior), offset 0 es la ÚNICA posición que
                // Compose garantiza SIN depender de ninguna medida previa: el item
                // pasa a ser el ancla del scroll, así que SIEMPRE termina dentro de
                // `visibleItemsInfo` al terminar esta llamada — sin importar cuántas
                // líneas ocupe. El offset estimado sí podía, para una oración larga,
                // dejar el item fuera del rango medido: el `find` de la fase 2 no lo
                // encontraba, no corregía nada, y la oración enfocada (la única con
                // alpha 1f) terminaba sin siquiera componerse — bug validado en
                // dispositivo con la oración #28 de `momotaro` (ninguna oración
                // visible alcanzaba alpha 1f y ninguna quedaba centrada).
                val esInstantaneo = primeraVez
                if (esInstantaneo) {
                    listaEstado.scrollToItem(estado.indiceActual)
                    primeraVez = false
                } else {
                    listaEstado.animateScrollToItem(estado.indiceActual)
                }
                // Fase 2 (corrección exacta): con el item YA garantizado visible y
                // layouteado tras la fase 1, leemos su alto REAL y lo corremos para
                // que su CENTRO —no su techo— quede en el centro del viewport. El
                // centro se calcula con `viewportStartOffset`/`viewportEndOffset` —
                // NUNCA con `viewportSize.height / 2` — porque `item.offset` está
                // medido en el mismo sistema de coordenadas que esos dos campos, que
                // NO arranca en el borde visible sino que resta el `contentPadding`
                // (acá, medio viewport arriba/abajo para poder centrar la primera y la
                // última oración): con `paddingCentrado` grande, `viewportStartOffset`
                // termina siendo `-viewportSize.height / 2`, así que usar
                // `viewportSize.height / 2` como centro quedaba corrido exactamente esa
                // mitad de viewport — la oración enfocada terminaba pegada abajo en vez
                // de centrada (bug validado en dispositivo, oración #23 de `momotaro`:
                // el cálculo daba un `delta` con el signo/magnitud de una pantalla
                // entera de más). Este es el MISMO criterio que ya usa, correctamente,
                // el detector de asentado del scroll manual (`centroViewport`, más
                // abajo) — antes duplicado con una fórmula distinta e inconsistente.
                listaEstado.layoutInfo.visibleItemsInfo
                    .find { item -> item.index == estado.indiceActual }
                    ?.let { item ->
                        val info = listaEstado.layoutInfo
                        val centroViewport = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                        val delta = (item.size / 2f) - centroViewport
                        if (esInstantaneo) listaEstado.scrollBy(delta) else listaEstado.animateScrollBy(delta)
                    }
            } catch (c: CancellationException) {
                // Si el gesto del usuario cancela la animación (fase 1 o 2), limpiamos el
                // flag para que el siguiente asentado legítimo no se consuma sin reenfocar.
                scrollProgramatico = false
                throw c
            }
        }

        // scroll -> foco: al asentarse un scroll del usuario, enfocar la oración más
        // centrada. Si el asentado lo causó nuestro propio scroll programático, se
        // consume el flag sin reenfocar (evita pelear con el auto-centrado de arriba).
        LaunchedEffect(listaEstado) {
            snapshotFlow { listaEstado.isScrollInProgress }
                .filter { enProgreso -> !enProgreso }
                .collect {
                    if (scrollProgramatico) {
                        scrollProgramatico = false
                        return@collect
                    }
                    val info = listaEstado.layoutInfo
                    val centroViewport = (info.viewportStartOffset + info.viewportEndOffset) / 2
                    info.visibleItemsInfo
                        .minByOrNull { item -> abs((item.offset + item.size / 2) - centroViewport) }
                        ?.let { vm.enfocar(it.index) }
                }
        }

        LazyColumn(
            state = listaEstado,
            contentPadding = PaddingValues(vertical = paddingCentrado),
            // snap al centro (fix rubberbanding): soltar el dedo asienta la oración más
            // cercana ya centrada, sin depender de una corrección posterior.
            flingBehavior = rememberSnapFlingBehavior(
                lazyListState = listaEstado,
                snapPosition = SnapPosition.Center,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 700.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(estado.oraciones, key = { indice, _ -> indice }) { indice, plana ->
                val esActual = indice == estado.indiceActual
                // Foco SOLO por alpha (animado), nunca por tamaño: todas las oraciones
                // tienen la misma altura de item siempre, así que cambiar el foco jamás
                // reflowea la LazyColumn — únicamente el scroll mueve cosas. El fundido
                // de ~250ms hace que el foco se deslice entre oraciones en vez de saltar.
                val alphaAnimada by animateFloatAsState(
                    targetValue = if (esActual) 1f else 0.35f,
                    animationSpec = tween(durationMillis = 250),
                    label = "alphaOracion",
                )
                Box(Modifier.alpha(alphaAnimada).fillMaxWidth()) {
                    TextoConFurigana(
                        oracion = plana.oracion,
                        tokens = plana.tokens,
                        furiganaActiva = estado.furiganaActiva,
                        onTapPalabra = { token ->
                            vm.enfocar(indice)
                            vm.tocarPalabra(token)
                        },
                    )
                }
            }
        }
    }
}

/** Portada de la historia (Task C3): se muestra cuando indiceActual == -1, antes
 *  de arrancar a leer o al retroceder desde la primera oración. */
@Composable
private fun Portada(estado: EstadoLector, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(estado.titulo, style = MaterialTheme.typography.displaySmall)
        estado.metadata?.tituloLectura?.let {
            Text(
                it, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        estado.metadata?.tituloEn?.let {
            Text(it, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
        }
        Text(estado.autor, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        estado.metadata?.let { meta ->
            Text(
                "${meta.kanjisUnicos} unique kanji · ${meta.oraciones} sentences · ${estado.porcentajeLeido}% read",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
