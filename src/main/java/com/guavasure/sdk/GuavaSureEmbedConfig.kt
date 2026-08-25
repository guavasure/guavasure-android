package com.guavasure.sdk

enum class GuavaSureEnvironment {
    PRODUCTION,
    SANDBOX,
}

/**
 * Configuration for [GuavaSureEmbedView].
 *
 * @param partnerAuthTokenProvider Preferred — mint short-lived JWTs from your backend.
 * @param partnerAuthToken Static JWT fallback (dev only).
 */
data class GuavaSureEmbedConfig(
    val partnerId: String,
    val environment: GuavaSureEnvironment = GuavaSureEnvironment.PRODUCTION,
    val partnerAuthToken: String? = null,
    val partnerAuthTokenProvider: (suspend () -> String?)? = null,
    val partnerExternalId: String? = null,
    val embedBaseUrl: String = DEFAULT_EMBED_BASE_URL,
    val embedUrlOverride: String? = null,
    val platform: String = APP_WEBVIEW_PLATFORM,
    val localDevHost: String? = null,
    val localDevPort: Int = 5173,
    val localDevScheme: String = "http",
) {
    companion object {
        const val DEFAULT_EMBED_BASE_URL = "https://embed.guavasure.com"
        const val APP_WEBVIEW_PLATFORM = "app-webview"
    }
}

typealias PartnerAuthTokenProvider = suspend () -> String?

typealias PartnerPaymentHandler = suspend (CollectPaymentRequest) -> PartnerPaymentResult

data class CollectPaymentRequest(
    val intentId: String,
    val quoteId: String,
    val amount: Number,
    val currency: String,
    val planLabel: String,
)

enum class PartnerPaymentResult {
    CONFIRMED,
    CANCELLED,
}

data class GuavaSureEmbedCallbacks(
    val partnerPaymentHandler: PartnerPaymentHandler? = null,
    val onPaymentLinkOpened: (() -> Unit)? = null,
    val onLogout: (suspend () -> Unit)? = null,
    val onGoBack: (() -> Unit)? = null,
    val onLoadError: ((String) -> Unit)? = null,
)
