package eu.kanade.tachiyomi.animeextension.en.aniwatch

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MegaPlayWebViewResolver(private val globalHeaders: Headers) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(embedUrl: String, referer: String): Result? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result?>(null)
        var webView: WebView? = null

        handler.post {
            val view = WebView(context)
            webView = view

            with(view.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = globalHeaders["User-Agent"]
                mediaPlaybackRequiresUserGesture = false
            }

            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val req = request ?: return null
                    val url = req.url.toString()

                    if (MEDIA_REGEX.containsMatchIn(url) && result.get() == null) {
                        val capturedHeaders = Headers.Builder().apply {
                            req.requestHeaders.forEach { (name, value) ->
                                if (name.isNotBlank() && value.isNotBlank()) {
                                    runCatching { add(name, value) }
                                }
                            }
                            if (get("Referer").isNullOrBlank()) set("Referer", embedUrl)
                            if (get("User-Agent").isNullOrBlank()) {
                                globalHeaders["User-Agent"]?.let { set("User-Agent", it) }
                            }
                        }.build()

                        if (result.compareAndSet(null, Result(url, capturedHeaders))) {
                            latch.countDown()
                        }
                    }

                    return super.shouldInterceptRequest(view, req)
                }
            }

            val extraHeaders = mutableMapOf("Referer" to referer)
            globalHeaders["User-Agent"]?.let { extraHeaders["User-Agent"] = it }
            view.loadUrl(embedUrl, extraHeaders)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        return result.get()
    }

    data class Result(
        val url: String,
        val headers: Headers,
    )

    companion object {
        private const val TIMEOUT_SEC = 25L
        private val MEDIA_REGEX = Regex("""\.(m3u8|mp4)(?:[?#]|$)""", RegexOption.IGNORE_CASE)
    }
}
