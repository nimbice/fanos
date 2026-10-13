package io.github.nimbice.fanos.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.core.network.BrowserUserAgent
import kotlinx.serialization.Serializable

@Serializable
data class BrowserRoute(val url: String)

/**
 * The site in a browser view inside the app. Its cookies are the ones the app's requests use, so
 * passing a site's bot check here (Cloudflare's "Just a moment") lets the novel or chapter load when
 * the user goes back and tries again. It's where the user signs in to a site, too: it goes by the user agent of a
 * browser rather than a view in an app, as Google's sign-in asks, and a window a page opens (Google's, Apple's) opens
 * over it, able to tell the page that opened it how the sign-in went.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(
    url: String,
    onClose: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(url) }
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    // A window the page opened (a sign-in's), over the page until it closes itself or Back closes it.
    var popup by remember { mutableStateOf<WebView?>(null) }
    val uriHandler = LocalUriHandler.current

    fun closePopup() {
        popup = null
        webView?.let { current = it.url ?: current }
    }

    val pageClient =
        remember {
            object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    loading = true
                    current = url
                }

                override fun onPageFinished(view: WebView, url: String) {
                    loading = false
                    title = view.title.orEmpty()
                    current = url
                    canGoBack = webView?.canGoBack() == true
                    CookieManager.getInstance().flush()
                }
            }
        }

    val windows =
        remember {
            object : WebChromeClient() {
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                    val window = browserView(view.context).apply { webViewClient = pageClient }
                    window.webChromeClient = this
                    popup = window
                    transport.webView = window
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView) {
                    if (window === popup) closePopup()
                }
            }
        }

    BackHandler(enabled = canGoBack) { webView?.goBack() }
    BackHandler(enabled = popup != null) { popup?.let { if (it.canGoBack()) it.goBack() else closePopup() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title.ifEmpty { "Loading…" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
                actions = {
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
                    IconButton(onClick = { uriHandler.openUri(current) }) { Icon(ReaderIcons.Globe, contentDescription = "Open in another browser") }
                },
            )
        },
    ) { padding ->
        // The page makes room for the keyboard in one step, at its full height: the WebView scrolls the field being typed
        // in into view as it's resized for the keyboard, and resized a step at a time with the keyboard's slide, it does
        // so at the first step, while the field is still in view, and the keyboard then covers it.
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).windowInsetsPadding(WindowInsets.imeAnimationTarget)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        browserView(context).apply {
                            webViewClient = pageClient
                            webChromeClient = windows
                            // A web address only: nothing else an extension's error might name is for a browser.
                            if (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)) loadUrl(url)
                            webView = this
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxSize(),
                )
                popup?.let { window ->
                    key(window) { AndroidView(factory = { window }, onRelease = { it.destroy() }, modifier = Modifier.fillMaxSize()) }
                }
            }
        }
    }
}

/**
 * A view for the site: its scripts and storage on, its cookies the app's, and a window the page opens from a tap (a
 * sign-in's) opened. A script can't open one by itself, as an ad's would.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun browserView(context: Context): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(true)
        BrowserUserAgent.applyTo(this)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }
