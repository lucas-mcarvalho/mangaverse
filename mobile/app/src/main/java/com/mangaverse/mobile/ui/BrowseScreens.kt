package com.mangaverse.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangaverse.mobile.App
import com.mangaverse.mobile.app
import com.mangaverse.mobile.ext.ExtensionManager
import com.mangaverse.mobile.ext.Genre
import com.mangaverse.mobile.ext.Genres
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Abre um título vindo de uma listagem: grava no banco e navega para ele. */
fun openRemote(app: App, nav: Nav, sourceId: Long, s: SManga, scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch { nav.manga(app.db.networkToLocal(sourceId, s).id) }
}

enum class Listing(val label: String) { POPULAR("Populares"), LATEST("Recentes"), SEARCH("Busca"), GENRE("Categoria") }

@Composable
fun SourceScreen(sourceId: Long, initialListing: Listing = Listing.POPULAR, initialGenre: Int = -1) {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val source = remember(sourceId) { app.extensions.source(sourceId) as? CatalogueSource }
    var listing by rememberSaveable { mutableStateOf(if (initialGenre >= 0) Listing.GENRE else initialListing) }
    var query by rememberSaveable { mutableStateOf("") }
    var genreIndex by rememberSaveable { mutableStateOf(initialGenre) }
    var genreName by rememberSaveable { mutableStateOf<String?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var genresOpen by remember { mutableStateOf(false) }

    val items = remember(listing, query, genreIndex) { mutableStateListOf<SManga>() }
    var page by remember(listing, query, genreIndex) { mutableStateOf(0) }
    var hasNext by remember(listing, query, genreIndex) { mutableStateOf(true) }
    var loading by remember(listing, query, genreIndex) { mutableStateOf(false) }
    var error by remember(listing, query, genreIndex) { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val genres = rememberLoad(sourceId) { source?.let { withContext(Dispatchers.IO) { Genres.of(it) } }.orEmpty() }

    fun loadMore() {
        val s = source ?: return
        if (loading || !hasNext) return
        loading = true
        error = null
        val next = page + 1
        job = scope.launch {
            try {
                val result: MangasPage = withContext(Dispatchers.IO) {
                    when (listing) {
                        Listing.POPULAR -> s.getPopularManga(next)
                        Listing.LATEST -> s.getLatestUpdates(next)
                        Listing.SEARCH -> s.getSearchManga(next, query, s.getFilterList())
                        Listing.GENRE -> {
                            val (genre, filters) = Genres.filtersFor(s, genreIndex) ?: throw IllegalStateException("Esta fonte não tem essa categoria")
                            genreName = genre.name
                            s.getSearchManga(next, "", filters)
                        }
                    }
                }
                val known = items.map { it.url }.toHashSet()
                items += result.mangas.filter { known.add(it.url) }
                page = next
                hasNext = result.hasNextPage && result.mangas.isNotEmpty()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.friendly()
            } finally {
                loading = false
            }
        }
    }

    fun show(l: Listing, genre: Genre? = null) {
        job?.cancel()
        if (genre != null) { genreIndex = genre.index; genreName = genre.name }
        listing = l
    }

    val grid = rememberLazyGridState()
    LaunchedEffect(listing, query, genreIndex) { loadMore() }
    LaunchedEffect(grid, listing, query, genreIndex) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= items.size - 6) loadMore()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton({ nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
            title = {
                if (searchOpen) {
                    var text by rememberSaveable { mutableStateOf(query) }
                    OutlinedTextField(
                        text, { text = it }, singleLine = true, placeholder = { Text("Pesquisar em ${source?.name ?: "fonte"}") },
                        trailingIcon = { if (text.isNotEmpty()) IconButton({ text = "" }) { Icon(Icons.Default.Close, "Limpar") } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (text.isNotBlank()) { job?.cancel(); query = text.trim(); listing = Listing.SEARCH } }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Column {
                        Text(source?.name ?: "Fonte", fontWeight = FontWeight.Bold)
                        if (source != null) Text(ExtensionManager.langName(source.lang), color = Muted, fontSize = 12.sp)
                    }
                }
            },
            actions = {
                IconButton({
                    searchOpen = !searchOpen
                    if (!searchOpen && listing == Listing.SEARCH) { query = ""; show(Listing.POPULAR) }
                }) { Icon(if (searchOpen) Icons.Default.Close else Icons.Default.Search, "Pesquisar") }
                if (source is HttpSource) IconButton({ nav.webView(source.baseUrl, source.id) }) { Icon(Icons.Default.Public, "Abrir o site") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas),
        )
        if (source == null) {
            EmptyState("Fonte indisponível", "A extensão desta fonte não está instalada ou não carregou.")
            return@Column
        }
        if (listing != Listing.SEARCH) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(Listing.POPULAR.label, listing == Listing.POPULAR) { show(Listing.POPULAR) }
                if (source.supportsLatest) Pill("Lançamentos", listing == Listing.LATEST) { show(Listing.LATEST) }
                if (genres.data?.isNotEmpty() == true) {
                    Pill(if (listing == Listing.GENRE && genreName != null) "${genreName} ▾" else "Categorias ▾", listing == Listing.GENRE) { genresOpen = true }
                }
            }
        }
        if (items.isEmpty() && error != null) {
            ErrorBox(error!!, onRetry = ::loadMore)
            ErrorExtra(source)
            return@Column
        }
        if (items.isEmpty() && !loading && !hasNext) {
            EmptyState(
                "Nada encontrado",
                when (listing) {
                    Listing.SEARCH -> "Nenhum resultado para “$query” nesta fonte."
                    Listing.GENRE -> "A fonte não devolveu títulos em “${genreName ?: "categoria"}”."
                    else -> "A fonte não devolveu títulos."
                },
            )
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), state = grid, contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(items, key = { _, m -> m.url }) { _, m ->
                TitleCard(m.title, source.id, m.thumbnail_url) { openRemote(app, nav, source.id, m, scope) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(28.dp), color = AccentLight)
                        error != null -> TextButton(::loadMore) { Text("Erro: $error · tentar de novo") }
                    }
                }
            }
        }
    }

    if (genresOpen) {
        GenresSheet(genres.data.orEmpty(), selected = genreIndex.takeIf { listing == Listing.GENRE }, onClose = { genresOpen = false }) { g ->
            genresOpen = false
            show(Listing.GENRE, g)
        }
    }
}

