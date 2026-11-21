package com.mangaverse.mobile.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max

const val MAX_ZOOM = 4f
const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Zoom e deslocamento de uma área (uma página ou a coluna inteira do modo vertical). Com zoom 1 os gestos de um dedo
 * passam para quem está embaixo (paginador ou lista); com zoom, um dedo arrasta a imagem.
 */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var size = IntSize.Zero
    /** Só desloca na horizontal (modo vertical: o eixo Y é a rolagem da lista). */
    var horizontalOnly = false

    val zoomed get() = scale > 1.01f

    private fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1) / 2f
        val maxY = if (horizontalOnly) 0f else size.height * (s - 1) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    /** Aplica pinça em torno de `centroid` (coordenadas da área) e arrasto. Devolve o deslocamento vertical não usado. */
    fun transform(zoom: Float, pan: Offset, centroid: Offset): Float {
        val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        val center = Offset(size.width / 2f, size.height / 2f)
        // mantém o ponto sob os dedos no lugar ao mudar o zoom
        val k = newScale / scale
        var o = (centroid - center) * (1 - k) + offset * k + pan
        if (horizontalOnly) o = Offset(o.x, 0f)
        val clamped = clamp(o, newScale)
        scale = newScale
        offset = clamped
        return if (horizontalOnly) pan.y else 0f
    }

    private val anim = Animatable(0f)

    /** Duplo toque: aproxima no ponto tocado ou volta ao tamanho normal. */
    fun toggle(scope: CoroutineScope, at: Offset) {
        val from = scale
        val fromOffset = offset
        val to = if (zoomed) 1f else DOUBLE_TAP_ZOOM
        val center = Offset(size.width / 2f, size.height / 2f)
        val target = if (to == 1f) Offset.Zero else clamp((center - at) * (to - 1), to)
        scope.launch {
            anim.snapTo(0f)
            anim.animateTo(1f) {
                val t = value
                scale = from + (to - from) * t
                offset = fromOffset + (target - fromOffset) * t
            }
        }
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

fun Modifier.zoomLayer(state: ZoomState) = graphicsLayer {
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.offset.x
    translationY = state.offset.y
}

/**
 * Gestos de zoom. Roda no passe inicial para "pegar" a pinça antes do paginador/lista. `onVerticalPan` recebe o
 * arrasto vertical quando o zoom só desloca na horizontal (a lista rola por ele).
 */
fun Modifier.zoomGestures(state: ZoomState, onVerticalPan: ((Float) -> Unit)? = null) = pointerInput(state) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val fingers = event.changes.count { it.pressed }
            if (fingers >= 2 || (state.zoomed && fingers == 1)) {
                val zoom = if (fingers >= 2) event.calculateZoom() else 1f
                val pan = event.calculatePan()
                val centroid = event.calculateCentroid(useCurrent = true)
                if (zoom != 1f || pan != Offset.Zero) {
                    val rest = state.transform(zoom, pan, if (centroid == Offset.Unspecified) Offset(size.width / 2f, size.height / 2f) else centroid)
                    if (rest != 0f) onVerticalPan?.invoke(rest / max(1f, state.scale))
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** Toques: simples (zonas de navegação/menu) e duplo (zoom). */
fun Modifier.readerTaps(state: ZoomState, scope: CoroutineScope, onTap: (Offset, IntSize) -> Unit) = pointerInput(state) {
    detectTapGestures(
        onDoubleTap = { state.toggle(scope, it) },
        onTap = { onTap(it, size) },
    )
}
