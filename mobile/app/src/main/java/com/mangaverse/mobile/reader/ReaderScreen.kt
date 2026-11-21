package com.mangaverse.mobile.reader

import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewDay
import android.app.Activity
import android.content.Context
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.SubcomposeAsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Size
import com.mangaverse.mobile.App
import com.mangaverse.mobile.MainActivity
import com.mangaverse.mobile.app
import com.mangaverse.mobile.data.Chapter
import com.mangaverse.mobile.data.ExternalChapterException
import com.mangaverse.mobile.data.ExternalChapters
import com.mangaverse.mobile.data.Manga
import com.mangaverse.mobile.data.PageModel
import com.mangaverse.mobile.data.SourceMissingException
import com.mangaverse.mobile.data.readingOrder
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mangaverse.mobile.ui.Accent
import com.mangaverse.mobile.ui.AccentLight
import com.mangaverse.mobile.ui.ErrorBox
import com.mangaverse.mobile.ui.LoadView
import com.mangaverse.mobile.ui.friendly
import com.mangaverse.mobile.ui.Muted
import com.mangaverse.mobile.ui.Pill
import com.mangaverse.mobile.ui.Surface1
import com.mangaverse.mobile.ui.Surface2
import com.mangaverse.mobile.ui.rememberLoad
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

enum class Mode(val label: String, val hint: String) {
    VERTICAL("Rolagem contínua", "Webtoon/manhwa: rola para baixo sem parar"),
    LTR("Esq. → Dir.", "Páginas para os lados, como HQ ocidental"),
    RTL("Dir. → Esq.", "Páginas para os lados, como mangá japonês"),
    PAGED_VERTICAL("Páginas na vertical", "Uma página por vez, passando para cima"),
}

enum class Background(val label: String, val color: Color) { BLACK("Preto", Color.Black), GRAY("Cinza", Color(0xFF26262E)), WHITE("Branco", Color.White) }