/** Lista de categorias de uma fonte para escolher uma. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun GenresSheet(genres: List<Genre>, selected: Int?, onClose: () -> Unit, onPick: (Genre) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val shown = genres.filter { filter.isBlank() || it.name.contains(filter.trim(), true) }
    androidx.compose.material3.ModalBottomSheet(onClose, containerColor = Surface1) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            Text("Categorias", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (genres.size > 12) OutlinedTextField(
                filter, { filter = it }, singleLine = true, placeholder = { Text("Filtrar categorias") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (filter.isNotEmpty()) IconButton({ filter = "" }) { Icon(Icons.Default.Close, "Limpar") } },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            androidx.compose.foundation.layout.FlowRow(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                shown.forEach { g -> Pill(g.name, g.index == selected) { onPick(g) } }
            }
        }
    }
}

/** Dica para quando o site bloqueia (Cloudflare/captcha): abrir no navegador interno, que guarda os cookies. */
@Composable
private fun ErrorExtra(source: CatalogueSource) {
    val nav = LocalNav.current
    if (source is HttpSource) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton({ nav.webView(source.baseUrl, source.id) }) { Text("Abrir o site no navegador do app (resolve captcha/Cloudflare)") }
        }
    }
}

private sealed interface SearchState {
    data object Loading : SearchState
    data class Done(val mangas: List<SManga>) : SearchState
    data class Failed(val message: String) : SearchState
}

/**
 * Pesquisa em várias fontes ao mesmo tempo. Na aba Pesquisar ([inTab]) não tem botão de voltar e, sem texto, mostra as
 * pesquisas recentes e as categorias da fonte principal do Início.
 */
