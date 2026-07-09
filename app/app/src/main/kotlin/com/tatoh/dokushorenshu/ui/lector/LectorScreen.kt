package com.tatoh.dokushorenshu.ui.lector

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
import kotlinx.coroutines.flow.filter
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
 *  antes. La oración [EstadoLector.indiceActual] se resalta (grande, opaca) y queda
 *  centrada en el viewport; el resto se ve atenuada pero sigue siendo tappeable: tocar
 *  cualquier palabra de contenido de CUALQUIER oración visible la enfoca y abre el sheet
 *  (mismo `vm.tocarPalabra`, comportamiento del sheet intacto).
 *
 *  Hay dos flujos que mueven el foco y hay que evitar que se peleen entre ellos:
 *  - foco → scroll: cuando cambia `indiceActual` (Previous/Next, tap o carga inicial),
 *    centramos la oración. La primera vez que esta lista se monta usamos `scrollToItem`
 *    (salto instantáneo, sin animación: así restaurar el progreso guardado no arranca
 *    con un scroll largo animado); de ahí en más, `animateScrollToItem`.
 *  - scroll → foco: cuando el usuario suelta el dedo y la lista se asienta
 *    (`isScrollInProgress` pasa a `false`), buscamos en `layoutInfo` la oración cuyo
 *    centro quedó más cerca del centro del viewport y llamamos `vm.enfocar(i)`.
 *
 *  Para que el auto-scroll programático del primer flujo no dispare de vuelta el
 *  segundo (reenfocando en pleno movimiento, o "peleando" con el centrado), usamos el
 *  flag [scrollProgramatico]: se prende antes de pedir el scroll y solo lo apaga el
 *  detector de asentado al consumirlo (nunca el propio scroll al terminar) — así no hay
 *  carrera entre la corrutina que anima el scroll y la que observa `isScrollInProgress`.
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

        // foco -> scroll: al cambiar la oración actual (por cualquier vía), centrarla.
        LaunchedEffect(estado.indiceActual, estado.oraciones) {
            if (estado.oraciones.isEmpty() || estado.indiceActual !in estado.oraciones.indices) {
                return@LaunchedEffect
            }
            scrollProgramatico = true
            // Offset negativo (aproximación documentada en el brief): el alto real de la
            // oración no se conoce antes de layoutear, así que centramos "a ojo" corriendo
            // el borde superior del item 1/3 de viewport hacia abajo del techo de la lista.
            val offset = -(listaEstado.layoutInfo.viewportSize.height / 3)
            if (primeraVez) {
                listaEstado.scrollToItem(estado.indiceActual, offset)
                primeraVez = false
            } else {
                listaEstado.animateScrollToItem(estado.indiceActual, offset)
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
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 700.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(estado.oraciones, key = { indice, _ -> indice }) { indice, plana ->
                val esActual = indice == estado.indiceActual
                Box(Modifier.alpha(if (esActual) 1f else 0.4f).fillMaxWidth()) {
                    TextoConFurigana(
                        oracion = plana.oracion,
                        tokens = plana.tokens,
                        furiganaActiva = estado.furiganaActiva,
                        grande = esActual,
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
