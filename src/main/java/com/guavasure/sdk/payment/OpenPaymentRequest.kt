package com.guavasure.sdk.payment

data class OpenPaymentRequest(
    val url: String,
    val quoteId: String? = null,
    val orderId: String? = null,
    val subscriptionId: String? = null,
    val key: String? = null,
    val amountPaise: Int? = null,
    val currency: String? = null,
    val name: String? = null,
    val description: String? = null,
    val prefillName: String? = null,
    val prefillEmail: String? = null,
    val prefillContact: String? = null,
) {
    val canOpenNativeCheckout: Boolean
        get() = !key.isNullOrBlank() &&
            (!orderId.isNullOrBlank() || !subscriptionId.isNullOrBlank())
}

sealed class PaymentLaunchResult {
    data class NativeSuccess(val paymentId: String) : PaymentLaunchResult()
    data class NativeFailure(val message: String, val cancelled: Boolean) : PaymentLaunchResult()
    data object BrowserOpened : PaymentLaunchResult()
    data object Failed : PaymentLaunchResult()
}
