package com.mangaverse.mobile

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.mangaverse.mobile.data.CoverFetcher
import com.mangaverse.mobile.data.CoverKeyer
import com.mangaverse.mobile.data.Db
import com.mangaverse.mobile.data.DownloadManager
import com.mangaverse.mobile.data.PageFetcher
import com.mangaverse.mobile.data.PageKeyer
import com.mangaverse.mobile.ext.ExtensionManager
import com.mangaverse.mobile.update.AppUpdater
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Path.Companion.toOkioPath
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType

class App : Application(), SingletonImageLoader.Factory {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var network: NetworkHelper
    lateinit var db: Db
    lateinit var extensions: ExtensionManager
    lateinit var downloads: DownloadManager
    lateinit var updater: AppUpdater

    override fun onCreate() {
        super.onCreate()
        network = NetworkHelper(this)

        // O que as extensões pedem com Injekt.get<…>()
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        Injekt.addSingleton(fullType<Application>(), this)
        Injekt.addSingleton(fullType<Context>(), this)
        Injekt.addSingleton(fullType<NetworkHelper>(), network)
        Injekt.addSingleton(fullType<Json>(), json)
        Injekt.addSingleton(fullType<ProtoBuf>(), ProtoBuf)
        Injekt.addSingletonFactory(fullType<JavaScriptEngine>()) { JavaScriptEngine(this) }

        db = Db(this)
        extensions = ExtensionManager(this, network, scope)
        downloads = DownloadManager(this, extensions, scope)
        extensions.init()
        updater = AppUpdater(this, network, scope)
        updater.checkOnStart()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { network.client }))
                add(CoverFetcher.Factory(extensions, network))
                add(PageFetcher.Factory(extensions))
                add(CoverKeyer)
                add(PageKeyer)
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images").toOkioPath()).maxSizeBytes(512L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
}

val Context.app: App get() = applicationContext as App