/** Preferências do leitor: modo por título (mangá ≠ webtoon) e o resto global. */
class ReaderPrefs(context: Context) {
    private val p = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun mode(mangaId: Long, default: Mode): Mode = p.getString("mode.$mangaId", null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: default
    fun setMode(mangaId: Long, m: Mode) = p.edit().putString("mode.$mangaId", m.name).apply()
    fun hasMode(mangaId: Long) = p.contains("mode.$mangaId")

    /** Modo para títulos ainda sem modo escolhido; null = automático (pelo gênero e pelo formato das páginas). */
    var defaultMode: Mode?
        get() = p.getString("defaultMode", null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
        set(v) = p.edit().apply { if (v == null) remove("defaultMode") else putString("defaultMode", v.name) }.apply()

    var background: Background
        get() = runCatching { Background.valueOf(p.getString("bg", "BLACK")!!) }.getOrDefault(Background.BLACK)
        set(v) = p.edit().putString("bg", v.name).apply()
    var gap: Boolean
        get() = p.getBoolean("gap", false)
        set(v) = p.edit().putBoolean("gap", v).apply()
    var volumeKeys: Boolean
        get() = p.getBoolean("volume", true)
        set(v) = p.edit().putBoolean("volume", v).apply()
    var tapZones: Boolean
        get() = p.getBoolean("taps", true)
        set(v) = p.edit().putBoolean("taps", v).apply()
    var keepOn: Boolean
        get() = p.getBoolean("keepOn", true)
        set(v) = p.edit().putBoolean("keepOn", v).apply()
    var pageNumber: Boolean
        get() = p.getBoolean("pageNumber", true)
        set(v) = p.edit().putBoolean("pageNumber", v).apply()
}

/** Capítulo pronto para leitura: páginas locais (arquivos baixados) ou online ([PageModel]). */
class ReaderChapter(val manga: Manga, val chapter: Chapter, val pages: List<Any>, val prev: Chapter?, val next: Chapter?, val local: Boolean) {
    val pageCount get() = pages.size
}

suspend fun loadReaderChapter(app: App, chapterId: Long): ReaderChapter {
    val chapter = app.db.getChapter(chapterId) ?: error("Capítulo não encontrado")
    val manga = app.db.getManga(chapter.mangaId) ?: error("Título não encontrado")
    val ordered = readingOrder(app.db.chapters(manga.id))
    val i = ordered.indexOfFirst { it.id == chapterId }
    val files = withContext(Dispatchers.IO) { app.downloads.pages(manga.id, chapter.id) }
    val pages: List<Any> = files ?: run {
        val source = app.extensions.source(manga.source) as? HttpSource ?: throw SourceMissingException(app.extensions.sourceName(manga.source))
        // MangaDex: capítulos oficiais (MANGA Plus etc.) não têm páginas e dão 403; explica em vez do erro cru
        val mangaDex = ExternalChapters.isMangaDex(source)
        if (mangaDex) ExternalChapters.cached(chapter)?.let { throw ExternalChapterException(it) }
        val list = try {
            withContext(Dispatchers.IO) { source.getPageList(chapter.toSChapter()) }
        } catch (e: Exception) {
            if (mangaDex) ExternalChapters.lookup(app, listOf(chapter))[chapter.id]?.let { throw ExternalChapterException(it) }
            throw e
        }
        if (list.isEmpty() && mangaDex) ExternalChapters.lookup(app, listOf(chapter))[chapter.id]?.let { throw ExternalChapterException(it) }
        list.mapIndexed { idx, p -> PageModel(source.id, "${chapter.url}#$idx", p) }
    }
    return ReaderChapter(manga, chapter, pages, ordered.getOrNull(i - 1), ordered.getOrNull(i + 1), files != null)
}

/** Modo inicial: webtoons/manhwas na vertical, HQs da esquerda para a direita, mangás da direita para a esquerda. */
private fun guessMode(m: Manga): Mode {
    val g = (m.genre.orEmpty() + " " + m.title).lowercase()
    return when {
        listOf("webtoon", "manhwa", "manhua", "long strip", "full color", "colorido").any { it in g } -> Mode.VERTICAL
        listOf("comic", "hq", "quadrinho", "super-her", "superher").any { it in g } -> Mode.LTR
        else -> Mode.RTL
    }
}

@Composable
fun ReaderScreen(chapterId: Long, onBack: () -> Unit, openChapter: (Long) -> Unit) {
    val app = LocalContext.current.app
    val load = rememberLoad(chapterId) { loadReaderChapter(app, chapterId) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LoadView(load) { chapter ->
            if (chapter.pages.isEmpty()) ErrorBox("Este capítulo não tem páginas.") { load.reload() }
            else Reader(chapter, onBack, openChapter)
        }
        if (load.data == null) {
            IconButton(onBack, Modifier.statusBarsPadding().padding(4.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Color.White) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Reader(chapter: ReaderChapter, onBack: () -> Unit, openChapter: (Long) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ReaderPrefs(ctx) }
    val mangaId = chapter.manga.id
    var mode by remember { mutableStateOf(prefs.mode(mangaId, prefs.defaultMode ?: guessMode(chapter.manga))) }
    // modo escolhido pelo usuário (neste título ou como padrão): a detecção automática de webtoon não mexe
    var explicitMode by remember { mutableStateOf(prefs.hasMode(mangaId) || prefs.defaultMode != null) }
    var defaultMode by remember { mutableStateOf(prefs.defaultMode) }
    var notice by remember { mutableStateOf<String?>(null) }
    var modeMenu by remember { mutableStateOf(false) }
    var chapterList by remember { mutableStateOf(false) }
    fun pickMode(m: Mode) { mode = m; explicitMode = true; prefs.setMode(mangaId, m) }
    var bg by remember { mutableStateOf(prefs.background) }
    var gap by remember { mutableStateOf(prefs.gap) }
    var volumeKeys by remember { mutableStateOf(prefs.volumeKeys) }
    var tapZones by remember { mutableStateOf(prefs.tapZones) }
    var keepOn by remember { mutableStateOf(prefs.keepOn) }
    var pageNumber by remember { mutableStateOf(prefs.pageNumber) }
    var ui by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    val total = chapter.pageCount
    val start = if (chapter.chapter.read) 0 else chapter.chapter.lastPage.coerceIn(0, total - 1)
    var page by remember { mutableIntStateOf(start) }
    var ended by remember { mutableStateOf(false) }
    val pages = chapter.pages
    val layouts = remember(pages) { androidx.compose.runtime.mutableStateMapOf<Int, PageLayout>() }
    val entries by remember(pages) {
        androidx.compose.runtime.derivedStateOf {
            pages.indices.flatMap { i ->
                when (val l = layouts[i]) {
                    is PageLayout.Tall -> (0 until l.slices).map { Entry(i, it, it == l.slices - 1) }
                    else -> listOf(Entry(i, -1, true))
                }
            }
        }
    }
    fun pageOfItem(idx: Int) = entries.getOrNull(idx)?.page ?: total
    fun itemOfPage(p: Int) = entries.indexOfFirst { it.page == p }.coerceAtLeast(0)
    // página em formato de tira longa: sem modo escolhido, passa para a rolagem contínua
    var tallPages by remember { mutableIntStateOf(0) }
    fun onTall() {
        tallPages++
        if (tallPages >= 2 && !explicitMode && mode != Mode.VERTICAL) {
            mode = Mode.VERTICAL
            notice = "Formato webtoon detectado: rolagem contínua. Troque no ícone de modo, em cima."
        }
    }
    LaunchedEffect(notice) { if (notice != null) { delay(4000); notice = null } }
    val nextId = chapter.next?.id
    val prevId = chapter.prev?.id

    // ---------- tela: barras do sistema, tela ligada ----------
    val window = (ctx as Activity).window
    val insets = remember { WindowInsetsControllerCompat(window, view) }
    LaunchedEffect(ui) {
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (ui) insets.show(WindowInsetsCompat.Type.systemBars()) else insets.hide(WindowInsetsCompat.Type.systemBars())
    }
    LaunchedEffect(Unit) { delay(2500); ui = false } // começa a ler sem a interface na frente
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        onDispose { insets.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // ---------- progresso ----------
    LaunchedEffect(Unit) {
        snapshotFlow { page to ended }.distinctUntilChanged().collectLatest { (p, end) ->
            delay(if (end) 0 else 800)
            try { app.db.saveProgress(chapter.chapter.id, if (end) total - 1 else p, total, end) } catch (_: Exception) {}
        }
    }

    // ---------- pré-carregamento: próximas páginas e início do próximo capítulo ----------
    val loader = ctx.imageLoader
    LaunchedEffect(page, pages) {
        for (i in page + 1..minOf(total - 1, page + 4)) loader.enqueue(ImageRequest.Builder(ctx).data(pages[i]).build())
    }
    var nextPrefetched by remember { mutableStateOf(false) }
    LaunchedEffect(page) {
        val next = nextId
        if (next != null && !nextPrefetched && page >= total - 3) {
            nextPrefetched = true
            try {
                loadReaderChapter(app, next).pages.take(4).forEach { loader.enqueue(ImageRequest.Builder(ctx).data(it).build()) }
            } catch (_: Exception) {}
        }
    }

    // ---------- navegação ----------
    val pager = rememberPagerState(initialPage = start) { total + 1 } // última "página" = fim do capítulo
    val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val zoom = remember { ZoomState() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val rtl = mode == Mode.RTL

    fun forward() = scope.launch {
        if (mode == Mode.VERTICAL) list.animateScrollBy(viewport.height * .8f)
        else if (pager.currentPage < total) pager.animateScrollToPage(pager.currentPage + 1)
        else nextId?.let(openChapter)
    }
    fun backward() = scope.launch {
        if (mode == Mode.VERTICAL) list.animateScrollBy(-viewport.height * .8f)
        else if (pager.currentPage > 0) pager.animateScrollToPage(pager.currentPage - 1)
        else prevId?.let(openChapter)
    }
    fun jump(p: Int) = scope.launch {
        zoom.reset()
        if (mode == Mode.VERTICAL) list.scrollToItem(itemOfPage(p)) else pager.scrollToPage(p)
    }

    // página atual a partir do paginador/lista
    LaunchedEffect(mode) {
        if (mode == Mode.VERTICAL) {
            snapshotFlow {
                val info = list.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.firstOrNull { it.offset <= center && it.offset + it.size > center }?.index ?: list.firstVisibleItemIndex
            }.distinctUntilChanged().collect { idx ->
                val i = pageOfItem(idx)
                if (i >= total) ended = true else page = i
            }
        } else {
            snapshotFlow { pager.currentPage }.collect { i ->
                zoom.reset()
                if (i >= total) ended = true else { page = i; ended = false }
            }
        }
    }
    // ao trocar de modo, mantém a página
    LaunchedEffect(mode) { zoom.reset(); zoom.horizontalOnly = mode == Mode.VERTICAL; if (mode == Mode.VERTICAL) list.scrollToItem(itemOfPage(page)) else pager.scrollToPage(page) }

    // teclas de volume (descer = avançar)
    val activity = ctx as? MainActivity
    DisposableEffect(volumeKeys) {
        activity?.volumePaging = volumeKeys
        onDispose { activity?.volumePaging = false }
    }
    LaunchedEffect(Unit) {
        activity?.volumeKeys?.collect { if (it == KeyEvent.KEYCODE_VOLUME_DOWN) forward() else backward() }
    }

    fun onTap(at: Offset, size: IntSize) {
        if (!tapZones || size.width == 0) { ui = !ui; return }
        if (mode == Mode.VERTICAL || mode == Mode.PAGED_VERTICAL) {
            val y = at.y / size.height
            when {
                y < .3f -> backward()
                y > .7f -> forward()
                else -> ui = !ui
            }
        } else {
            val x = at.x / size.width
            when {
                x < .3f -> if (rtl) forward() else backward()
                x > .7f -> if (rtl) backward() else forward()
                else -> ui = !ui
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(bg.color).onSizeChanged { viewport = it; zoom.size = it }
            .zoomGestures(zoom) { dy -> list.dispatchRawDelta(-dy) }
            .readerTaps(zoom, scope, ::onTap),
    ) {
        if (mode == Mode.VERTICAL) {
            VerticalReader(list, pages, entries, layouts, chapter, zoom, gap, bg, openChapter, ::onTall)
        } else {
            PagedReader(pager, pages, chapter, zoom, rtl, mode == Mode.PAGED_VERTICAL, bg, openChapter, ::onTall)
        }

        notice?.let { msg ->
            Text(
                msg, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = .8f)).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        // número da página discreto com a interface escondida
        if (pageNumber && !ui) {
            Text(
                if (ended) "Fim" else "${page + 1} / $total", fontSize = 11.sp, color = Color.White.copy(alpha = .85f),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }

        // barra superior
        AnimatedVisibility(ui, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .9f), Color.Transparent)))
                    .statusBarsPadding().padding(start = 4.dp, end = 4.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(chapter.manga.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        chapter.chapter.name + if (chapter.local) " · baixado" else "",
                        color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    androidx.compose.material3.TextButton({ modeMenu = true }) {
                        Icon(modeIcon(mode), null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Text(" " + mode.label, color = Color.White, fontSize = 12.sp, maxLines = 1)
                    }
                    androidx.compose.material3.DropdownMenu(modeMenu, { modeMenu = false }) {
                        Mode.entries.forEach { m ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(m.label, fontWeight = if (m == mode) FontWeight.Bold else FontWeight.Normal, color = if (m == mode) AccentLight else Color.Unspecified)
                                        Text(m.hint, fontSize = 11.sp, color = Muted)
                                    }
                                },
                                leadingIcon = { Icon(modeIcon(m), null) },
                                onClick = { modeMenu = false; pickMode(m) },
                            )
                        }
                    }
                }
                IconButton({ settings = true }) { Icon(Icons.Default.Tune, "Ajustes do leitor", tint = Color.White) }
            }
        }

        // barra inferior
        AnimatedVisibility(ui, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            var drag by remember { mutableFloatStateOf(-1f) }
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .9f))))
                    .navigationBarsPadding().padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
            ) {
                Text(
                    if (drag >= 0) "Página ${drag.toInt() + 1} de $total" else if (ended) "Fim do capítulo" else "Página ${page + 1} de $total",
                    color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ prevId?.let(openChapter) }, enabled = prevId != null) {
                        Icon(Icons.Default.SkipPrevious, "Capítulo anterior", tint = if (prevId != null) Color.White else Color.Gray)
                    }
                    // em RTL o controle também vai da direita para a esquerda
                    val shown = if (drag >= 0) drag else page.toFloat()
                    Slider(
                        value = if (rtl) (total - 1 - shown) else shown,
                        onValueChange = { v -> drag = if (rtl) (total - 1 - v) else v },
                        onValueChangeFinished = { if (drag >= 0) { jump(drag.toInt().coerceIn(0, total - 1)); drag = -1f } },
                        valueRange = 0f..(total - 1).coerceAtLeast(1).toFloat(),
                        steps = (total - 2).coerceIn(0, 200),
                        colors = SliderDefaults.colors(thumbColor = AccentLight, activeTrackColor = Accent, inactiveTrackColor = Color.White.copy(alpha = .25f), activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton({ nextId?.let(openChapter) }, enabled = nextId != null) {
                        Icon(Icons.Default.SkipNext, "Próximo capítulo", tint = if (nextId != null) Color.White else Color.Gray)
                    }
                    IconButton({ chapterList = true }) { Icon(Icons.AutoMirrored.Filled.List, "Todos os capítulos", tint = Color.White) }
                }
            }
        }
    }

    if (chapterList) ChapterListSheet(chapter, onClose = { chapterList = false }) { id -> chapterList = false; if (id != chapter.chapter.id) openChapter(id) }

    if (settings) {
        ModalBottomSheet({ settings = false }, containerColor = Surface1) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Option("Modo de leitura (vale para este título)") {
                    Mode.entries.forEach { m -> Pill(m.label, mode == m) { pickMode(m) } }
                }
                Option("Modo para títulos novos") {
                    Pill("Automático", defaultMode == null) { defaultMode = null; prefs.defaultMode = null }
                    Mode.entries.forEach { m -> Pill(m.label, defaultMode == m) { defaultMode = m; prefs.defaultMode = m } }
                }
                Text("Automático: webtoon/manhwa em rolagem contínua, HQ da esquerda para a direita e mangá da direita para a esquerda. Tiras longas são detectadas sozinhas.", color = Muted, fontSize = 12.sp)
                Option("Fundo") {
                    Background.entries.forEach { b -> Pill(b.label, bg == b) { bg = b; prefs.background = b } }
                }
                Toggle("Espaço entre páginas (vertical)", gap) { gap = it; prefs.gap = it }
                Toggle("Tocar nas bordas para virar a página", tapZones) { tapZones = it; prefs.tapZones = it }
                Toggle("Virar página com as teclas de volume", volumeKeys) { volumeKeys = it; prefs.volumeKeys = it }
                Toggle("Manter a tela ligada", keepOn) { keepOn = it; prefs.keepOn = it }
                Toggle("Mostrar número da página", pageNumber) { pageNumber = it; prefs.pageNumber = it }
                Text(
                    "Gestos: pinça ou toque duplo para zoom · toque no centro mostra/esconde os controles · " +
                        when (mode) {
                            Mode.VERTICAL -> "toque em cima/embaixo rola a tela"
                            Mode.PAGED_VERTICAL -> "toque em cima/embaixo troca de página"
                            else -> "toque nas laterais vira a página"
                        },
                    color = Muted, fontSize = 12.sp,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Option(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(value, onChange)
    }
}

/** Item da rolagem contínua: uma página inteira ([slice] = -1) ou uma fatia de uma tira longa. */
data class Entry(val page: Int, val slice: Int, val lastOfPage: Boolean)

@Composable
private fun VerticalReader(
    list: LazyListState, pages: List<Any>, entries: List<Entry>, layouts: MutableMap<Int, PageLayout>, chapter: ReaderChapter,
    zoom: ZoomState, gap: Boolean, bg: Background, openChapter: (Long) -> Unit, onTall: () -> Unit,
) {
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val ctx = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { minOf(maxWidth, 900.dp).roundToPx() }
        // sem espaçamento da lista: as fatias de uma tira têm de ficar coladas; o espaço vai depois de cada página
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().zoomLayer(zoom), horizontalAlignment = Alignment.CenterHorizontally) {
            itemsIndexed(entries, key = { _, e -> "${e.page}:${e.slice}" }) { _, e ->
                val pad = if (gap && e.lastOfPage) Modifier.padding(bottom = 8.dp) else Modifier
                val model = pages[e.page]
                val layout = layouts[e.page]
                when {
                    layout is PageLayout.Tall && e.slice >= 0 -> TallSlice(layout, e.slice, widthPx, Modifier.widthIn(max = 900.dp).fillMaxWidth().then(pad))
                    layout == null -> {
                        // descobre o formato da página antes de desenhar (tiras longas precisam de fatias)
                        LaunchedEffect(model) {
                            val l = runCatching { probePage(ctx, model) }.getOrDefault(PageLayout.Normal)
                            layouts[e.page] = l
                            if (l is PageLayout.Tall) onTall()
                        }
                        PagePlaceholder(e.page, screenH * .7f, bg)
                    }
                    else -> PageImage(model, e.page, Modifier.widthIn(max = 900.dp).fillMaxWidth().then(pad), ContentScale.FillWidth, Size(Dimension(widthPx), Dimension.Undefined), screenH * .7f, bg)
                }
            }
            item(key = "end") { EndPanel(chapter, bg, openChapter, Modifier.fillMaxWidth().heightIn(min = screenH * .6f)) }
        }
    }
}

