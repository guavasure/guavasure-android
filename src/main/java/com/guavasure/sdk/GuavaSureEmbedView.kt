package com.guavasure.sdk

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.guavasure.sdk.bridge.BridgeConstants
import com.guavasure.sdk.bridge.BridgeDispatcher
import com.guavasure.sdk.bridge.CameraBridge
import com.guavasure.sdk.bridge.FilePickerBridge
import com.guavasure.sdk.internal.ChunkedJsDelivery
import com.guavasure.sdk.payment.PaymentLaunchResult
import com.guavasure.sdk.payment.PaymentLauncher
import com.guavasure.sdk.security.EmbedOriginPolicy
import kotlinx.coroutines.launch

/**
 * Drop-in GuavaSure embed WebView - mirrors [guavasure_flutter] behavior.
 *
 * Register in a [ComponentActivity], call [bind], then [loadEmbed].
 */
class GuavaSureEmbedView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var webView = WebView(context)
    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)

    private var activity: ComponentActivity? = null
    private var config: GuavaSureEmbedConfig? = null
    private var callbacks: GuavaSureEmbedCallbacks = GuavaSureEmbedCallbacks()

    private var allowedOrigins: Set<String> = emptySet()
    private var bridgeOriginTrusted = false
    private var awaitingBrowserPayment = false
    private var paymentOpenedAtMs = 0L
    private var collectPaymentInFlight = false

    private lateinit var paymentLauncher: PaymentLauncher
    private lateinit var filePickerBridge: FilePickerBridge
    private lateinit var cameraBridge: CameraBridge
    private lateinit var bridgeDispatcher: BridgeDispatcher

    init {
        progressBar.isIndeterminate = true
        progressBar.visibility = GONE
        addView(
            webView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            progressBar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )
    }

    fun bind(activity: ComponentActivity, lifecycleOwner: LifecycleOwner = activity) {
        this.activity = activity
        paymentLauncher = when (activity) {
            is GuavaSureEmbedActivity -> activity.paymentLauncher
            else -> PaymentLauncher(activity)
        }
        if (activity is GuavaSurePaymentHost && activity !is GuavaSureEmbedActivity) {
            activity.registerPaymentListener(paymentLauncher)
        }
        filePickerBridge = FilePickerBridge(activity, { webView }, activity.lifecycleScope)
        cameraBridge = CameraBridge(activity, { webView }, activity.lifecycleScope)
        filePickerBridge.register()
        cameraBridge.register()

        bridgeDispatcher = BridgeDispatcher(
            object : BridgeDispatcher.Callbacks {
                override fun onOpenPayment(request: com.guavasure.sdk.payment.OpenPaymentRequest) {
                    callbacks.onPaymentLinkOpened?.invoke()
                    paymentLauncher.launch(request) { result ->
                        when (result) {
                            is PaymentLaunchResult.NativeSuccess -> {
                                // Embed keeps polling quote status - no reload needed.
                            }
                            is PaymentLaunchResult.BrowserOpened -> {
                                awaitingBrowserPayment = true
                                paymentOpenedAtMs = System.currentTimeMillis()
                            }
                            is PaymentLaunchResult.NativeFailure -> {
                                ChunkedJsDelivery.signalNativePaymentEnded(
                                    webView,
                                    cancelled = result.cancelled,
                                )
                            }
                            PaymentLaunchResult.Failed -> {
                                ChunkedJsDelivery.signalNativePaymentEnded(webView, cancelled = true)
                            }
                        }
                    }
                }

                override fun onCollectPayment(request: CollectPaymentRequest) {
                    handleCollectPayment(request)
                }

                override fun onPickFile(requestId: String, accept: String) {
                    filePickerBridge.handlePickFile(requestId, accept)
                }

                override fun onCameraPermission(requestId: String) {
                    cameraBridge.handlePermissionRequest(requestId)
                }

                override fun onCapturePhoto(requestId: String) {
                    cameraBridge.handleCaptureRequest(requestId)
                }

                override fun onOpenCameraSettings() {
                    cameraBridge.openCameraSettings()
                }

                override fun onLogout() {
                    GuavaSureEmbedWarmup.cancel()
                    activity?.lifecycleScope?.launch {
                        callbacks.onLogout?.invoke()
                    }
                }

                override fun onGoBack() {
                    callbacks.onGoBack?.invoke()
                }

                override fun onChromeColor(background: String, statusBarStyle: String) {
                    applyChromeColor(background, statusBarStyle)
                }
            },
        )

        lifecycleOwner.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    handleResumeFromBrowserPayment()
                }
            },
        )

        configureWebView()
    }

    fun configure(config: GuavaSureEmbedConfig, callbacks: GuavaSureEmbedCallbacks = GuavaSureEmbedCallbacks()) {
        this.config = config
        this.callbacks = callbacks
        allowedOrigins = EmbedOriginPolicy.resolveAllowedOrigins(config)
        cameraBridge.setEnableCameraCapture(config.enableCameraCapture)
    }

    fun loadEmbed() {
        val activity = activity ?: error("Call bind() before loadEmbed()")
        if (activity !is GuavaSurePaymentHost) {
            error(
                "Host activity must extend GuavaSureEmbedActivity or implement GuavaSurePaymentHost " +
                    "so Razorpay payment callbacks are delivered.",
            )
        }
        val config = config ?: error("Call configure() before loadEmbed()")

        activity.lifecycleScope.launch {
            try {
                val warmed = GuavaSureEmbedWarmup.claimIfMatching(config)
                if (warmed != null) {
                    warmed.onLoadError = { message ->
                        callbacks.onLoadError?.invoke(message)
                    }
                    adoptWarmupSession(warmed)
                    return@launch
                }

                val token = resolvePartnerAuthToken(config)
                val url = GuavaSureUrlBuilder.buildEmbedUrl(config, token)
                webView.loadUrl(url)
            } catch (error: Exception) {
                callbacks.onLoadError?.invoke(error.message ?: "Failed to load embed")
            }
        }
    }

    fun reloadEmbedHome() {
        val activity = activity ?: return
        val config = config ?: return

        activity.lifecycleScope.launch {
            val token = resolvePartnerAuthToken(config)
            val url = GuavaSureUrlBuilder.buildEmbedUrl(config, token)
            webView.loadUrl(url)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolvePartnerAuthToken(config: GuavaSureEmbedConfig): String? {
        config.partnerAuthTokenProvider?.let { return it() }
        return config.partnerAuthToken
    }

    private fun adoptWarmupSession(warmed: GuavaSureEmbedWarmupSession) {
        swapToWebView(warmed.webView)
        bridgeOriginTrusted = warmed.bridgeOriginTrusted
        if (warmed.loadFinished) {
            progressBar.visibility = GONE
        }
        val queued = warmed.attachDispatcher { message ->
            if (bridgeOriginTrusted) {
                bridgeDispatcher.dispatch(message)
            }
        }
        for (message in queued) {
            if (bridgeOriginTrusted) {
                bridgeDispatcher.dispatch(message)
            }
        }
    }

    private fun swapToWebView(newWebView: WebView) {
        if (webView === newWebView) return
        removeView(webView)
        webView.destroy()
        (newWebView.parent as? android.view.ViewGroup)?.removeView(newWebView)
        webView = newWebView
        addView(webView, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
    }

    private fun handleResumeFromBrowserPayment() {
        if (!awaitingBrowserPayment) return

        val elapsed = System.currentTimeMillis() - paymentOpenedAtMs
        if (elapsed < 2000) return

        awaitingBrowserPayment = false
    }

    private fun handleCollectPayment(request: CollectPaymentRequest) {
        val activity = activity ?: return
        if (collectPaymentInFlight) return

        val handler = callbacks.partnerPaymentHandler
        if (handler == null) {
            webView.evaluateJavascript(
                bridgeDispatcher.buildPaymentCancelledScript(request.intentId, request.quoteId),
                null,
            )
            return
        }

        collectPaymentInFlight = true
        activity.lifecycleScope.launch {
            try {
                val result = handler(request)
                val script = when (result) {
                    PartnerPaymentResult.CONFIRMED ->
                        bridgeDispatcher.buildPaymentConfirmedScript(request.intentId, request.quoteId)
                    PartnerPaymentResult.CANCELLED ->
                        bridgeDispatcher.buildPaymentCancelledScript(request.intentId, request.quoteId)
                }
                webView.evaluateJavascript(script, null)
            } catch (_: Exception) {
                webView.evaluateJavascript(
                    bridgeDispatcher.buildPaymentCancelledScript(request.intentId, request.quoteId),
                    null,
                )
            } finally {
                collectPaymentInFlight = false
            }
        }
    }

    private fun configureWebView() {
        GuavaSureEmbedWarmup.applyBaseSettings(webView)

        webView.removeJavascriptInterface(BridgeConstants.CHANNEL_NAME)
        webView.addJavascriptInterface(
            GuavasureBridgeJsInterface { message ->
                if (!bridgeOriginTrusted) return@GuavasureBridgeJsInterface
                post { bridgeDispatcher.dispatch(message) }
            },
            BridgeConstants.CHANNEL_NAME,
        )

        webView.webChromeClient = filePickerBridge.createWebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return true
                if (EmbedOriginPolicy.isAllowed(uri, allowedOrigins)) return false

                openExternalLink(uri)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                updateBridgeTrust(url)
                progressBar.visibility = VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                updateBridgeTrust(url)
                progressBar.visibility = GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    callbacks.onLoadError?.invoke(error?.description?.toString() ?: "WebView error")
                }
            }
        }
    }

    private fun updateBridgeTrust(url: String?) {
        val uri = url?.let(Uri::parse)
        bridgeOriginTrusted = uri != null && EmbedOriginPolicy.isAllowed(uri, allowedOrigins)
    }

    private fun openExternalLink(uri: Uri) {
        val activity = activity ?: return
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun applyChromeColor(background: String, statusBarStyle: String) {
        val activity = activity ?: return
        val color = runCatching { Color.parseColor(background) }.getOrElse { return }
        setBackgroundColor(color)
        activity.window.statusBarColor = color
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = statusBarStyle == "dark"
        ViewCompat.requestApplyInsets(this)
    }

    private class GuavasureBridgeJsInterface(
        private val onMessage: (String) -> Unit,
    ) {
        @JavascriptInterface
        fun postMessage(message: String) {
            onMessage(message)
        }
    }

    companion object {
        /**
         * Optional preload from a previous screen. Use the same [GuavaSureEmbedConfig]
         * as [configure] / [loadEmbed].
         */
        fun warmup(activity: ComponentActivity, config: GuavaSureEmbedConfig) {
            GuavaSureEmbedWarmup.start(activity, config)
        }

        /** Clears any warmup session (for example on partner logout). */
        fun cancelWarmup() {
            GuavaSureEmbedWarmup.cancel()
        }
    }
}