@Composable
fun GlobalSearchScreen(initial: String, inTab: Boolean = false, goToExtensions: () -> Unit = {}) {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf(initial) }
    var text by rememberSaveable { mutableStateOf(initial) }
    val ctx = LocalContext.current
    val searchPrefs = remember { SearchSourcePrefs(ctx) }
    val catalogPrefs = remember { CatalogPrefs(ctx) }
    val installed by app.extensions.installed.collectAsState()
    val settings by app.extensions.settings.collectAsState()
    val allSources = remember(installed, settings) { app.extensions.visibleSources() }
    var chosen by remember { mutableStateOf(searchPrefs.selected) }
    val sources = remember(allSources, chosen) { searchSources(allSources, chosen) }
    val results = remember(query, sources) { mutableStateMapOf<Long, SearchState>() }
    var recent by remember { mutableStateOf(catalogPrefs.recentSearches) }

    fun submit(q: String) {
        val t = q.trim()
        if (t.isBlank()) return
        text = t
        query = t
        catalogPrefs.addRecentSearch(t)
        recent = catalogPrefs.recentSearches
    }

    LaunchedEffect(Unit) { if (initial.isNotBlank()) { catalogPrefs.addRecentSearch(initial); recent = catalogPrefs.recentSearches } }

    LaunchedEffect(query, sources) {
        if (query.isBlank()) return@LaunchedEffect
        sources.forEach { results[it.id] = SearchState.Loading }
        val gate = Semaphore(5)
        coroutineScope {
            sources.map { s ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        results[s.id] = try {
                            SearchState.Done(withTimeout(45_000) { s.getSearchManga(1, query, s.getFilterList()) }.mangas)
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                            SearchState.Failed(if (e is kotlinx.coroutines.TimeoutCancellationException) "demorou demais" else e.friendly())
                        }
                    }
                }
            }.awaitAll()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { if (!inTab) IconButton({ nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
            title = {
                OutlinedTextField(
                    text, { text = it }, singleLine = true, placeholder = { Text("Pesquisar mangás, manhwas, HQs…") },
                    leadingIcon = if (inTab) ({ Icon(Icons.Default.Search, null) }) else null,
                    trailingIcon = { if (text.isNotEmpty()) IconButton({ text = ""; if (inTab) query = "" }) { Icon(Icons.Default.Close, "Limpar") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(text) }),
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas),
        )
        if (allSources.isEmpty()) {
            EmptyState("Nenhuma fonte instalada", "Instale extensões para pesquisar títulos.") {
                androidx.compose.material3.Button(goToExtensions) { Text("Ver extensões") }
            }
            return@Column
        }
        SearchSourcesChip(allSources, chosen, { chosen = it; searchPrefs.selected = it }, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

        if (query.isBlank()) {
            SearchSuggestions(
                recent = recent, mainSource = catalogPrefs.mainSource(allSources),
                onSearch = ::submit,
                onRemove = { catalogPrefs.removeRecentSearch(it); recent = catalogPrefs.recentSearches },
                onClear = { catalogPrefs.clearRecentSearches(); recent = emptyList() },
            )
            return@Column
        }

        val withResults = sources.filter { (results[it.id] as? SearchState.Done)?.mangas?.isNotEmpty() == true }
        val pending = sources.count { results[it.id] == null || results[it.id] == SearchState.Loading }
        val empty = sources.filter { (results[it.id] as? SearchState.Done)?.mangas?.isEmpty() == true }
        val failed = sources.filter { results[it.id] is SearchState.Failed }

        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            if (pending > 0) item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentLight)
                    Text("  Pesquisando em $pending fonte(s)…", color = Muted, fontSize = 13.sp)
                }
            }
            items(withResults, key = { it.id }) { s ->
                val list = (results[s.id] as SearchState.Done).mangas
                Column(Modifier.padding(top = 8.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { nav.source(s.id) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${s.lang.uppercase()} · ${list.size}", color = Muted, fontSize = 12.sp)
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list, key = { it.url }) { m -> TitleCard(m.title, s.id, m.thumbnail_url, width = 104.dp) { openRemote(app, nav, s.id, m, scope) } }
                    }
                }
            }
            if (pending == 0 && withResults.isEmpty()) item { EmptyState("Nada encontrado", "Nenhuma fonte encontrou “$query”.", Modifier.height(240.dp)) }
            if (empty.isNotEmpty()) item {
                Text("Sem resultados: " + empty.joinToString { it.name }, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            if (failed.isNotEmpty()) item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("Falharam", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    failed.forEach { s -> Text("${s.name}: ${(results[s.id] as SearchState.Failed).message}", color = Muted, fontSize = 12.sp, maxLines = 2) }
                }
            }
        }
    }
}