/** Uma fatia de uma tira longa, decodificada só quando aparece na tela. */
@Composable
private fun TallSlice(layout: PageLayout.Tall, k: Int, widthPx: Int, modifier: Modifier) {
    val r = layout.sliceRect(k)
    var bitmap by remember(layout, k) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(layout, k, widthPx) {
        bitmap = withContext(Dispatchers.IO) { layout.decode(k, widthPx)?.asImageBitmap() }
    }
    Box(modifier.aspectRatio(r.width().toFloat() / r.height().coerceAtLeast(1))) {
        bitmap?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}

@Composable
private fun PagePlaceholder(index: Int, height: androidx.compose.ui.unit.Dp, bg: Background) {
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(28.dp), color = AccentLight, strokeWidth = 2.dp)
            Text("${index + 1}", color = if (bg == Background.WHITE) Color.DarkGray else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun PagedReader(
    pager: PagerState, pages: List<Any>, chapter: ReaderChapter, zoom: ZoomState, rtl: Boolean, vertical: Boolean, bg: Background,
    openChapter: (Long) -> Unit, onTall: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(LocalDensity.current) { maxWidth.roundToPx() }
        val h = with(LocalDensity.current) { maxHeight.roundToPx() }
        val content: @Composable androidx.compose.foundation.pager.PagerScope.(Int) -> Unit = { i ->
            if (i >= pages.size) {
                EndPanel(chapter, bg, openChapter, Modifier.fillMaxSize())
            } else {
                // só a página atual recebe o zoom
                val current = pager.currentPage == i
                Box(Modifier.fillMaxSize().then(if (current) Modifier.zoomLayer(zoom) else Modifier), contentAlignment = Alignment.Center) {
                    // decodifica com o dobro da tela para o zoom continuar nítido
                    PageImage(pages[i], i, Modifier.fillMaxSize(), ContentScale.Fit, Size(w * 2, h * 2), null, bg) { ratio -> if (ratio >= TALL_RATIO) onTall() }
                }
            }
        }
        if (vertical) androidx.compose.foundation.pager.VerticalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoom.zoomed, beyondViewportPageCount = 1, key = { it }, pageContent = content)
        else HorizontalPager(pager, Modifier.fillMaxSize(), reverseLayout = rtl, userScrollEnabled = !zoom.zoomed, beyondViewportPageCount = 1, key = { it }, pageContent = content)
    }
}

