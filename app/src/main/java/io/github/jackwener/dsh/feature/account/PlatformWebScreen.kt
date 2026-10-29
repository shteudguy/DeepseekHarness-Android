package io.github.jackwener.dsh.feature.account

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.account.AccountRepository
import io.github.jackwener.dsh.core.account.PlatformPage
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.DTopBar
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.HDivider
import io.github.jackwener.dsh.core.designsystem.IconBtn
import io.github.jackwener.dsh.core.platform.Browser

/**
 * DeepSeek Platform inside the app: the sign-in authorization page and plain Platform pages
 * (充值 / 用量 / account) in a WebView under a DSH top bar, so the flow never depends on how the
 * default browser treats Custom Tabs (some OEM browsers open them as a full browser task whose
 * Back lands on the browser's home page).
 *
 * Sign-in: Platform redirects to the engine's loopback callback (`http://127.0.0.1:<port>/oauth/callback`,
 * cleartext allowed only for loopback in network_security_config); the engine validates and
 * exchanges the code and answers with its completion page, which navigates to
 * `dsh-android://account/login?result=…` (as an `intent:` URL when the engine knows the package).
 * That navigation is intercepted here and closes the screen; the attempt settling closes it too
 * (AccountRepository.apply). Back walks the page history, then closes; closing cancels the attempt.
 *
 * Links that leave Platform on the sign-in page (privacy policy, terms, …), every new window,
 * and non-web schemes go to the system browser / the app that handles them.
 */
@Composable
fun PlatformWebScreen(page: PlatformPage, account: AccountRepository) {
    val c = Dsh.colors
    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val close = { account.closePage() }
    BackHandler { web?.takeIf { it.canGoBack() }?.goBack() ?: close() }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, web) {
        val w = web
        val obs = LifecycleEventObserver { _, e ->
            when (e) { Lifecycle.Event.ON_RESUME -> w?.onResume(); Lifecycle.Event.ON_PAUSE -> w?.onPause(); else -> Unit }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val title = if (page.signIn) stringResource(R.string.web_sign_in_title)
        else pageTitle?.takeUnless { it.isBlank() || it.startsWith("http") || it.startsWith("data:") } ?: stringResource(R.string.web_platform_title)
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding().navigationBarsPadding().imePadding()) {
        DTopBar(title, onBack = null) {
            IconBtn(R.drawable.dsw_close_outline, close, contentDescription = stringResource(R.string.close), iconSize = 18.dp, tint = c.labelPrimary)
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (progress in 1..99) Box(Modifier.fillMaxWidth(progress / 100f).fillMaxHeight().background(c.brandPrimary))
        }
        HDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(page) {
                AndroidView(
                    factory = { ctx ->
                        platformWebView(ctx, page, c.bgBase.toArgb(), account,
                            onProgress = { progress = it }, onTitle = { pageTitle = it }, onFailed = { failed = it },
                            onGone = { web = null; close() },
                        ).also { web = it; it.loadUrl(page.url) }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { w ->
                        if (web === w) web = null
                        w.stopLoading()
                        CookieManager.getInstance().flush()
                        w.destroy()
                    },
                )
            }
            if (failed) Column(Modifier.fillMaxSize().background(c.bgBase).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.web_load_failed), style = DshType.s14, color = c.labelSecondary, textAlign = TextAlign.Center)
                DButton(stringResource(R.string.retry), { failed = false; web?.reload() }, variant = BtnVariant.Outline)
            }
        }
    }
}

private const val TAG = "PlatformWeb"

@SuppressLint("SetJavaScriptEnabled")
private fun platformWebView(
    context: Context,
    page: PlatformPage,
    background: Int,
    account: AccountRepository,
    onProgress: (Int) -> Unit,
    onTitle: (String?) -> Unit,
    onFailed: (Boolean) -> Unit,
    onGone: () -> Unit,
): WebView = WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    setBackgroundColor(background)
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION") run { allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false }
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(true)
        setSupportZoom(false)
        // The mobile Chrome UA minus the WebView markers ("; wv", "Version/4.0"): Platform's login
        // (and the AWS WAF challenge in front of it) is meant for a regular mobile browser.
        userAgentString = userAgentString.replace("; wv", "").replace(Regex(" Version/[\\d.]+"), "")
    }
    CookieManager.getInstance().let { it.setAcceptCookie(true); it.setAcceptThirdPartyCookies(this, true) }

    /** true = handled here (the WebView must not load it). */
    fun route(uri: Uri, redirect: Boolean, gesture: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase()
        return when (scheme) {
            // http(s) always stays in this WebView. During sign-in the whole OAuth flow
            // (DeepSeek platform -> Google / AWS WAF) is bound to this WebView's cookies and to
            // server-side session ids (Google's signin/oauth/v3/consent?as=...&xsrf=...), so handing
            // a tapped link to another browser breaks the flow: Google answers 400 malformed.
            "http", "https" -> false
            APP_SCHEME -> { if (uri.host == "account") account.onSignInReturned(); true }
            "intent" -> { openIntentUrl(context, uri.toString(), account); true }
            "about", "data", "blob", "javascript" -> false
            else -> { openOutside(context, Intent(Intent.ACTION_VIEW, uri)); true }
        }
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.isForMainFrame && route(request.url, request.isRedirect, request.hasGesture())

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { onFailed(false) }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) { Log.i(TAG, "load failed: ${error.errorCode} ${error.description}"); onFailed(true) }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "renderer gone (crash=${detail.didCrash()})")
            onGone()
            return true
        }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)
        override fun onReceivedTitle(view: WebView, title: String?) = onTitle(title)

        /** target=_blank / window.open: catch the popup's first navigation and send it outside. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val popup = WebView(view.context)
            popup.settings.javaScriptEnabled = false
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    when (u.scheme?.lowercase()) {
                        // A sign-in popup (target=_blank) belongs to the same browser session: load it in
                        // the main WebView instead of another app, whose cookies it does not have.
                        "http", "https" -> view.post { runCatching { view.loadUrl(u.toString()) } }
                        "intent" -> openIntentUrl(context, u.toString(), account)
                        APP_SCHEME -> if (u.host == "account") account.onSignInReturned()
                        else -> openOutside(context, Intent(Intent.ACTION_VIEW, u))
                    }
                    v.post { v.destroy() }
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }
}

private const val APP_SCHEME = "dsh-android"

private fun isLoopback(host: String?) = host == "127.0.0.1" || host == "localhost"

/**
 * `intent:` URLs: the engine's completion page uses one for the app link; anything else is
 * started as a browsable intent with no explicit component, or its `S.browser_fallback_url`.
 */
private fun openIntentUrl(context: Context, url: String, account: AccountRepository) {
    val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
    if (intent.data?.scheme == APP_SCHEME || intent.scheme == APP_SCHEME) { account.onSignInReturned(); return }
    intent.component = null
    intent.selector = null
    if (!openOutside(context, intent)) intent.getStringExtra("browser_fallback_url")?.let { Browser.openExternal(context, it) }
}

private fun openOutside(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    Log.i(TAG, "no handler for ${intent.data?.scheme}: ${e.message}"); false
} catch (e: SecurityException) {
    Log.i(TAG, "not allowed: ${e.message}"); false
}
