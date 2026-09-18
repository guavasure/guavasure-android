package com.guavasure.sdk

enum class GuavaSureEnvironment {
    PRODUCTION,
    SANDBOX,
}

/**
 * Configuration for [GuavaSureEmbedView].
 *
 * @param partnerAuthTokenProvider Preferred - mint short-lived JWTs from your backend.
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
    /**
     * When true, the SDK handles native camera bridge messages if the host app has
     * already granted `CAMERA` runtime permission. The SDK never prompts for camera
     * access - partners opt in and request permission in their own app flow.
     */
    val enableCameraCapture: Boolean = false,
    /**
     * Alternate partner customization from Embed → Theme configs.
     * When set, the embed URL includes `theme-config-id`. Unset uses the default.
     */
    val themeConfigId: String? = null,
    /**
     * When true, the embed URL includes `claim-mode=true` so home is a
     * claims-focused Policy Details screen instead of the full portal dashboard.
     */
    val claimMode: Boolean = false,
) {
    override fun toString(): String {
        return "GuavaSureEmbedConfig(" +
            "partnerId=$partnerId, " +
            "environment=$environment, " +
            "partnerAuthToken=${if (partnerAuthToken == null) "null" else "[REDACTED]"}, " +
            "partnerAuthTokenProvider=${if (partnerAuthTokenProvider == null) "null" else "[REDACTED]"}, " +
            "partnerExternalId=$partnerExternalId, " +
            "embedBaseUrl=$embedBaseUrl, " +
            "embedUrlOverride=$embedUrlOverride, " +
            "platform=$platform, " +
            "localDevHost=$localDevHost, " +
            "localDevPort=$localDevPort, " +
            "localDevScheme=$localDevScheme, " +
            "enableCameraCapture=$enableCameraCapture, " +
            "themeConfigId=$themeConfigId, " +
            "claimMode=$claimMode" +
            ")"
    }

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
