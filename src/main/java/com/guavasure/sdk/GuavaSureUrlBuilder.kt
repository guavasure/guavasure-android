package com.guavasure.sdk

import android.net.Uri

object GuavaSureUrlBuilder {
    fun buildEmbedUrl(config: GuavaSureEmbedConfig, partnerAuthToken: String?): String {
        config.embedUrlOverride?.takeIf { it.isNotBlank() }?.let { return it }

        val externalId = config.partnerExternalId?.trim().orEmpty()
        val themeConfigId = config.themeConfigId?.trim().orEmpty()
        val params = linkedMapOf(
            "partner" to config.partnerId,
            "platform" to config.platform,
        )
        if (config.environment == GuavaSureEnvironment.SANDBOX) {
            params["env"] = "sandbox"
        }
        if (!partnerAuthToken.isNullOrBlank()) {
            params["partner-auth-token"] = partnerAuthToken
        }
        if (externalId.isNotEmpty()) {
            params["partner-external-id"] = externalId
            if (!config.lockPet) {
                params["partner-pet-lock"] = "false"
            }
        }
        if (themeConfigId.isNotEmpty()) {
            params["theme-config-id"] = themeConfigId
        }
        if (config.enableCameraCapture) {
            params["enable-camera-capture"] = "true"
        }
        if (config.claimMode) {
            params["claim-mode"] = "true"
        }
        config.theme?.let { params["theme"] = it.param }

        val localHost = config.localDevHost?.trim().orEmpty()
        if (localHost.isNotEmpty()) {
            return Uri.Builder()
                .scheme(config.localDevScheme)
                .encodedAuthority("${localHost}:${config.localDevPort}")
                .apply { params.forEach { (k, v) -> appendQueryParameter(k, v) } }
                .build()
                .toString()
        }

        val base = Uri.parse(config.embedBaseUrl)
        val merged = base.queryParameterNames.associateWith { base.getQueryParameter(it)!! }.toMutableMap()
        merged.putAll(params)
        return base.buildUpon()
            .clearQuery()
            .apply { merged.forEach { (k, v) -> appendQueryParameter(k, v) } }
            .build()
            .toString()
    }

    fun stripPartnerAuthToken(url: String): String {
        val uri = Uri.parse(url)
        if (!uri.queryParameterNames.contains("partner-auth-token")) return url
        return uri.buildUpon()
            .clearQuery()
            .apply {
                uri.queryParameterNames
                    .filter { it != "partner-auth-token" }
                    .forEach { key ->
                        uri.getQueryParameters(key).forEach { value ->
                            appendQueryParameter(key, value)
                        }
                    }
            }
            .build()
            .toString()
    }
}
