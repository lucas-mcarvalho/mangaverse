package com.mangaverse.mobile.ui

import androidx.compose.foundation.clickable
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

private enum class Listing(val label: String) { POPULAR("Populares"), LATEST("Recentes"), SEARCH("Busca") }

@Composable
fun SourceScreen(sourceId: Long) {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val source = remember(sourceId) { app.extensions.source(sourceId) as? CatalogueSource }
    var listing by rememberSaveable { mutableStateOf(Listing.POPULAR) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    val items = remember(listing, query) { mutableStateListOf<SManga>() }
    var page by remember(listing, query) { mutableStateOf(0) }
    var hasNext by remember(listing, query) { mutableStateOf(true) }
    var loading by remember(listing, query) { mutableStateOf(false) }
    var error by remember(listing, query) { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

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

    val grid = rememberLazyGridState()
    LaunchedEffect(listing, query) { loadMore() }
    LaunchedEffect(grid, listing, query) {
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
                    if (!searchOpen && listing == Listing.SEARCH) { job?.cancel(); listing = Listing.POPULAR; query = "" }
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
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(Listing.POPULAR.label, listing == Listing.POPULAR) { job?.cancel(); listing = Listing.POPULAR }
                if (source.supportsLatest) Pill(Listing.LATEST.label, listing == Listing.LATEST) { job?.cancel(); listing = Listing.LATEST }
            }
        }
        if (items.isEmpty() && error != null) {
            ErrorBox(error!!, onRetry = ::loadMore)
            ErrorExtra(source)
            return@Column
        }
        if (items.isEmpty() && !loading && !hasNext) {
            EmptyState("Nada encontrado", if (listing == Listing.SEARCH) "Nenhum resultado para “$query” nesta fonte." else "A fonte não devolveu títulos.")
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

@Composable
fun GlobalSearchScreen(initial: String) {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf(initial) }
    var text by rememberSaveable { mutableStateOf(initial) }
    val ctx = LocalContext.current
    val searchPrefs = remember { SearchSourcePrefs(ctx) }
    val allSources = remember { app.extensions.visibleSources() }
    var chosen by remember { mutableStateOf(searchPrefs.selected) }
    val sources = remember(chosen) { searchSources(allSources, chosen) }
    val results = remember(query, chosen) { mutableStateMapOf<Long, SearchState>() }

    LaunchedEffect(query, chosen) {
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
            navigationIcon = { IconButton({ nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
            title = {
                OutlinedTextField(
                    text, { text = it }, singleLine = true, placeholder = { Text("Pesquisar") },
                    trailingIcon = { if (text.isNotEmpty()) IconButton({ text = "" }) { Icon(Icons.Default.Close, "Limpar") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (text.isNotBlank()) query = text.trim() }),
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas),
        )
        if (sources.isEmpty()) {
            EmptyState("Nenhuma fonte instalada", "Instale extensões na aba Extensões para pesquisar.")
            return@Column
        }
        SearchSourcesChip(allSources, chosen, { chosen = it; searchPrefs.selected = it }, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        val withResults = sources.filter { (results[it.id] as? SearchState.Done)?.mangas?.isNotEmpty() == true }
        val pending = sources.count { results[it.id] == SearchState.Loading }
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
    val adult = remember(all) { all.filter { ExtensionManager.isAdult(it.name, it.javaClass.name) }.map { it.id }.toSet() }
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
