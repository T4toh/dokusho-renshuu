package com.tatoh.dokushorenshu.ui.lector

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tatoh.dokushorenshu.dominio.PalabraToken
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
    val contexto = LocalContext.current
    val portapapeles = LocalClipboardManager.current

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
                        TextButton(onClick = vm::alternarKatakana) {
                            Text(if (estado.katakanaActiva) "カナ ON" else "カナ OFF")
                        }
                    }
                },
            )
        },
        bottomBar = {
            val seleccionado = estado.textoSeleccionado
            if (seleccionado != null) {
                // Con selección activa la barra de navegación cede su lugar a las acciones
                // de selección (backlog feedback de uso 2026-07-13): Search web abre el
                // browser con la frase (el diccionario no tiene expresiones largas), Copy
                // al portapapeles, ✕ cancela. Previous/Next vuelven al limpiar.
                BarraSeleccion(
                    texto = seleccionado,
                    onBuscarWeb = { buscarEnWeb(contexto, seleccionado) },
                    onCopiar = {
                        portapapeles.setText(AnnotatedString(seleccionado))
                        vm.limpiarSeleccion()
                    },
                    onCancelar = vm::limpiarSeleccion,
                )
            } else {
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
 *  Indicador de centro ("piquito", feedback de dispositivo post-scroll-libre): una
 *  marca fija en el margen izquierdo señala la línea vertical de centro del viewport,
 *  así el usuario sabe dónde soltar/ubicar la oración que quiere leer mientras scrollea
 *  libremente — sin esto no era obvio dónde caía el centro real (el `SnapPosition` es
 *  invisible hasta que se suelta el dedo). Vive fuera de la `LazyColumn` (hijo directo
 *  del `BoxWithConstraints`) por eso NO scrollea con el contenido, y no tiene ningún
 *  modifier de gestos (sin `clickable`/`pointerInput`) por eso no intercepta toques.
 *
 *  Hay dos flujos que mueven el foco y hace falta que NUNCA se disparen entre sí (fix
 *  del "rubberbanding" reportado: la oración saltaba de posición sola justo después de
 *  soltar el dedo):
 *  - foco EXPLÍCITO → scroll: keyeado en [EstadoLector.centradoPedido] (no en
 *    `indiceActual`). Este contador solo lo incrementan `mover()` (Previous/Next,
 *    incluido el salto Start/Continue desde la portada) y la carga inicial — nunca
 *    `enfocar()`. Centramos la oración `indiceActual` vigente al momento del cambio.
 *
 *    Fix de "overshoot" (feedback de dispositivo: Previous/Next se pasaba de largo y
 *    recién después volvía — encadenar `animateScrollToItem` con una `animateScrollBy`
 *    correctiva son DOS animaciones visualmente distintas, y la segunda se ve como una
 *    corrección tardía). Ahora es SIEMPRE una sola animación:
 *    - Si el item destino YA está en `visibleItemsInfo` (caso común: Previous/Next mueve
 *      a un vecino ya compuesto), vamos derecho a la corrección exacta con UNA sola
 *      `animateScrollBy(delta)` — sin pasar antes por `animateScrollToItem`.
 *    - Si NO está visible/medido todavía (salto largo o carga inicial, donde el item
 *      puede estar lejísimos de lo que hay compuesto), primero un `scrollToItem` INSTANTÁNEO
 *      (sin animación — no cuenta como una de las dos animaciones prohibidas) para que
 *      el item quede compuesto y medido, y RECIÉN AHÍ la única `animateScrollBy(delta)`
 *      de corrección.
 *    El delta, en ambos casos, es el mismo cálculo (fix de bf50f05): centro del item
 *    (`item.offset + item.size / 2`) menos el centro REAL del viewport, calculado con
 *    `viewportStartOffset`/`viewportEndOffset` —NUNCA `viewportSize.height / 2`, que
 *    queda corrido por el `contentPadding` de medio viewport que usamos para poder
 *    centrar la primera y la última oración.
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
                // Fix "overshoot" (feedback de dispositivo): NUNCA dos animaciones
                // encadenadas (antes: animateScrollToItem + animateScrollBy correctiva —
                // dos animaciones visualmente distintas, la segunda se veía como que la
                // oración "se pasaba y después volvía"). Ahora es SIEMPRE una animación:
                // si el destino ya está compuesto (caso común de Previous/Next a un
                // vecino), vamos derecho a la corrección exacta; si no está medido
                // todavía (salto largo o carga inicial), un salto instantáneo primero
                // (sin animación, no cuenta como una de las dos prohibidas) para poder
                // medirlo, y recién ahí la única animación de corrección.
                val yaCompuesto = listaEstado.layoutInfo.visibleItemsInfo.any { it.index == estado.indiceActual }
                if (!yaCompuesto) {
                    listaEstado.scrollToItem(estado.indiceActual, 0)
                }
                // Corrección exacta (fix de bf50f05): centro del item —
                // `item.offset + item.size / 2`— menos el centro REAL del viewport,
                // calculado con `viewportStartOffset`/`viewportEndOffset` —NUNCA con
                // `viewportSize.height / 2`— porque `item.offset` está medido en el
                // mismo sistema de coordenadas que esos dos campos, que NO arranca en
                // el borde visible sino que resta el `contentPadding` (acá, medio
                // viewport arriba/abajo para poder centrar la primera y la última
                // oración). Mismo criterio que usa, correctamente, el detector de
                // asentado del scroll manual (`centroViewport`, más abajo).
                listaEstado.layoutInfo.visibleItemsInfo
                    .find { item -> item.index == estado.indiceActual }
                    ?.let { item ->
                        val info = listaEstado.layoutInfo
                        val centroViewport = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                        val delta = (item.offset + item.size / 2f) - centroViewport
                        listaEstado.animateScrollBy(delta)
                    }
            } catch (c: CancellationException) {
                // Si el gesto del usuario cancela la animación, limpiamos el flag para
                // que el siguiente asentado legítimo no se consuma sin reenfocar.
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
                // esActual se calcula ACÁ, en el callsite de itemsIndexed, como un
                // Boolean plano (fix de performance, Plan 3.6 feedback de dispositivo):
                // es el único dato de foco que entra a ItemOracion, nunca `estado`
                // completo — así un cambio ajeno al foco (p.ej. abrir el sheet de
                // palabra) no fuerza recomponer TODAS las oraciones visibles.
                ItemOracion(
                    esActual = indice == estado.indiceActual,
                    plana = plana,
                    furiganaActiva = estado.furiganaActiva,
                    katakanaActiva = estado.katakanaActiva,
                    onTapPalabra = { token -> vm.tapPalabra(indice, token) },
                    onLongPressPalabra = { token -> vm.iniciarSeleccion(indice, token) },
                    // rango de selección SOLO si pertenece a esta oración (mismo criterio
                    // que esActual, ver doc de ItemOracion): nunca entra EstadoLector
                    // completo, así un cambio de selección solo recompone los items cuyo
                    // param cambió.
                    rangoSeleccion = estado.seleccion
                        ?.takeIf { it.indiceOracion == indice }
                        ?.let { it.inicio until it.fin },
                )
            }
        }

        // Indicador de centro ("piquito", feedback de dispositivo): marca fija en el
        // margen izquierdo que señala la línea vertical central del viewport, para que
        // el usuario sepa dónde soltar/ubicar la oración que quiere leer durante el
        // scroll libre. Vive FUERA de la LazyColumn (hijo directo de este
        // BoxWithConstraints) así que NO scrollea con el contenido; no tiene ningún
        // modifier de gestos (sin `clickable` ni `pointerInput`) así que no intercepta
        // toques — el scroll/tap de la lista de abajo pasa de largo.
        // Al lado va el número (1-based) de la oración enfocada (backlog feedback de
        // uso 2026-07-13: poder reportar casos puntuales por número de oración).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
        ) {
            if (estado.indiceActual >= 0) {
                Text(
                    text = "${estado.indiceActual + 1}",
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = "▸",
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        // Contraparte espejada del indicador de centro, en el margen derecho (Plan 3.6):
        // misma línea vertical central, misma altura, mismo estilo/alpha — así la marca
        // de centro queda simétrica en vez de sugerir (erróneamente) que el punto de
        // referencia está pegado a la izquierda. También vive fuera de la LazyColumn y
        // sin modifiers de gestos, por los mismos motivos que su par de la izquierda.
        Text(
            text = "◂",
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
        )
    }
}

/** Un item de oración de [ListaOracionesLibre], extraído a su propio composable (fix de
 *  performance, Plan 3.6 feedback de dispositivo): así Compose puede saltear su
 *  recomposición cuando cambia algo AJENO a esta oración (p.ej. se abre el sheet de
 *  palabra, o cualquier otro campo de [EstadoLector] no relacionado con el foco). Solo
 *  recibe [esActual] (un `Boolean` plano, ya comparado en el callsite de `itemsIndexed`)
 *  y los datos propios de la oración — nunca `EstadoLector` completo. */
@Composable
private fun ItemOracion(
    esActual: Boolean,
    plana: OracionPlana,
    furiganaActiva: Boolean,
    katakanaActiva: Boolean,
    onTapPalabra: (PalabraToken) -> Unit,
    onLongPressPalabra: (PalabraToken) -> Unit,
    // rango de selección SOLO si pertenece a esta oración (ya filtrado en el
    // callsite de itemsIndexed, mismo criterio que esActual: nunca entra
    // EstadoLector completo — un cambio de selección solo recompone los items
    // cuyo param cambió).
    rangoSeleccion: IntRange?,
) {
    // Foco SOLO por alpha (animado), nunca por tamaño: todas las oraciones tienen la
    // misma altura de item siempre, así que cambiar el foco jamás reflowea la
    // LazyColumn — únicamente el scroll mueve cosas. El fundido de ~250ms hace que el
    // foco se deslice entre oraciones en vez de saltar.
    val alphaAnimada by animateFloatAsState(
        targetValue = if (esActual) 1f else 0.35f,
        animationSpec = tween(durationMillis = 250),
        label = "alphaOracion",
    )
    Box(Modifier.alpha(alphaAnimada).fillMaxWidth()) {
        TextoConFurigana(
            tokens = plana.tokens,
            gruposFurigana = plana.gruposFurigana,
            furiganaActiva = furiganaActiva,
            katakanaActiva = katakanaActiva,
            onTapPalabra = onTapPalabra,
            onLongPressPalabra = onLongPressPalabra,
            rangoSeleccion = rangoSeleccion,
        )
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

/** Barra contextual de selección: texto elegido + Search web / Copy / cancelar.
 *  Reemplaza a Previous/Next en el bottomBar mientras hay selección activa. */
@Composable
private fun BarraSeleccion(
    texto: String,
    onBuscarWeb: () -> Unit,
    onCopiar: () -> Unit,
    onCancelar: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            texto,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCopiar) { Text("Copy") }
        Button(onClick = onBuscarWeb) { Text("Search web") }
        TextButton(onClick = onCancelar) { Text("✕") }
    }
}

/** Abre la búsqueda web del texto seleccionado en el BROWSER DEFAULT del usuario:
 *  ACTION_VIEW con la URL de búsqueda. Antes se intentaba ACTION_WEB_SEARCH
 *  primero, pero en MIUI (y otros OEM) lo captura la app de búsqueda
 *  (Google/Xiaomi) que abre su webview embebido en vez del browser elegido por
 *  el usuario — feedback de uso 2026-07-16. Si no hay browser (emulador
 *  pelado), no crashear: la selección queda para Copy. */
private fun buscarEnWeb(contexto: Context, texto: String) {
    val url = "https://www.google.com/search?q=${Uri.encode(texto)}"
    runCatching { contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