@Composable
private fun PageImage(
    model: Any, index: Int, modifier: Modifier, scale: ContentScale, size: Size, placeholderHeight: androidx.compose.ui.unit.Dp?, bg: Background,
    onRatio: ((Float) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    var attempt by remember(model) { mutableIntStateOf(0) }
    val request = remember(model, attempt) {
        ImageRequest.Builder(ctx).data(model).size(size).precision(Precision.INEXACT)
            .apply { if (attempt > 0) memoryCacheKey("$index#${model.hashCode()}#$attempt") }
            .build()
    }
    val fg = if (bg == Background.WHITE) Color.DarkGray else Muted
    SubcomposeAsyncImage(
        request, "Página ${index + 1}", modifier, contentScale = scale,
        onSuccess = { st ->
            val sz = st.painter.intrinsicSize
            if (onRatio != null && sz.width > 0f && sz.height > 0f) onRatio(sz.height / sz.width)
        },
        loading = {
            Box(Modifier.fillMaxWidth().then(if (placeholderHeight != null) Modifier.height(placeholderHeight) else Modifier.fillMaxSize()), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = AccentLight, strokeWidth = 2.dp)
                    Text("${index + 1}", color = fg, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        error = {
            Box(Modifier.fillMaxWidth().then(if (placeholderHeight != null) Modifier.height(placeholderHeight) else Modifier.fillMaxSize()), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Falha ao carregar a página ${index + 1}", color = fg, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Button({
                        // a url da imagem pode ter expirado: resolve de novo
                        if (model is PageModel && model.page.url.isNotBlank()) model.page.imageUrl = null
                        attempt++
                    }, colors = ButtonDefaults.buttonColors(containerColor = Surface2)) { Text("Tentar de novo") }
                }
            }
        },
    )
}

