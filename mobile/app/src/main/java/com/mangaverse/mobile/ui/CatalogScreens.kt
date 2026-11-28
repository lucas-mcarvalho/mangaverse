package com.mangaverse.mobile.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.mangaverse.mobile.data.CoverModel
import com.mangaverse.mobile.ext.ExtensionManager
import com.mangaverse.mobile.ext.Genre
import com.mangaverse.mobile.ext.Genres
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------------------------------------------
// Preferências do catálogo: fonte principal do Início e pesquisas recentes.

class CatalogPrefs(context: Context) {
    private val p = context.getSharedPreferences("catalog", Context.MODE_PRIVATE)

    var mainSourceId: Long
        get() = p.getLong("main_source", -1)
        set(v) = p.edit().putLong("main_source", v).apply()

    /** Fonte do Início: a escolhida, senão a primeira em português com lançamentos, senão a primeira. */
    fun mainSource(all: List<CatalogueSource>): CatalogueSource? =
        all.firstOrNull { it.id == mainSourceId }
            ?: all.firstOrNull { it.lang.startsWith("pt") && it.supportsLatest }
            ?: all.firstOrNull { it.supportsLatest }
            ?: all.firstOrNull()

    val recentSearches: List<String>
        get() = p.getString("recent_searches", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun addRecentSearch(q: String) = saveRecent(listOf(q.trim()) + recentSearches.filterNot { it.equals(q.trim(), true) })
    fun removeRecentSearch(q: String) = saveRecent(recentSearches - q)
    fun clearRecentSearches() = saveRecent(emptyList())
    private fun saveRecent(list: List<String>) = p.edit().putString("recent_searches", list.take(15).joinToString("\n")).apply()
}

/** Cache em memória das listagens do Início, para não buscar tudo de novo a cada troca de aba (vale 15 min). */
object CatalogCache {
    private class Entry(val at: Long, val value: Any)
    private val map = ConcurrentHashMap<String, Entry>()
    private const val TTL = 15 * 60_000L

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, force: Boolean, block: suspend () -> T): T {
        map[key]?.takeIf { !force && System.currentTimeMillis() - it.at < TTL }?.let { return it.value as T }
        return block().also { map[key] = Entry(System.currentTimeMillis(), it) }
    }

    suspend fun listing(s: CatalogueSource, listing: Listing, force: Boolean = false): List<SManga> = cached("${s.id}:$listing", force) {
        withContext(Dispatchers.IO) {
            withTimeout(45_000) { if (listing == Listing.LATEST) s.getLatestUpdates(1) else s.getPopularManga(1) }.mangas
        }
    }

