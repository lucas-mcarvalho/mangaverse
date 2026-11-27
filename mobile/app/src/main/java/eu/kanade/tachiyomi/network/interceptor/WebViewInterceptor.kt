package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Base dos interceptadores que resolvem desafios abrindo uma WebView invisível. Adaptado do Mihon (Apache 2.0). */
abstract class WebViewInterceptor(
    private val context: Context,
    private val defaultUserAgentProvider: () -> String,
) : Interceptor {

    private val initWebView by lazy {
        try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {
            // WebView sendo atualizada; só fica mais lenta na primeira vez
        }
    }

    abstract fun shouldIntercept(response: Response): Boolean

    abstract fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!shouldIntercept(response)) return response
        if (!prepareWebView()) return response
        return intercept(chain, request, response)
    }

    protected fun prepareWebView(): Boolean {
        val hasWebView = runCatching { context.packageManager.hasSystemFeature("android.software.webview") }.getOrDefault(false)
        if (!hasWebView) return false
        initWebView
        return true
    }

    fun CountDownLatch.awaitFor30Seconds() {
        await(30, TimeUnit.SECONDS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(request: Request): WebView {
        return WebView(context).apply {
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = request.header("User-Agent") ?: defaultUserAgentProvider()
            }
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
    }
}
