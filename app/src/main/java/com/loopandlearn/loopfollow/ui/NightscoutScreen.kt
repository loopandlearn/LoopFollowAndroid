package com.loopandlearn.loopfollow.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors

/** The Nightscout site in a web view, signed in with the stored token. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightscoutScreen() {
    val colors = LocalAppColors.current
    val url by Storage.url.flow.collectAsStateWithLifecycle()
    val token by Storage.token.flow.collectAsStateWithLifecycle()
    val address = remember(url, token) { NightscoutUtils.constructURL(url, token, "", emptyMap())?.toString() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    if (address == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Set up Nightscout in Settings to see the site here.", color = colors.secondaryText)
        }
        return
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            webView?.clearCache(false)
            webView?.reload()
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
                        override fun onPageFinished(view: WebView?, url: String?) {
                            refreshing = false
                        }
                    }
                    loadUrl(address)
                    webView = this
                }
            },
            update = { if (it.url == null) it.loadUrl(address) },
            onRelease = {
                it.stopLoading()
                it.destroy()
            },
        )
    }
}
