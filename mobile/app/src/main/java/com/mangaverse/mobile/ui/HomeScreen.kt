package com.mangaverse.mobile.ui

import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Add
import com.mangaverse.mobile.data.readingOrder
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangaverse.mobile.app
import com.mangaverse.mobile.data.HistoryItem
import com.mangaverse.mobile.data.LibraryManga
import com.mangaverse.mobile.data.updateLibrary
import com.mangaverse.mobile.ext.ExtensionManager
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class Tab(val label: String) { LIBRARY("Biblioteca"), HISTORY("Histórico"), BROWSE("Explorar"), EXTENSIONS("Extensões") }

@Composable
fun HomeScreen() {
    val app = LocalContext.current.app
    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    val installed by app.extensions.installed.collectAsState()
    val available by app.extensions.available.collectAsState()
    val updates = remember(installed, available) { app.extensions.updatesCount() }
    val queue by app.downloads.queue.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Surface1) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t }, label = { Text(t.label, fontSize = 11.sp) },
                        icon = {
                            BadgedBox(badge = { if (t == Tab.EXTENSIONS && updates > 0) Badge { Text("$updates") } }) {
                                Icon(
                                    when (t) {
                                        Tab.LIBRARY -> Icons.Default.LocalLibrary
                                        Tab.HISTORY -> Icons.Default.History
                                        Tab.BROWSE -> Icons.Default.Explore
                                        Tab.EXTENSIONS -> Icons.Default.Extension
                                    },
                                    null,
                                )
                            }
                        },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(bottom = pad.calculateBottomPadding())) {
            when (tab) {
                Tab.LIBRARY -> LibraryTab(queue.size)
                Tab.HISTORY -> HistoryTab()
                Tab.BROWSE -> BrowseTab(goToExtensions = { tab = Tab.EXTENSIONS })
                Tab.EXTENSIONS -> ExtensionsTab()
            }
        }
    }
}

@Composable
fun DownloadsAction(count: Int) {
    val nav = LocalNav.current
    IconButton({ nav.downloads() }) {
        BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) { Icon(Icons.Default.Download, "Downloads") }
    }
}

private enum class LibrarySort(val label: String) { TITLE("A–Z"), LAST_READ("Lidos recentemente"), UNREAD("Não lidos"), ADDED("Adicionados") }

