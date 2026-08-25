package com.guavasure.sdk.security

import android.net.Uri
import com.guavasure.sdk.GuavaSureEmbedConfig

object EmbedOriginPolicy {
    fun resolveAllowedOrigins(config: GuavaSureEmbedConfig): Set<String> {
        config.embedUrlOverride?.takeIf { it.isNotBlank() }?.let {
            return setOf(normalizeOrigin(Uri.parse(it)))
        }
        val localHost = config.localDevHost?.trim().orEmpty()
        if (localHost.isNotEmpty()) {
            return setOf(
                normalizeOrigin(
                    Uri.Builder()
                        .scheme(config.localDevScheme)
                        .encodedAuthority("${localHost}:${config.localDevPort}")
                        .build(),
                ),
            )
        }
        return setOf(normalizeOrigin(Uri.parse(config.embedBaseUrl)))
    }

    fun isAllowed(uri: Uri, allowedOrigins: Set<String>): Boolean {
        if (uri.host.isNullOrBlank()) return false
        return allowedOrigins.contains(normalizeOrigin(uri))
    }

    private fun normalizeOrigin(uri: Uri): String {
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val port = if (uri.port != -1) uri.port else defaultPort(scheme)
        return if (isDefaultPort(scheme, port)) {
            "$scheme://$host"
        } else {
            "$scheme://$host:$port"
        }
    }

    private fun defaultPort(scheme: String): Int = when (scheme) {
        "https" -> 443
        "http" -> 80
        else -> 0
    }

    private fun isDefaultPort(scheme: String, port: Int): Boolean = port == defaultPort(scheme)
}