@Composable
private fun EndPanel(chapter: ReaderChapter, bg: Background, openChapter: (Long) -> Unit, modifier: Modifier) {
    val fg = if (bg == Background.WHITE) Color.Black else Color.White
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Fim de ${chapter.chapter.name}", color = fg, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
            Text("Marcado como lido.", color = Muted, fontSize = 13.sp)
            val next = chapter.next
            if (next != null) {
                Button({ openChapter(next.id) }, modifier = Modifier.height(46.dp)) { Text("Próximo: ${next.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            } else {
                Text("Este é o último capítulo disponível.", color = Muted, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun modeIcon(m: Mode) = when (m) {
    Mode.VERTICAL -> Icons.Default.ViewDay
    Mode.LTR -> Icons.AutoMirrored.Filled.ArrowForward
    Mode.RTL -> Icons.AutoMirrored.Filled.ArrowBack
    Mode.PAGED_VERTICAL -> Icons.Default.SwapVert
}

/** Lista de todos os capítulos da obra, aberta de dentro do leitor, para pular para qualquer um. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterListSheet(current: ReaderChapter, onClose: () -> Unit, onPick: (Long) -> Unit) {
    val app = LocalContext.current.app
    var newestFirst by remember { mutableStateOf(true) }
    var all by remember { mutableStateOf<List<Chapter>?>(null) }
    LaunchedEffect(Unit) { all = readingOrder(app.db.chapters(current.manga.id)) }
    ModalBottomSheet(onClose, containerColor = Surface1) {
        val list = all
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Capítulos", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (list != null) Text("${list.count { it.read }} de ${list.size} lidos · ${current.manga.title}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                androidx.compose.material3.TextButton({ newestFirst = !newestFirst }) { Text(if (newestFirst) "Mais novos primeiro" else "Mais antigos primeiro", fontSize = 12.sp) }
            }
            if (list == null) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentLight) }
                return@Column
            }
            val shown = if (newestFirst) list.asReversed() else list
            val currentIndex = shown.indexOfFirst { it.id == current.chapter.id }.coerceAtLeast(0)
            val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), state = state) {
                itemsIndexed(shown, key = { _, c -> c.id }) { _, c ->
                    val isCurrent = c.id == current.chapter.id
                    Row(
                        Modifier.fillMaxWidth().background(if (isCurrent) Accent.copy(alpha = .22f) else Color.Transparent)
                            .clickable { onPick(c.id) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = when { isCurrent -> AccentLight; c.read -> Muted; else -> Color.White },
                            )
                            val info = when {
                                isCurrent -> "Lendo agora"
                                c.read -> "Lido"
                                c.lastPage > 0 && c.pageCount > 0 -> "Página ${c.lastPage + 1} de ${c.pageCount}"
                                else -> null
                            }
                            if (info != null) Text(info, color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