@Composable
private fun LibraryTab(queueSize: Int) {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val items = rememberQuery { app.db.library() }
    var filter by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.LAST_READ) }
    var updating by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    fun update() {
        if (updating) return
        updating = true
        progress = 0f
        scope.launch {
            val r = updateLibrary(app) { done, total -> progress = done.toFloat() / total.coerceAtLeast(1) }
            updating = false
            val msg = buildString {
                append(if (r.newChapters == 0) "Nenhum capítulo novo" else "${r.newChapters} capítulo(s) novo(s) em ${r.updated} título(s)")
                if (r.failures.isNotEmpty()) append(" · ${r.failures.size} falha(s): ${r.failures.first()}")
            }
            snackbar.showSnackbar(msg)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            filter, { filter = it }, singleLine = true, placeholder = { Text("Filtrar biblioteca") },
                            modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                        )
                    } else {
                        Text("Biblioteca", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton({ searching = !searching; if (!searching) filter = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Filtrar")
                    }
                    IconButton(::update, enabled = !updating) { Icon(Icons.Default.Refresh, "Procurar capítulos novos") }
                    DownloadsAction(queueSize)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (updating) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth(), color = AccentLight)
            val list = items
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    "Sua biblioteca está vazia",
                    "Instale extensões na aba Extensões, procure títulos no Explorar e toque em “Adicionar à biblioteca”.",
                )
                else -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LibrarySort.entries.forEach { s -> Pill(s.label, sort == s) { sort = s } }
                    }
                    val shown = remember(list, filter, sort) { sortLibrary(list, filter, sort) }
                    PullToRefreshBox(updating, ::update, Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(shown, key = { it.manga.id }) { item ->
                                TitleCard(
                                    item.manga.title, item.manga.source, item.manga.thumbnailUrl,
                                    subtitle = if (item.total == 0) "Sem capítulos" else "${item.read}/${item.total} lidos",
                                    progress = if (item.total > 0) item.read.toFloat() / item.total else null,
                                    badge = item.unread.takeIf { it > 0 }?.toString(),
                                ) { nav.manga(item.manga.id) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sortLibrary(list: List<LibraryManga>, filter: String, sort: LibrarySort): List<LibraryManga> {
    val f = filter.trim()
    val filtered = if (f.isEmpty()) list else list.filter { it.manga.title.contains(f, true) || it.manga.author?.contains(f, true) == true }
    return when (sort) {
        LibrarySort.TITLE -> filtered.sortedBy { it.manga.title.lowercase() }
        LibrarySort.LAST_READ -> filtered.sortedWith(compareByDescending<LibraryManga> { it.lastRead }.thenByDescending { it.manga.dateAdded })
        LibrarySort.UNREAD -> filtered.sortedWith(compareByDescending<LibraryManga> { it.unread }.thenBy { it.manga.title.lowercase() })
        LibrarySort.ADDED -> filtered.sortedByDescending { it.manga.dateAdded }
    }
}

@Composable
private fun HistoryTab() {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val items = rememberQuery { app.db.history() }
    // continuar: o capítulo pela metade, ou o seguinte se o último lido já terminou
    fun resume(h: HistoryItem) = scope.launch {
        if (!h.chapter.read) { nav.read(h.chapter.id); return@launch }
        val ordered = readingOrder(app.db.chapters(h.manga.id))
        val i = ordered.indexOfFirst { it.id == h.chapter.id }
        val next = ordered.drop(i + 1).firstOrNull { !it.read } ?: ordered.getOrNull(i + 1)
        if (next != null) nav.read(next.id) else nav.manga(h.manga.id)
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Histórico", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas))
        val list = items ?: return@Column
        if (list.isEmpty()) {
            EmptyState("Nada lido ainda", "Os capítulos que você ler aparecem aqui para continuar de onde parou.")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
            item(key = "now") {
                val h = list.first()
                ReadingNow(h, onResume = { resume(h) }, onOpen = { nav.manga(h.manga.id) }, onFavorite = { scope.launch { app.db.setFavorite(h.manga.id, true) } })
            }
            if (list.size > 1) item(key = "others") { Text("Lidos antes", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)) }
            items(list.drop(1), key = { it.manga.id }) { h ->
                HistoryRow(h, onOpen = { nav.manga(h.manga.id) }, onResume = { resume(h) }, onRemove = { scope.launch { app.db.clearHistory(h.manga.id) } })
            }
        }
    }
}

private fun HistoryItem.state(): String? = when {
    chapter.read -> "Capítulo terminado"
    chapter.pageCount > 0 -> "Página ${chapter.lastPage + 1} de ${chapter.pageCount}"
    else -> null
}

/** Obra que está sendo lida agora: capa grande, progresso da obra inteira e atalhos. */
@Composable
private fun ReadingNow(h: HistoryItem, onResume: () -> Unit, onOpen: () -> Unit, onFavorite: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface2)
            .clickable(onClick = onOpen).padding(12.dp),
    ) {
        Text("LENDO AGORA", color = AccentLight, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Row(Modifier.padding(top = 8.dp)) {
            Box(Modifier.width(96.dp)) { Cover(h.manga.source, h.manga.thumbnailUrl, Modifier.fillMaxWidth()) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(h.manga.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(h.chapter.name, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                h.state()?.let { Text(it, color = Muted, fontSize = 12.sp) }
                if (h.total > 0) {
                    Text("${h.readCount} de ${h.total} capítulos lidos", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    LinearProgressIndicator(
                        { h.readCount.toFloat() / h.total }, Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = AccentLight, trackColor = Color.White.copy(alpha = .12f),
                    )
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onResume, Modifier.weight(1f)) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Text(if (h.chapter.read) " Próximo capítulo" else " Continuar")
            }
            OutlinedButton(onOpen, Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp))
                Text(" Obra completa")
            }
        }
        if (!h.manga.favorite) {
            TextButton(onFavorite, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Text(" Adicionar à biblioteca")
            }
        } else {
            Text("Na sua biblioteca", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun HistoryRow(h: HistoryItem, onOpen: () -> Unit, onResume: () -> Unit, onRemove: () -> Unit) {
    val date = remember(h.chapter.lastRead) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(h.chapter.lastRead)) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onResume).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(56.dp).clickable(onClick = onOpen)) { Cover(h.manga.source, h.manga.thumbnailUrl, Modifier.fillMaxWidth()) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(h.manga.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(h.chapter.name, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(h.state(), date).joinToString(" · "), color = Muted, fontSize = 12.sp)
            if (h.total > 0) Text("${h.readCount}/${h.total} capítulos lidos", color = Muted, fontSize = 12.sp)
        }
        IconButton(onOpen) { Icon(Icons.AutoMirrored.Filled.MenuBook, "Ver a obra completa", tint = AccentLight) }
        IconButton(onRemove) { Icon(Icons.Default.Close, "Remover do histórico", tint = Muted) }
    }
}

@Composable
private fun BrowseTab(goToExtensions: () -> Unit) {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val installed by app.extensions.installed.collectAsState()
    val settings by app.extensions.settings.collectAsState()
    val ready by app.extensions.ready.collectAsState()
    val sources = remember(installed, settings) { app.extensions.visibleSources() }
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Explorar", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas))
        OutlinedTextField(
            query, { query = it }, singleLine = true, placeholder = { Text("Pesquisar em todas as fontes") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) Row {
                    IconButton({ query = "" }) { Icon(Icons.Default.Close, "Limpar") }
                    if (query.isNotBlank()) IconButton({ nav.search(query.trim()) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Pesquisar") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) nav.search(query.trim()) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        // em quais fontes a pesquisa procura (o mesmo filtro da tela de resultados)
        val ctx = LocalContext.current
        val searchPrefs = remember { SearchSourcePrefs(ctx) }
        var chosen by remember { mutableStateOf(searchPrefs.selected) }
        if (sources.isNotEmpty()) SearchSourcesChip(sources, chosen, { chosen = it; searchPrefs.selected = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Spacer(Modifier.height(4.dp))
        when {
            !ready -> Unit
            sources.isEmpty() -> EmptyState(
                "Nenhuma fonte instalada",
                "As fontes vêm das extensões (as mesmas do Tachiyomi/Mihon). Instale as que você usa, como Manga Livre, Mugiwaras ou MangaDex.",
            ) { Button(goToExtensions) { Text("Ver extensões") } }
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                var lastLang: String? = null
                sources.forEach { s ->
                    if (s.lang != lastLang) {
                        lastLang = s.lang
                        item(key = "lang-${s.lang}") { Text(ExtensionManager.langName(s.lang), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
                    }
                    item(key = s.id) {
                        Row(Modifier.fillMaxWidth().clickable { nav.source(s.id) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Surface2), contentAlignment = Alignment.Center) {
                                Text(s.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = AccentLight)
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(s.name, fontWeight = FontWeight.SemiBold)
                                if (s.supportsLatest) Text("Populares · Recentes", color = Muted, fontSize = 12.sp) else Text("Populares", color = Muted, fontSize = 12.sp)
                            }
                            Text(s.lang.uppercase(), color = Muted, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Surface2).padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SmallLabel(text: String, color: Color = Muted) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
}
