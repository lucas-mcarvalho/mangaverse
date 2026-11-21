package com.mangaverse.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mangaverse.mobile.app
import com.mangaverse.mobile.ext.ExtensionManager
import com.mangaverse.mobile.ext.InstalledExtension
import com.mangaverse.mobile.ext.KEIYOUSHI_REPO
import com.mangaverse.mobile.ext.RepoExtension
import kotlinx.coroutines.launch

private val Danger = Color(0xFFF87171)

@Composable
fun ExtensionsTab() {
    val app = LocalContext.current.app
    val ext = app.extensions
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val installed by ext.installed.collectAsState()
    val available by ext.available.collectAsState()
    val settings by ext.settings.collectAsState()
    val loadingRepo by ext.loadingRepo.collectAsState()
    val repoError by ext.repoError.collectAsState()
    var page by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<InstalledExtension?>(null) }
    val progress = remember { mutableStateMapOf<String, Float>() }

    fun install(e: RepoExtension) {
        if (progress.containsKey(e.pkg)) return
        progress[e.pkg] = 0f
        scope.launch {
            try {
                ext.install(e) { progress[e.pkg] = it }
                snackbar.showSnackbar("${e.name} instalada")
            } catch (t: Throwable) {
                snackbar.showSnackbar("Falha ao instalar ${e.name}: ${t.friendly()}")
            } finally {
                progress.remove(e.pkg)
            }
        }
    }

    val repoByPkg = remember(available) { available.associateBy { it.pkg } }
    val installedPkgs = remember(installed) { installed.map { it.pkg }.toSet() }
    val q = query.trim()
    // aba 0 = instaladas, 1 = disponíveis, 2 = +18 (instaladas e disponíveis só de conteúdo adulto, separadas das outras)
    val adultTab = settings.nsfw
    if (!adultTab && page == 2) page = 0
    val shownInstalled = remember(installed, q, page) {
        installed.filter { (q.isEmpty() || it.name.contains(q, true)) && ExtensionManager.isAdult(it.name, it.pkg) == (page == 2) }
    }
    val shownAvailable = remember(available, installedPkgs, settings, q, page) {
        available.filter { e ->
            e.pkg !in installedPkgs && (settings.nsfw || !e.nsfw) &&
                ExtensionManager.isAdult(e.name, e.pkg) == (page == 2) &&
                (e.lang in settings.langs || e.sources.any { it.lang in settings.langs }) &&
                (q.isEmpty() || e.name.contains(q, true))
        }.sortedWith(compareBy({ langRank(it.lang) }, { it.name.lowercase() }))
    }
    val adultInstalled = installed.count { ExtensionManager.isAdult(it.name, it.pkg) }
    val updates = installed.filter { i -> repoByPkg[i.pkg]?.let { it.versionCode > i.versionCode } == true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Extensões", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton({ scope.launch { ext.refreshRepo() } }, enabled = !loadingRepo) { Icon(Icons.Default.Refresh, "Atualizar lista") }
                    IconButton({ showSettings = true }) { Icon(Icons.Default.Settings, "Idiomas e repositórios") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (loadingRepo) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AccentLight)
            TabRow(page, containerColor = Canvas) {
                Tab(page == 0, { page = 0 }, text = { Text("Instaladas (${installed.size - adultInstalled})", maxLines = 1) })
                Tab(page == 1, { page = 1 }, text = { Text("Disponíveis", maxLines = 1) })
                if (adultTab) Tab(page == 2, { page = 2 }, text = { Text("+18" + if (adultInstalled > 0) " ($adultInstalled)" else "", color = if (page == 2) Pink else Color.Unspecified, maxLines = 1) })
            }
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("Procurar extensão") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Limpar") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            repoError?.let { Text("Repositório indisponível: $it", color = Danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }

            if (page == 2) {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    item { SectionHeader("Instaladas (${shownInstalled.size})") }
                    if (shownInstalled.isEmpty()) item { Text("Nenhuma extensão +18 instalada.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                    items(shownInstalled, key = { "i-" + it.pkg }) { i ->
                        val repo = repoByPkg[i.pkg]
                        val hasUpdate = repo != null && repo.versionCode > i.versionCode
                        ExtensionRow(
                            name = i.name, icon = repo?.iconUrl, subtitle = listOf(ExtensionManager.langName(i.lang), "v${i.versionName}").joinToString(" · "),
                            nsfw = true, error = i.error, progress = progress[i.pkg],
                        ) {
                            if (hasUpdate) Button({ install(repo) }) { Text("Atualizar") } else OutlinedButton({ confirmRemove = i }) { Text("Remover") }
                        }
                    }
                    item { SectionHeader("Disponíveis (${shownAvailable.size})") }
                    items(shownAvailable, key = { "a-" + it.pkg }) { e ->
                        ExtensionRow(
                            name = e.name, icon = e.iconUrl, subtitle = listOf(ExtensionManager.langName(e.lang), "v${e.versionName}").joinToString(" · "),
                            nsfw = true, error = null, progress = progress[e.pkg],
                        ) { Button({ install(e) }) { Text("Instalar") } }
                    }
                    item { Text("Só extensões de conteúdo adulto ficam aqui. Para esconder esta aba, desligue “Mostrar extensões adultas” nos ajustes.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp)) }
                }
            } else if (page == 0) {
                if (installed.isEmpty()) {
                    EmptyState("Nenhuma extensão instalada", "Na aba “Disponíveis”, instale as fontes que você quer usar. O app baixa e confere a assinatura de cada uma.") {
                        Button({ page = 1 }) { Text("Ver disponíveis") }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        if (updates.isNotEmpty() && q.isEmpty()) {
                            item {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${updates.size} atualização(ões)", modifier = Modifier.weight(1f), color = AccentLight, fontWeight = FontWeight.SemiBold)
                                    TextButton({ updates.forEach { i -> repoByPkg[i.pkg]?.let(::install) } }) { Text("Atualizar todas") }
                                }
                            }
                        }
                        items(shownInstalled, key = { it.pkg }) { i ->
                            val repo = repoByPkg[i.pkg]
                            val hasUpdate = repo != null && repo.versionCode > i.versionCode
                            ExtensionRow(
                                name = i.name, icon = repo?.iconUrl,
                                subtitle = listOf(ExtensionManager.langName(i.lang), "v${i.versionName}").joinToString(" · "),
                                nsfw = false, error = i.error, progress = progress[i.pkg],
                            ) {
                                if (hasUpdate) Button({ install(repo) }) { Text("Atualizar") }
                                else OutlinedButton({ confirmRemove = i }) { Text("Remover") }
                            }
                        }
                    }
                }
            } else {
                if (available.isEmpty() && !loadingRepo) {
                    EmptyState("Lista indisponível", "Não foi possível baixar a lista de extensões. Confira a internet e toque em atualizar.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(shownAvailable, key = { it.pkg }) { e ->
                            ExtensionRow(
                                name = e.name, icon = e.iconUrl,
                                subtitle = listOf(ExtensionManager.langName(e.lang), "v${e.versionName}").joinToString(" · "),
                                nsfw = false, error = null, progress = progress[e.pkg],
                            ) { Button({ install(e) }) { Text("Instalar") } }
                        }
                        item {
                            Text(
                                "Mostrando ${shownAvailable.size} de ${available.size}. Idiomas e conteúdo adulto mudam no ícone de ajustes.",
                                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    confirmRemove?.let { i ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remover ${i.name}?") },
            text = { Text("Os títulos dessa fonte continuam na biblioteca e os capítulos baixados continuam legíveis, mas não dá para atualizar nem ler online até instalar de novo.") },
            confirmButton = { TextButton({ ext.uninstall(i.pkg); confirmRemove = null }) { Text("Remover", color = Danger) } },
            dismissButton = { TextButton({ confirmRemove = null }) { Text("Cancelar") } },
        )
    }

    if (showSettings) ExtensionSettingsSheet(ext) { showSettings = false }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

private fun langRank(lang: String) = when (lang) {
    "pt-BR", "pt" -> 0
    "all" -> 1
    "en" -> 2
    else -> 3
}

@Composable
private fun ExtensionRow(
    name: String, icon: String?, subtitle: String, nsfw: Boolean, error: String?, progress: Float?, action: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Surface2), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = AccentLight)
            if (icon != null) AsyncImage(icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (nsfw) Text(
                    "18+", color = Pink, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp)).background(Pink.copy(alpha = .15f)).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            Text(subtitle, color = Muted, fontSize = 12.sp)
            if (error != null) Text(error, color = Danger, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (progress != null) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(top = 4.dp), color = AccentLight)
        }
        if (progress != null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = AccentLight) else action()
    }
}

private val LANGS = listOf("pt-BR", "en", "es", "es-419", "ja", "ko", "zh", "fr", "it", "de", "ru", "id", "vi", "tr", "ar")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtensionSettingsSheet(ext: ExtensionManager, onClose: () -> Unit) {
    val current = ext.settings.value
    var langs by remember { mutableStateOf(current.langs) }
    var nsfw by remember { mutableStateOf(current.nsfw) }
    var repos by remember { mutableStateOf(current.repos.joinToString("\n")) }

    fun save() {
        val list = repos.lines().map { it.trim() }.filter { it.startsWith("http") }.ifEmpty { listOf(KEIYOUSHI_REPO) }
        ext.updateSettings(ExtensionManager.ExtSettings(list, langs + "all", nsfw))
        onClose()
    }

    ModalBottomSheet(::save, containerColor = Surface1) {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Idiomas das fontes", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LANGS.forEach { l -> Pill(ExtensionManager.langName(l), l in langs) { langs = if (l in langs) langs - l else langs + l } }
            }
            Row(Modifier.fillMaxWidth().clickable { nsfw = !nsfw }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mostrar extensões adultas (aba +18)")
                    Text("Mostra a aba +18, com as extensões adultas separadas das outras.", color = Muted, fontSize = 12.sp)
                }
                Switch(nsfw, { nsfw = it })
            }
            Text("Repositórios", fontWeight = FontWeight.Bold)
            Text("Um por linha. Aceita o index.json, o index.min.json ou o repo.json de repositórios no formato do Mihon.", color = Muted, fontSize = 12.sp)
            OutlinedTextField(repos, { repos = it }, Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
            Button(::save, Modifier.fillMaxWidth()) { Text("Salvar") }
        }
    }
}
