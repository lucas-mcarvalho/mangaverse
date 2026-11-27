package com.mangaverse.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mangaverse.mobile.app
import com.mangaverse.mobile.data.CoverModel

val Canvas = Color(0xFF0B0B10)
val Surface1 = Color(0xFF101018)
val Surface2 = Color(0xFF1E1E2B)
val Line = Color(0xFF2A2A3B)
val Accent = Color(0xFF7C3AED)
val AccentLight = Color(0xFFA78BFA)
val Pink = Color(0xFFF472B6)
val Muted = Color(0xFFA1A1AA)

@Composable
fun MangaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Color.White, secondary = Pink, background = Canvas, surface = Surface1,
            surfaceVariant = Surface2, onSurface = Color(0xFFE4E4E7), onSurfaceVariant = Muted, outline = Line,
            surfaceContainer = Surface1, surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface2,
        ),
    ) {
        // fundo e cor de texto padrão para telas que não usam Scaffold
        androidx.compose.material3.Surface(color = Canvas, contentColor = Color(0xFFE4E4E7), content = content)
    }
}

/** Estado de uma carga assíncrona com "tentar de novo". */
class Load<T> {
    var data by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    internal var tick by mutableIntStateOf(0)
    fun reload() { tick++ }
}

@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): Load<T> {
    val state = remember(*keys) { Load<T>() }
    LaunchedEffect(*keys, state.tick) {
        state.loading = true
        state.error = null
        try {
            state.data = block()
        } catch (e: Exception) {
            state.error = e.friendly()
        }
        state.loading = false
    }
    return state
}

@Composable
fun <T> LoadView(load: Load<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val data = load.data
    when {
        data != null -> content(data)
        load.error != null -> ErrorBox(load.error!!, modifier) { load.reload() }
        else -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentLight) }
    }
}

@Composable
fun ErrorBox(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Muted, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Surface2)) { Text("Tentar novamente") }
        }
    }
}

@Composable
fun Cover(sourceId: Long, url: String?, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Surface2)) {
        if (!url.isNullOrBlank()) AsyncImage(CoverModel(sourceId, url), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** Capa com título, usada nas grades e fileiras. */
@Composable
fun TitleCard(
    title: String, sourceId: Long, cover: String?, subtitle: String? = null, progress: Float? = null, badge: String? = null,
    width: Dp? = null, onClick: () -> Unit,
) {
    Column((if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Box {
            Cover(sourceId, cover, Modifier.fillMaxWidth())
            if (badge != null) {
                Text(
                    badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Accent).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f)))))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) }, color = AccentLight, trackColor = Color.White.copy(alpha = .2f),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp))
        if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(horizontal = 2.dp))
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp))
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else Color(0xFFD4D4D8),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Accent else Surface2).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Executa [block] de novo sempre que o banco muda (ou as chaves mudam). `null` enquanto carrega. */
@Composable
fun <T> rememberQuery(vararg keys: Any?, block: suspend () -> T): T? {
    val app = androidx.compose.ui.platform.LocalContext.current.app
    val tick by app.db.changes.collectAsState()
    var value by remember(*keys) { mutableStateOf<T?>(null) }
    LaunchedEffect(tick, *keys) { value = runCatching { block() }.getOrNull() ?: value }
    return value
}

@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

fun Throwable.friendly(): String {
    val root = generateSequence(this) { it.cause }.last()
    return when (root) {
        is java.net.UnknownHostException -> "Sem conexão com a internet (ou o site está fora do ar)."
        is java.net.SocketTimeoutException -> "O site demorou demais para responder."
        else -> (message ?: root.message ?: root.javaClass.simpleName)
    }
}
