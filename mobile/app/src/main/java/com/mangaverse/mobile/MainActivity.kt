package com.mangaverse.mobile

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mangaverse.mobile.reader.ReaderScreen
import com.mangaverse.mobile.ui.DownloadsScreen
import com.mangaverse.mobile.ui.GlobalSearchScreen
import com.mangaverse.mobile.ui.HomeScreen
import com.mangaverse.mobile.ui.LocalNav
import com.mangaverse.mobile.ui.MangaScreen
import com.mangaverse.mobile.ui.MangaTheme
import com.mangaverse.mobile.ui.Nav
import com.mangaverse.mobile.ui.SourceScreen
import com.mangaverse.mobile.ui.WebViewScreen
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {
    /** Teclas de volume repassadas ao leitor (virar página). */
    val volumeKeys = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    var volumePaging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MangaTheme {
                val controller = rememberNavController()
                val nav = remember(controller) { Nav(controller) }
                CompositionLocalProvider(LocalNav provides nav) {
                    val long = { name: String -> navArgument(name) { type = NavType.LongType } }
                    NavHost(controller, startDestination = "home") {
                        composable("home") { HomeScreen() }
                        composable("manga/{id}", listOf(long("id"))) { MangaScreen(it.arguments!!.getLong("id")) }
                        composable("source/{id}", listOf(long("id"))) { SourceScreen(it.arguments!!.getLong("id")) }
                        composable("search/{q}", listOf(navArgument("q") { type = NavType.StringType })) { GlobalSearchScreen(it.arguments!!.getString("q").orEmpty()) }
                        composable("downloads") { DownloadsScreen() }
                        composable("web/{source}?url={url}", listOf(long("source"), navArgument("url") { type = NavType.StringType; defaultValue = "" })) {
                            WebViewScreen(it.arguments!!.getString("url").orEmpty(), it.arguments!!.getLong("source"))
                        }
                        composable("read/{id}", listOf(long("id"))) { e ->
                            ReaderScreen(
                                e.arguments!!.getLong("id"), onBack = { controller.popBackStack() },
                                // trocar de capítulo substitui o leitor atual: "voltar" sai do leitor
                                openChapter = { id -> controller.navigate("read/$id") { popUpTo("read/{id}") { inclusive = true } } },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumePaging && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            volumeKeys.tryEmit(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
