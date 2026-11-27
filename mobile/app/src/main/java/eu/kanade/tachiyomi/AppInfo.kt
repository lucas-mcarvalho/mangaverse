package eu.kanade.tachiyomi

import com.mangaverse.mobile.BuildConfig

/** Usado pelas extensões. Adaptado do Mihon (Apache 2.0). */
@Suppress("UNUSED")
object AppInfo {
    fun getVersionCode(): Int = BuildConfig.VERSION_CODE

    fun getVersionName(): String = BuildConfig.VERSION_NAME

    fun getSupportedImageMimeTypes(): List<String> =
        listOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif", "image/heif", "image/jxl")
}
