package com.mangaverse.mobile.ui

import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.OpenInNew
import com.mangaverse.mobile.data.ExternalChapters
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mangaverse.mobile.app
import com.mangaverse.mobile.data.Chapter
import com.mangaverse.mobile.data.CoverModel
import com.mangaverse.mobile.data.DownloadTask
import com.mangaverse.mobile.data.Manga
import com.mangaverse.mobile.data.STATUS_LABEL
import com.mangaverse.mobile.data.readingOrder
import com.mangaverse.mobile.data.refreshManga
import com.mangaverse.mobile.ext.ExtensionManager
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun MangaScreen(mangaId: Long) {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val manga = rememberQuery(mangaId) { app.db.getManga(mangaId) }
    val chapters = rememberQuery(mangaId) { app.db.chapters(mangaId) }
    val installed by app.extensions.installed.collectAsState()
    val source = remember(manga?.source, installed) { manga?.let { app.extensions.source(it.source) } }
    val queue by app.downloads.queue.collectAsState()
    val downloaded by app.downloads.downloaded.collectAsState()

    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var menu by remember { mutableStateOf(false) }
    // MangaDex: capítulos que só existem no site oficial (MANGA Plus…) e não abrem no app
    var externals by remember { mutableStateOf(emptyMap<Long, String>()) }
    var externalDialog by remember { mutableStateOf<Chapter?>(null) }
    val ctx = LocalContext.current
    LaunchedEffect(chapters, source) {
        val chs = chapters
        if (chs.isNullOrEmpty() || !ExternalChapters.isMangaDex(source)) return@LaunchedEffect
        externals = ExternalChapters.lookup(app, chs)
    }

    fun refresh(details: Boolean) {
        val m = manga ?: return
        if (refreshing) return
        refreshing = true
        error = null
        scope.launch {
            try {
                val added = refreshManga(app, m, details)
                if (added > 0 && chapters?.isNotEmpty() == true) snackbar.showSnackbar("$added capítulo(s) novo(s)")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.friendly()
            } finally {
                refreshing = false
            }
        }
    }

    // primeira abertura: busca detalhes e capítulos na fonte
    var firstLoad by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(manga != null, chapters != null, source != null) {
        val m = manga ?: return@LaunchedEffect
        if (chapters == null || source == null || !firstLoad) return@LaunchedEffect
        firstLoad = false
        if (!m.initialized || chapters.isEmpty()) refresh(details = true)
    }

    val m = manga
    if (m == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentLight) }
        return
    }
    val list = chapters.orEmpty()
    val ordered = remember(list) { readingOrder(list) }
    val shown = if (newestFirst) ordered.asReversed() else ordered
    val queued = remember(queue) { queue.associateBy { it.chapterId } }
    val selecting = selected.isNotEmpty()

    fun download(chs: List<Chapter>) {
        if (chs.isEmpty()) return
        val skipped = chs.count { it.id in externals }
        val chs = chs.filter { it.id !in externals }
        if (skipped > 0) scope.launch { snackbar.showSnackbar("$skipped capítulo(s) oficial(is) do site externo ficaram de fora (não dá para baixar).") }
        if (chs.isEmpty()) return
        if (source == null) {
            scope.launch { snackbar.showSnackbar("Instale a extensão da fonte para baixar.") }
            return
        }
        app.downloads.enqueue(m, chs)
        scope.launch { snackbar.showSnackbar("${chs.size} capítulo(s) na fila de download") }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                val sel = list.filter { it.id in selected }
                TopAppBar(
                    navigationIcon = { IconButton({ selected = emptySet() }) { Icon(Icons.Default.Close, "Cancelar seleção") } },
                    title = { Text("${selected.size} selecionado(s)") },
                    actions = {
                        IconButton({ selected = list.map { it.id }.toSet() }) { Icon(Icons.Default.SelectAll, "Selecionar todos") }
                        IconButton({ scope.launch { app.db.markRead(selected, true) }; selected = emptySet() }) { Icon(Icons.Default.DoneAll, "Marcar como lido") }
                        IconButton({ scope.launch { app.db.markRead(selected, false) }; selected = emptySet() }) { Icon(Icons.Default.RemoveDone, "Marcar como não lido") }
                        IconButton({ download(readingOrder(sel)); selected = emptySet() }) { Icon(Icons.Default.Download, "Baixar") }
                        IconButton({ app.downloads.delete(m.id, selected); selected = emptySet() }) { Icon(Icons.Outlined.DeleteOutline, "Apagar downloads") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface2),
                )
            } else {
                TopAppBar(
                    navigationIcon = { IconButton({ nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                    title = { Text(m.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp) },
                    actions = {
                        if (source is HttpSource) IconButton({
                            val url = runCatching { source.getMangaUrl(m.toSManga()) }.getOrDefault(source.baseUrl)
                            nav.webView(url, source.id)
                        }) { Icon(Icons.Default.Public, "Abrir no site") }
                        IconButton({ refresh(details = true) }, enabled = !refreshing && source != null) { Icon(Icons.Default.Refresh, "Atualizar") }
                        DownloadsAction(queue.size)
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Mais opções") }
                            DropdownMenu(menu, { menu = false }) {
                                val unread = ordered.filter { !it.read && it.id !in downloaded }
                                DropdownMenuItem({ Text("Baixar os próximos 5 não lidos") }, { menu = false; download(unread.take(5)) })
                                DropdownMenuItem({ Text("Baixar todos os não lidos") }, { menu = false; download(unread) })
                                DropdownMenuItem({ Text("Baixar todos") }, { menu = false; download(ordered.filter { it.id !in downloaded }) })
                                DropdownMenuItem({ Text("Marcar todos como lidos") }, { menu = false; scope.launch { app.db.markRead(list.map { it.id }, true) } })
                                DropdownMenuItem({ Text("Marcar todos como não lidos") }, { menu = false; scope.launch { app.db.markRead(list.map { it.id }, false) } })
                                DropdownMenuItem({ Text("Apagar todos os downloads") }, { menu = false; app.downloads.delete(m.id, list.map { it.id }) })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas),
                )
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") { Header(m, source?.name ?: app.extensions.sourceName(m.source), source?.lang) }
            item(key = "actions") {
                Actions(
                    m, ordered,
                    onFavorite = { scope.launch { app.db.setFavorite(m.id, !m.favorite) } },
                    onRead = { c -> if (c.id in externals) externalDialog = c else nav.read(c.id) },
                )
            }
            if (externals.isNotEmpty()) item(key = "externals") {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF59E0B).copy(alpha = .12f)).padding(12.dp),
                ) {
                    Text(
                        "${externals.size} capítulo(s) são oficiais do ${ExternalChapters.siteName(externals.values.first())}. " +
                            "A MangaDex só guarda o link, sem as páginas, então eles não abrem aqui (erro 403).",
                        fontSize = 13.sp, color = Color(0xFFFCD34D),
                    )
                    TextButton({ nav.search(m.title) }) { Text("Procurar \"${m.title}\" em outras fontes") }
                }
            }
            if (source == null) item(key = "missing") { MissingSource(m.source) }
            item(key = "desc") { Description(m) }
            if (refreshing) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = AccentLight) }
            error?.let { e ->
                item(key = "error") {
                    Column(Modifier.padding(16.dp)) {
                        Text(e, color = Color(0xFFF87171), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ refresh(details = !m.initialized) }) { Text("Tentar de novo") }
                            if (source is HttpSource) OutlinedButton({ nav.webView(source.baseUrl, source.id) }) { Text("Abrir o site") }
                        }
                    }
                }
            }
            item(key = "chapters-head") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${list.size} capítulo(s)", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IconButton({ newestFirst = !newestFirst }) { Icon(Icons.Default.SwapVert, if (newestFirst) "Mais novos primeiro" else "Mais antigos primeiro") }
                }
            }
            items(shown, key = { it.id }) { c ->
                ChapterRow(
                    c, selected = c.id in selected, downloaded = c.id in downloaded, task = queued[c.id],
                    external = externals[c.id]?.let(ExternalChapters::siteName),
                    onClick = { if (selecting) selected = selected.toggle(c.id) else if (c.id in externals) externalDialog = c else nav.read(c.id) },
                    onLongClick = { selected = selected.toggle(c.id) },
                    onDownload = { download(listOf(c)) },
                    onCancel = { app.downloads.cancel(c.id) },
                    onRetry = { app.downloads.retry(c.id) },
                    onDelete = { app.downloads.delete(m.id, listOf(c.id)) },
                )
            }
        }
    }

    externalDialog?.let { c ->
        val url = externals[c.id].orEmpty()
        ExternalDialog(
            c, url, m.title,
            onSearch = { externalDialog = null; nav.search(m.title) },
            onOpen = {
                externalDialog = null
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            },
            onDismiss = { externalDialog = null },
        )
    }
}

