package com.guavasure.sdk.payment

/**
 * Retains an in-flight native Razorpay callback across host activity recreation.
 * The embed WebView typically survives configuration changes; this holder keeps
 * the completion handler reachable until Razorpay delivers a result.
 */
internal object PaymentSessionHolder {
    var pendingNative: ((PaymentLaunchResult) -> Unit)? = null
        private set

    fun begin(onResult: (PaymentLaunchResult) -> Unit) {
        pendingNative = onResult
    }

    fun complete(result: PaymentLaunchResult) {
        val callback = pendingNative
        pendingNative = null
        callback?.invoke(result)
    }

    fun clear() {
        pendingNative = null
    }
}