    suspend fun genres(s: CatalogueSource, force: Boolean = false): List<Genre> = cached("${s.id}:genres", force) {
        withContext(Dispatchers.IO) { Genres.of(s) }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Início: catálogo com destaques, lançamentos recentes, categorias, populares e novidades das outras fontes.

@Composable
fun CatalogHomeTab(openSearch: () -> Unit, goToExtensions: () -> Unit) {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by app.extensions.installed.collectAsState()
    val settings by app.extensions.settings.collectAsState()
    val ready by app.extensions.ready.collectAsState()
    val sources = remember(installed, settings) { app.extensions.visibleSources() }
    val prefs = remember { CatalogPrefs(ctx) }
    var mainId by rememberSaveable { mutableLongStateOf(prefs.mainSourceId) }
    val main = remember(sources, mainId) { prefs.mainSource(sources) }
    var tick by rememberSaveable { mutableIntStateOf(0) }
    var pickSource by remember { mutableStateOf(false) }
    var genresOpen by remember { mutableStateOf(false) }
    val hidden = remember(installed, settings) { app.extensions.hiddenSourceIds() }
    val history = rememberQuery(hidden) { app.db.history().filter { it.manga.source !in hidden } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("MangaVerse", fontWeight = FontWeight.Bold)
                    if (main != null) {
                        Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { pickSource = true }, verticalAlignment = Alignment.CenterVertically) {
                            Text("Catálogo: ${main.name}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ArrowDropDown, "Trocar a fonte do catálogo", tint = Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            },
            actions = {
                IconButton(openSearch) { Icon(Icons.Default.Search, "Pesquisar") }
                IconButton({ nav.settings() }) { Icon(Icons.Default.Settings, "Ajustes e atualizações") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas),
        )
        when {
            !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentLight) }
            main == null -> EmptyState(
                "Bem-vindo ao MangaVerse",
                "O catálogo vem das extensões (as mesmas do Tachiyomi/Mihon). Instale as fontes que você usa, como Manga Livre, " +
                    "Mugiwaras ou MangaDex, e os destaques, lançamentos e categorias aparecem aqui.",
            ) { Button(goToExtensions) { Text("Instalar extensões") } }
            else -> PullToRefreshBox(false, { tick++ }, Modifier.fillMaxSize()) {
                val force = tick > 0
                val popular = rememberLoad(main.id, tick) { CatalogCache.listing(main, Listing.POPULAR, force) }
                val others = remember(sources, main) {
                    sources.filter { it.id != main.id }
                        .sortedBy { if (it.lang == main.lang) 0 else 1 }
                        .take(4)
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                    item(key = "search") { SearchBox(openSearch) }

                    val reading = history.orEmpty().take(12)
                    if (reading.isNotEmpty()) item(key = "reading") {
                        Section("Continue lendo", onSeeAll = null) {
                            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(reading, key = { it.manga.id }) { h ->
                                    TitleCard(
                                        h.manga.title, h.manga.source, h.manga.thumbnailUrl, subtitle = h.chapter.name,
                                        progress = if (h.total > 0) h.readCount.toFloat() / h.total else null, width = 108.dp,
                                    ) { nav.manga(h.manga.id) }
                                }
                            }
                        }
                    }

                    item(key = "featured-${main.id}") {
                        Section("Em alta", subtitle = main.name, onSeeAll = { nav.source(main.id, Listing.POPULAR) }) {
                            ListingContent(popular, height = 180.dp) { list ->
                                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    itemsIndexed(list.take(8), key = { _, m -> m.url }) { i, m ->
                                        HeroCard(i + 1, m, main.id) { openRemote(app, nav, main.id, m, scope) }
                                    }
                                }
                            }
                        }
                    }

                    if (main.supportsLatest) item(key = "latest-${main.id}") {
                        MangaRow("Lançamentos recentes", main.name, main, Listing.LATEST, tick)
                    }

                    item(key = "genres-${main.id}") {
                        GenresSection(main, tick, onOpenAll = { genresOpen = true })
                    }

                    item(key = "popular-${main.id}") {
                        Section("Populares", subtitle = main.name, onSeeAll = { nav.source(main.id, Listing.POPULAR) }) {
                            ListingContent(popular) { list -> MangaLazyRow(list.drop(8).ifEmpty { list }, main.id) }
                        }
                    }

                    others.forEach { s ->
                        item(key = "other-${s.id}") {
                            MangaRow(
                                if (s.supportsLatest) "Novidades em ${s.name}" else "Populares em ${s.name}", ExtensionManager.langName(s.lang),
                                s, if (s.supportsLatest) Listing.LATEST else Listing.POPULAR, tick,
                            )
                        }
                    }

                    item(key = "all-sources") {
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                            TextButton({ pickSource = true }) { Text("Trocar a fonte do catálogo (${sources.size} instalada(s))") }
                        }
                    }
                }
            }
        }
    }

    if (pickSource && sources.isNotEmpty()) {
        SourcePickerSheet(sources, main?.id, onClose = { pickSource = false }) { s ->
            prefs.mainSourceId = s.id
            mainId = s.id
            pickSource = false
        }
    }
    if (genresOpen && main != null) {
        val genres = rememberLoad(main.id) { CatalogCache.genres(main) }
        GenresSheet(genres.data.orEmpty(), selected = null, onClose = { genresOpen = false }) { g ->
            genresOpen = false
            nav.source(main.id, genre = g.index)
        }
    }
}