@Composable
private fun ExternalDialog(c: Chapter, url: String, title: String, onSearch: () -> Unit, onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.name) },
        text = {
            Text(
                "Este capítulo é oficial do ${ExternalChapters.siteName(url)}. A MangaDex não tem as páginas, só o link, " +
                    "e o site bloqueia a leitura por apps (erro 403).\n\nProcure \"$title\" em outra fonte (ex.: Mugiwaras Oficial para One Piece) ou leia no site oficial.",
            )
        },
        confirmButton = { TextButton({ onSearch() }) { Text("Outras fontes") } },
        dismissButton = { TextButton({ onOpen() }) { Text("Abrir o site") } },
    )
}

private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

@Composable
private fun Header(m: Manga, sourceName: String, lang: String?) {
    Box(Modifier.fillMaxWidth()) {
        if (!m.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(CoverModel(m.source, m.thumbnailUrl), null, Modifier.matchParentSize().blur(24.dp).alpha(.35f), contentScale = ContentScale.Crop)
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Canvas))))
        Row(Modifier.padding(16.dp)) {
            Cover(m.source, m.thumbnailUrl, Modifier.width(112.dp))
            Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(m.title, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                val people = listOfNotNull(m.author, m.artist?.takeIf { it != m.author }).filter { it.isNotBlank() }
                if (people.isNotEmpty()) Text(people.joinToString(" · "), color = Muted, fontSize = 13.sp, maxLines = 2)
                STATUS_LABEL[m.status]?.let { Text(it, color = Muted, fontSize = 13.sp) }
                Text(sourceName + (lang?.let { " · ${ExtensionManager.langName(it)}" } ?: ""), color = AccentLight, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Actions(m: Manga, ordered: List<Chapter>, onFavorite: () -> Unit, onRead: (Chapter) -> Unit) {
    // continua no capítulo lido mais recentemente se não terminou; senão, o primeiro não lido depois dele
    val resume = remember(ordered) {
        val last = ordered.filter { it.lastRead > 0 }.maxByOrNull { it.lastRead }
        when {
            last != null && !last.read -> last
            last != null -> ordered.drop(ordered.indexOf(last) + 1).firstOrNull { !it.read } ?: ordered.firstOrNull { !it.read }
            else -> ordered.firstOrNull { !it.read }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onFavorite, Modifier.weight(1f)) {
            Icon(if (m.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, Modifier.size(18.dp), tint = if (m.favorite) Pink else Color.White)
            Spacer(Modifier.width(6.dp))
            Text(if (m.favorite) "Na biblioteca" else "Adicionar", maxLines = 1)
        }
        if (resume != null) {
            val started = ordered.any { it.lastRead > 0 || it.read }
            Button({ onRead(resume) }, Modifier.weight(1.3f)) {
                Text(if (started) "Continuar · ${resume.shortName()}" else "Começar a ler", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else if (ordered.isNotEmpty()) {
            Button({ onRead(ordered.first()) }, Modifier.weight(1.3f)) { Text("Ler de novo", maxLines = 1) }
        }
    }
}

private fun Chapter.shortName(): String = if (number >= 0) "Cap. ${if (number % 1f == 0f) number.toInt().toString() else number.toString()}" else name

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Description(m: Manga) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp).animateContentSize()) {
        m.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it.trim(), color = Color(0xFFD4D4D8), fontSize = 14.sp, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded },
            )
            Spacer(Modifier.height(8.dp))
        }
        if (m.genres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                m.genres.take(if (expanded) 50 else 8).forEach {
                    Text(it, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Surface2).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun MissingSource(sourceId: Long) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val ext = remember { app.extensions.extensionForSource(sourceId) }
    var installing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).padding(14.dp)) {
        Text("A extensão desta fonte não está instalada", fontWeight = FontWeight.SemiBold)
        Text("Os capítulos baixados continuam legíveis. Para atualizar ou ler online, instale a extensão.", color = Muted, fontSize = 13.sp)
        error?.let { Text(it, color = Color(0xFFF87171), fontSize = 12.sp) }
        if (ext != null) {
            Spacer(Modifier.height(8.dp))
            Button({
                installing = true
                error = null
                scope.launch {
                    try { app.extensions.install(ext) } catch (e: Throwable) { error = e.friendly() }
                    installing = false
                }
            }, enabled = !installing) { Text(if (installing) "Instalando…" else "Instalar ${ext.name}") }
        }
    }
}

@Composable
private fun ChapterRow(
    c: Chapter, selected: Boolean, downloaded: Boolean, task: DownloadTask?, external: String? = null,
    onClick: () -> Unit, onLongClick: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit,
) {
    val date = remember(c.dateUpload) { if (c.dateUpload > 0) DateFormat.getDateInstance(DateFormat.SHORT).format(Date(c.dateUpload)) else null }
    Row(
        Modifier.fillMaxWidth().background(if (selected) Accent.copy(alpha = .22f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(if (c.read) .45f else 1f)) {
            Text(c.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val info = listOfNotNull(
                external?.let { "Só no $it" }, date, c.scanlator?.takeIf { it.isNotBlank() },
                if (!c.read && c.lastPage > 0 && c.pageCount > 0) "Página ${c.lastPage + 1}/${c.pageCount}" else null,
            )
            if (info.isNotEmpty()) Text(info.joinToString(" · "), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            external != null -> Icon(Icons.Default.OpenInNew, "Só no site oficial", tint = Color(0xFFF59E0B), modifier = Modifier.padding(12.dp).size(20.dp))
            downloaded -> IconButton(onDelete) { Icon(Icons.Default.CheckCircle, "Baixado (toque para apagar)", tint = AccentLight) }
            task?.state == DownloadTask.State.ERROR -> IconButton(onRetry) { Icon(Icons.Default.ErrorOutline, "Falhou: ${task.error} (toque para tentar de novo)", tint = Color(0xFFF87171)) }
            task != null -> IconButton(onCancel) {
                if (task.state == DownloadTask.State.RUNNING && task.total > 0) {
                    CircularProgressIndicator({ task.done.toFloat() / task.total }, Modifier.size(22.dp), color = AccentLight, strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Muted, strokeWidth = 2.dp)
                }
            }
            else -> IconButton(onDownload) { Icon(Icons.Outlined.DownloadForOffline, "Baixar", tint = Muted) }
        }
    }
}
