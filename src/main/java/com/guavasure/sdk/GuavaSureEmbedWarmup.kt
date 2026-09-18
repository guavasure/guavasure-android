package com.guavasure.sdk

import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.guavasure.sdk.bridge.BridgeConstants
import com.guavasure.sdk.security.EmbedOriginPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal object GuavaSureEmbedWarmupKey {
    fun build(config: GuavaSureEmbedConfig): String {
        val override = config.embedUrlOverride?.trim().orEmpty()
        val localHost = config.localDevHost?.trim().orEmpty()
        val origin = when {
            override.isNotEmpty() -> override
            localHost.isNotEmpty() -> "${config.localDevScheme}://$localHost:${config.localDevPort}"
            else -> config.embedBaseUrl.trim()
        }
        val externalId = config.partnerExternalId?.trim().orEmpty()
        val authToken = config.partnerAuthToken?.trim().orEmpty()
        val env = if (config.environment == GuavaSureEnvironment.SANDBOX) "sandbox" else "production"
        val camera = if (config.enableCameraCapture) "1" else "0"
        val themeConfigId = config.themeConfigId?.trim().orEmpty()
        val claimMode = if (config.claimMode) "1" else "0"
        return listOf(
            config.partnerId,
            env,
            origin,
            config.platform,
            externalId,
            authToken,
            camera,
            themeConfigId,
            claimMode,
        ).joinToString("|")
    }
}

internal class GuavaSureEmbedWarmupSession(
    val config: GuavaSureEmbedConfig,
    val matchKey: String,
    val webView: WebView,
    val hostContainer: FrameLayout,
    val allowedOrigins: Set<String>,
) {
    var loadFinished = false
    var bridgeOriginTrusted = false
    var chromeBackground: String? = null
    var chromeStatusBarStyle: String? = null
    private val queuedMessages = mutableListOf<String>()
    var claimed = false
    var loadError: String? = null
    var loadJob: Job? = null
    var onLoadError: ((String) -> Unit)? = null
    @Volatile
    private var messageDispatcher: ((String) -> Unit)? = null

    fun offerBridgeMessage(message: String) {
        val dispatcher: ((String) -> Unit)?
        synchronized(queuedMessages) {
            dispatcher = messageDispatcher
            if (dispatcher == null) {
                queuedMessages.add(message)
            }
        }
        dispatcher?.invoke(message)
    }

    fun discardQueuedMessages() {
        synchronized(queuedMessages) {
            queuedMessages.clear()
        }
    }

    fun attachDispatcher(handler: (String) -> Unit): List<String> {
        synchronized(queuedMessages) {
            messageDispatcher = handler
            val snapshot = queuedMessages.toList()
            queuedMessages.clear()
            return snapshot
        }
    }
}

internal object GuavaSureEmbedWarmup {
    private var session: GuavaSureEmbedWarmupSession? = null

    fun start(activity: ComponentActivity, config: GuavaSureEmbedConfig) {
        cancel()

        val matchKey = GuavaSureEmbedWarmupKey.build(config)
        val allowedOrigins = EmbedOriginPolicy.resolveAllowedOrigins(config)
        val themedContext = ContextThemeWrapper(
            activity.applicationContext,
            android.R.style.Theme_DeviceDefault,
        )
        val webView = WebView(themedContext)
        applyBaseSettings(webView)

        val hostContainer = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            visibility = FrameLayout.INVISIBLE
        }
        activity.addContentView(
            hostContainer,
            FrameLayout.LayoutParams(1, 1),
        )
        hostContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val warmupSession = GuavaSureEmbedWarmupSession(
            config = config,
            matchKey = matchKey,
            webView = webView,
            hostContainer = hostContainer,
            allowedOrigins = allowedOrigins,
        )
        session = warmupSession

        val hostLifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY && session === warmupSession && !warmupSession.claimed) {
                cancel()
            }
        }
        activity.lifecycle.addObserver(hostLifecycleObserver)

        webView.addJavascriptInterface(
            WarmupBridgeJsInterface { message ->
                webView.post {
                    if (!warmupSession.bridgeOriginTrusted) return@post
                    warmupSession.offerBridgeMessage(message)
                }
            },
            BridgeConstants.CHANNEL_NAME,
        )

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return true
                if (EmbedOriginPolicy.isAllowed(uri, warmupSession.allowedOrigins)) return false
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                updateBridgeTrust(warmupSession, url)
                warmupSession.loadFinished = true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                updateBridgeTrust(warmupSession, url)
                warmupSession.loadFinished = false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                val description = error?.description?.toString() ?: "WebView error"
                warmupSession.loadError = description
                if (warmupSession.claimed) {
                    warmupSession.onLoadError?.invoke(description)
                }
            }
        }

        warmupSession.loadJob = activity.lifecycleScope.launch {
            try {
                val token = resolvePartnerAuthToken(config)
                if (!warmupSession.shouldContinueLoad()) return@launch
                val url = GuavaSureUrlBuilder.buildEmbedUrl(config, token)
                webView.loadUrl(url)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val description = error.message ?: "Failed to load embed"
                warmupSession.loadError = description
                if (warmupSession.claimed) {
                    warmupSession.onLoadError?.invoke(description)
                } else if (session === warmupSession) {
                    cancel()
                }
            }
        }
    }

    fun claimIfMatching(config: GuavaSureEmbedConfig): GuavaSureEmbedWarmupSession? {
        val current = session
        if (current == null || current.claimed) return null
        if (current.matchKey != GuavaSureEmbedWarmupKey.build(config)) return null
        if (current.loadError != null) {
            cancel()
            return null
        }
        current.claimed = true
        session = null
        (current.hostContainer.parent as? ViewGroup)?.removeView(current.hostContainer)
        return current
    }

    fun cancel() {
        val current = session ?: return
        current.loadJob?.cancel()
        (current.hostContainer.parent as? ViewGroup)?.removeView(current.hostContainer)
        current.webView.destroy()
        session = null
    }

    private fun GuavaSureEmbedWarmupSession.shouldContinueLoad(): Boolean {
        return claimed || session === this
    }

    private fun updateBridgeTrust(session: GuavaSureEmbedWarmupSession, url: String?) {
        val uri = url?.let(android.net.Uri::parse)
        session.bridgeOriginTrusted =
            uri != null && EmbedOriginPolicy.isAllowed(uri, session.allowedOrigins)
        if (!session.bridgeOriginTrusted) {
            session.discardQueuedMessages()
        }
    }

    private suspend fun resolvePartnerAuthToken(config: GuavaSureEmbedConfig): String? {
        config.partnerAuthTokenProvider?.let { return it() }
        return config.partnerAuthToken
    }

    fun applyBaseSettings(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = true
            allowContentAccess = true
            userAgentString = CHROME_MOBILE_USER_AGENT
        }
    }

    private class WarmupBridgeJsInterface(
        private val onMessage: (String) -> Unit,
    ) {
        @JavascriptInterface
        fun postMessage(message: String) {
            onMessage(message)
        }
    }

    private const val CHROME_MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