/** Pesquisa vazia: pesquisas recentes e categorias da fonte principal. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SearchSuggestions(
    recent: List<String>, mainSource: CatalogueSource?, onSearch: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit,
) {
    val nav = LocalNav.current
    val genres = rememberLoad(mainSource?.id) { mainSource?.let { withContext(Dispatchers.IO) { Genres.of(it) } }.orEmpty() }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        if (recent.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Pesquisas recentes", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClear) { Text("Limpar", fontSize = 12.sp) }
                }
            }
            items(recent, key = { "r-$it" }) { q ->
                Row(Modifier.fillMaxWidth().clickable { onSearch(q) }.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, null, tint = Muted, modifier = Modifier.size(20.dp))
                    Text(q, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp))
                    IconButton({ onRemove(q) }) { Icon(Icons.Default.Close, "Remover", tint = Muted, modifier = Modifier.size(18.dp)) }
                }
            }
        }
        val list = genres.data.orEmpty()
        if (mainSource != null && list.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Explorar por categoria", fontWeight = FontWeight.Bold)
                    Text("em ${mainSource.name}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { g -> Pill(g.name, false) { nav.source(mainSource.id, genre = g.index) } }
                    }
                }
            }
        }
        if (recent.isEmpty() && list.isEmpty() && !genres.loading) {
            item { EmptyState("Pesquise em todas as suas fontes", "Digite o nome de um título. As pesquisas ficam salvas aqui.", Modifier.height(260.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Fontes da pesquisa global: o usuário escolhe em quais fontes pesquisar (vazio = todas). Fica salvo no aparelho.

class SearchSourcePrefs(context: android.content.Context) {
    private val p = context.getSharedPreferences("search", android.content.Context.MODE_PRIVATE)
    var selected: Set<Long>
        get() = p.getStringSet("sources", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = p.edit().putStringSet("sources", v.map { it.toString() }.toSet()).apply()
}

/** Fontes em que a pesquisa global vai procurar (as escolhidas que ainda existem; nenhuma escolhida = todas). */
fun searchSources(all: List<CatalogueSource>, selected: Set<Long>): List<CatalogueSource> =
    all.filter { it.id in selected }.ifEmpty { all }

/** Botão "Fontes: …" que abre a escolha das fontes da pesquisa. */
@Composable
fun SearchSourcesChip(all: List<CatalogueSource>, selected: Set<Long>, onChange: (Set<Long>) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val chosen = all.count { it.id in selected }
    val label = if (chosen == 0) "Fontes: todas (${all.size})" else "Fontes: $chosen de ${all.size}"
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Pill("⚙ $label", chosen > 0) { open = true }
        if (chosen > 0) TextButton({ onChange(emptySet()) }) { Text("Usar todas", fontSize = 12.sp) }
    }
    if (open) SearchSourcesSheet(all, selected, onChange) { open = false }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SearchSourcesSheet(all: List<CatalogueSource>, selected: Set<Long>, onChange: (Set<Long>) -> Unit, onClose: () -> Unit) {
    var sel by remember { mutableStateOf(selected.filter { id -> all.any { it.id == id } }.toSet()) }
    var filter by remember { mutableStateOf("") }
    val app = LocalContext.current.app
    val adult = remember(all) { app.extensions.adultSourceIds().let { ids -> all.filter { it.id in ids }.map { it.id }.toSet() } }
    val shown = all.filter { filter.isBlank() || it.name.contains(filter.trim(), true) }
    androidx.compose.material3.ModalBottomSheet({ onChange(sel); onClose() }, containerColor = Surface1) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            Text("Pesquisar só nestas fontes", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Nenhuma marcada = pesquisa em todas.", color = Muted, fontSize = 12.sp)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Todas", sel.size == all.size) { sel = all.map { it.id }.toSet() }
                Pill("Nenhuma", sel.isEmpty()) { sel = emptySet() }
                if (adult.isNotEmpty()) Pill("Sem +18", sel.isNotEmpty() && sel.none { it in adult } && sel.size == all.size - adult.size) { sel = all.map { it.id }.toSet() - adult }
                val ptBr = all.filter { it.lang.startsWith("pt") }.map { it.id }.toSet()
                if (ptBr.isNotEmpty()) Pill("Só PT-BR", sel == ptBr) { sel = ptBr }
            }
            OutlinedTextField(
                filter, { filter = it }, singleLine = true, placeholder = { Text("Filtrar fontes") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (filter.isNotEmpty()) IconButton({ filter = "" }) { Icon(Icons.Default.Close, "Limpar") } },
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
                items(shown, key = { it.id }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { sel = if (s.id in sel) sel - s.id else sel + s.id }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(s.id in sel, { sel = if (s.id in sel) sel - s.id else sel + s.id })
                        Text(s.name, modifier = Modifier.weight(1f))
                        if (s.id in adult) Text("18+", color = Pink, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                        Text(s.lang.uppercase(), color = Muted, fontSize = 11.sp)
                    }
                }
            }
            androidx.compose.material3.Button({ onChange(sel); onClose() }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(if (sel.isEmpty()) "Pesquisar em todas" else "Pesquisar em ${sel.size} fonte(s)")
            }
        }
    }
}
