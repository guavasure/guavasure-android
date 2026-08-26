package com.guavasure.sdk.security

import android.net.Uri
import java.net.URI

object PaymentUrlPolicy {
    fun isAllowed(url: String): Boolean {
        if (url.contains('\\')) return false

        val host = try {
            URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false

        val uri = Uri.parse(url)
        if (uri.scheme?.lowercase() != "https") return false
        if (uri.host?.lowercase() != host) return false

        if (host == "rzp.io" || host.endsWith(".rzp.io")) return true
        if (host == "razorpay.me" || host.endsWith(".razorpay.me")) return true
        if (host == "razorpay.com" || host.endsWith(".razorpay.com")) return true
        return false
    }
}
