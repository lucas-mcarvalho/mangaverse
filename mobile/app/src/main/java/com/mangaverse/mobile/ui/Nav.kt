package com.mangaverse.mobile.ui

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController

/** Atalhos de navegação usados pelas telas. */
class Nav(val controller: NavHostController) {
    fun manga(id: Long) = controller.navigate("manga/$id")
    fun source(id: Long) = controller.navigate("source/$id")
    fun search(query: String) = controller.navigate("search/${Uri.encode(query)}")
    fun read(chapterId: Long) = controller.navigate("read/$chapterId")
    fun downloads() = controller.navigate("downloads")
    fun webView(url: String, sourceId: Long) = controller.navigate("web/$sourceId?url=${Uri.encode(url)}")
    fun back() = controller.popBackStack()
}

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav não fornecida") }
