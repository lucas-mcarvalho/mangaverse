package com.mangaverse.mobile.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mangaverse.mobile.app
import com.mangaverse.mobile.data.DownloadTask
import eu.kanade.tachiyomi.source.online.HttpSource

@Composable
fun DownloadsScreen() {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val queue by app.downloads.queue.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton({ nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
            title = { Text("Downloads", fontWeight = FontWeight.Bold) },
            actions = { if (queue.isNotEmpty()) TextButton({ app.downloads.cancelAll() }) { Text("Cancelar todos") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas),
        )
        if (queue.isEmpty()) {
            EmptyState("Nenhum download na fila", "Na página de um título, toque no ícone ao lado do capítulo ou use o menu para baixar vários. Os capítulos baixados ficam disponíveis sem internet.")
            return@Column
        }
        Text(
            "Mantenha o app aberto enquanto baixa: o Android pode interromper downloads em segundo plano.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(queue, key = { it.chapterId }) { t ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.mangaTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.chapterName, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when (t.state) {
                            DownloadTask.State.RUNNING -> {
                                LinearProgressIndicator({ if (t.total > 0) t.done.toFloat() / t.total else 0f }, Modifier.fillMaxWidth().padding(top = 6.dp), color = AccentLight)
                                Text(if (t.total > 0) "${t.done}/${t.total} páginas" else "Buscando páginas…", color = Muted, fontSize = 11.sp)
                            }
                            DownloadTask.State.QUEUED -> Text("Na fila", color = Muted, fontSize = 11.sp)
                            DownloadTask.State.ERROR -> Text("Falhou: ${t.error}", color = Color(0xFFF87171), fontSize = 12.sp, maxLines = 2)
                        }
                    }
                    if (t.state == DownloadTask.State.ERROR) IconButton({ app.downloads.retry(t.chapterId) }) { Icon(Icons.Default.Refresh, "Tentar de novo") }
                    IconButton({ app.downloads.cancel(t.chapterId) }) { Icon(Icons.Default.Close, "Cancelar") }
                }
            }
        }
    }
}

/**
 * Navegador interno: usa os mesmos cookies das fontes, então resolver um captcha/Cloudflare aqui
 * libera o acesso da extensão.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(url: String, sourceId: Long) {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val source = remember(sourceId) { app.extensions.source(sourceId) as? HttpSource }
    var title by remember { mutableStateOf(source?.name ?: url) }
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(canGoBack) { webView?.goBack() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton({ nav.back() }) { Icon(Icons.Default.Close, "Fechar") } },
            title = {
                Column {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                    Text("Resolva o captcha, se aparecer, e volte", color = Muted, fontSize = 11.sp)
                }
            },
            actions = { IconButton({ webView?.reload() }) { Icon(Icons.Default.Refresh, "Recarregar") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas),
        )
        if (progress in 1..99) LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth(), color = AccentLight)
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // mesmo User-Agent da extensão, senão o cookie do Cloudflare não vale para ela
                    source?.headers?.get("User-Agent")?.let { settings.userAgentString = it }
                        ?: run { settings.userAgentString = app.network.defaultUserAgentProvider() }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                            canGoBack = view.canGoBack()
                        }

                        override fun onPageFinished(view: WebView, u: String?) {
                            canGoBack = view.canGoBack()
                            view.title?.takeIf { it.isNotBlank() }?.let { title = it }
                            CookieManager.getInstance().flush()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    val headers = source?.headers?.toMultimap()?.mapValues { it.value.firstOrNull().orEmpty() }?.filterKeys { !it.equals("User-Agent", true) }.orEmpty()
                    loadUrl(url, headers)
                    webView = this
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
