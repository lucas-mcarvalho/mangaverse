package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Resolve o desafio "Just a moment..." do Cloudflare numa WebView invisível e reaproveita o cookie `cf_clearance`.
 * Adaptado do Mihon (Apache 2.0); só funciona quando o desafio não exige interação.
 */
class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)
    private val hostLocks = ConcurrentHashMap<String, ReentrantReadWriteLock>()

    override fun shouldIntercept(response: Response): Boolean =
        response.header("cf-mitigated") == "challenge" && response.header("Server") in SERVER_CHECK

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val lock = hostLocks.computeIfAbsent(request.url.host) { ReentrantReadWriteLock() }

        val (response, clearance) = lock.read {
            val response = chain.proceed(request)
            if (!shouldIntercept(response)) return response
            response to clearance(request.url)
        }

        lock.write {
            if (isCleared(request.url, clearance)) {
                response.close()
            } else {
                if (!prepareWebView()) return response
                solve(request, response, clearance)
            }
        }
        return chain.proceed(request)
    }

    override fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response {
        solve(request, response, clearance(request.url))
        return chain.proceed(request)
    }

    private fun clearance(url: HttpUrl): String? = cookieManager.get(url).firstOrNull { it.name == "cf_clearance" }?.value

    private fun isCleared(url: HttpUrl, previous: String?): Boolean {
        val c = clearance(url)
        return !c.isNullOrBlank() && c != previous
    }

    private fun solve(request: Request, response: Response, clearance: String?) {
        try {
            response.close()
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            resolveWithWebView(request, clearance)
        } catch (e: CloudflareBypassException) {
            throw IOException("Não foi possível passar pelo Cloudflare. Abra o site no navegador do app e tente de novo.", e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    private fun resolveWithWebView(originalRequest: Request, originalClearance: String?) {
        val latch = CountDownLatch(1)
        var webview: WebView? = null
        var challengeFound = false
        var bypassed = false

        val url = originalRequest.url
        val challengeUrl = if (originalRequest.method == "GET") url.toString()
        else url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
        val headers = originalRequest.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
            .filterKeys { !it.equals("user-agent", true) }

        executor.execute {
            val wv = createWebView(originalRequest)
            webview = wv
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    if (isCleared(url, originalClearance)) {
                        bypassed = true
                        latch.countDown()
                    }
                    if (finishedUrl == challengeUrl && !challengeFound) latch.countDown()
                }

                override fun onReceivedHttpError(view: WebView?, req: WebResourceRequest?, error: WebResourceResponse?) {
                    if (req?.isForMainFrame == true) {
                        if (error?.responseHeaders?.get("cf-mitigated") == "challenge") challengeFound = true
                        else latch.countDown()
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    Log.e("Cloudflare", "WebView caiu durante o desafio de $challengeUrl")
                    latch.countDown()
                    return true
                }
            }
            wv.loadUrl(challengeUrl, headers)
        }

        latch.awaitFor30Seconds()

        executor.execute {
            webview?.run {
                stopLoading()
                destroy()
            }
        }

        if (!bypassed) {
            cookieManager.remove(url, COOKIE_NAMES, 0)
            throw CloudflareBypassException()
        }
    }
}

private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")

private class CloudflareBypassException : Exception()
