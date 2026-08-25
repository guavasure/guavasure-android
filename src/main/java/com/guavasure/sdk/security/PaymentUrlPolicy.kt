package com.guavasure.sdk.security

import android.net.Uri

object PaymentUrlPolicy {
    fun isAllowed(url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (host == "rzp.io" || host == "razorpay.me") return true
        if (host == "razorpay.com" || host.endsWith(".razorpay.com")) return true
        return false
    }
}