/** Caixa de pesquisa do Início (abre a aba Pesquisar). */
@Composable
private fun SearchBox(onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(50)).background(Surface2)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = Muted, modifier = Modifier.size(20.dp))
        Text("  Pesquisar em todas as fontes", color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun Section(title: String, subtitle: String? = null, onSeeAll: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            if (onSeeAll != null) TextButton(onSeeAll) { Text("Ver tudo", color = AccentLight) }
        }
        content()
    }
}

/** Conteúdo de uma listagem: esqueleto enquanto carrega, erro com "tentar de novo", ou [content]. */
@Composable
private fun ListingContent(load: Load<List<SManga>>, height: androidx.compose.ui.unit.Dp = 200.dp, content: @Composable (List<SManga>) -> Unit) {
    val data = load.data
    when {
        data != null && data.isEmpty() -> Text("A fonte não devolveu títulos.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        data != null -> content(data)
        load.error != null -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(load.error!!, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton({ load.reload() }) { Text("Tentar de novo") }
        }
        else -> Row(Modifier.padding(horizontal = 12.dp).height(height), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(4) { Box(Modifier.width(108.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Surface2)) }
        }
    }
}

@Composable
private fun MangaLazyRow(list: List<SManga>, sourceId: Long) {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list.take(24), key = { it.url }) { m ->
            TitleCard(m.title, sourceId, m.thumbnail_url, width = 108.dp) { openRemote(app, nav, sourceId, m, scope) }
        }
    }
}

/** Fileira de uma listagem (populares/lançamentos) de uma fonte, com "Ver tudo". */
@Composable
private fun MangaRow(title: String, subtitle: String?, source: CatalogueSource, listing: Listing, tick: Int) {
    val nav = LocalNav.current
    val load = rememberLoad(source.id, listing, tick) { CatalogCache.listing(source, listing, tick > 0) }
    Section(title, subtitle, onSeeAll = { nav.source(source.id, listing) }) {
        ListingContent(load) { list -> MangaLazyRow(list, source.id) }
    }
}

/** Destaque grande: capa, posição e gêneros sobre a própria capa desfocada. */
@Composable
private fun HeroCard(rank: Int, m: SManga, sourceId: Long, onClick: () -> Unit) {
    val thumb = m.thumbnail_url
    Box(Modifier.width(300.dp).height(180.dp).clip(RoundedCornerShape(16.dp)).background(Surface2).clickable(onClick = onClick)) {
        if (!thumb.isNullOrBlank()) {
            AsyncImage(CoverModel(sourceId, thumb), null, Modifier.fillMaxSize().blur(16.dp), contentScale = ContentScale.Crop, alpha = .5f)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Canvas.copy(alpha = .92f), Canvas.copy(alpha = .35f)))))
        Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(100.dp)) { Cover(sourceId, thumb, Modifier.fillMaxWidth()) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("#$rank EM ALTA", color = AccentLight, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(m.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color.White)
                m.genre?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenresSection(source: CatalogueSource, tick: Int, onOpenAll: () -> Unit) {
    val nav = LocalNav.current
    val load = rememberLoad(source.id, tick) { CatalogCache.genres(source, tick > 0) }
    val list = load.data ?: return
    if (list.isEmpty()) return
    Section("Categorias", subtitle = source.name, onSeeAll = if (list.size > 14) onOpenAll else null) {
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.take(14).forEach { g -> Pill(g.name, false) { nav.source(source.id, genre = g.index) } }
        }
    }
}

/** Escolha da fonte do catálogo do Início. */
@Composable
private fun SourcePickerSheet(sources: List<CatalogueSource>, selected: Long?, onClose: () -> Unit, onPick: (CatalogueSource) -> Unit) {
    ModalBottomSheet(onClose, containerColor = Surface1) {
        Column(Modifier.padding(bottom = 20.dp)) {
            Text("Fonte do catálogo", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Text("Os destaques, lançamentos e categorias do Início vêm desta fonte.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(sources, key = { it.id }) { s ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOf(ExtensionManager.langName(s.lang), if (s.supportsLatest) "Populares · Lançamentos" else "Populares").joinToString(" · "),
                                color = Muted, fontSize = 12.sp,
                            )
                        }
                        if (s.id == selected) Icon(Icons.Default.Check, "Selecionada", tint = AccentLight)
                    }
                }
            }
        }
    }
}
