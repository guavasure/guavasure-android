package com.guavasure.sdk.payment

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.guavasure.sdk.security.PaymentUrlPolicy
import com.razorpay.Checkout
import com.razorpay.PaymentResultListener
import org.json.JSONObject

class PaymentLauncher(
    private val activity: Activity,
) : PaymentResultListener {
    private var pendingNative: ((PaymentLaunchResult) -> Unit)? = null

    fun launch(
        request: OpenPaymentRequest,
        onResult: (PaymentLaunchResult) -> Unit,
    ) {
        if (request.canOpenNativeCheckout) {
            launchNative(request, onResult)
            return
        }

        if (!PaymentUrlPolicy.isAllowed(request.url)) {
            onResult(PaymentLaunchResult.Failed)
            return
        }

        openCustomTab(request.url)
        onResult(PaymentLaunchResult.BrowserOpened)
    }

    private fun launchNative(
        request: OpenPaymentRequest,
        onResult: (PaymentLaunchResult) -> Unit,
    ) {
        if (pendingNative != null) {
            onResult(PaymentLaunchResult.NativeFailure("Payment already in progress", true))
            return
        }

        pendingNative = onResult
        val checkout = Checkout()
        checkout.setKeyID(request.key)

        val options = JSONObject().apply {
            put("name", request.name ?: "GuavaSure")
            put("currency", request.currency ?: "INR")
            request.description?.let { put("description", it) }
            request.orderId?.let { put("order_id", it) }
            request.subscriptionId?.let { put("subscription_id", it) }
            request.amountPaise?.takeIf { it > 0 }?.let { put("amount", it) }
            val prefill = JSONObject()
            request.prefillName?.let { prefill.put("name", it) }
            request.prefillEmail?.let { prefill.put("email", it) }
            request.prefillContact?.let { prefill.put("contact", it) }
            if (prefill.length() > 0) put("prefill", prefill)
            put("theme", JSONObject().put("color", "#E85A4F"))
        }

        checkout.open(activity, options)
    }

    private fun openCustomTab(url: String) {
        val uri = Uri.parse(url)
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(activity, uri)
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?) {
        val callback = pendingNative
        pendingNative = null
        callback?.invoke(
            PaymentLaunchResult.NativeSuccess(razorpayPaymentId.orEmpty()),
        )
    }

    override fun onPaymentError(code: Int, description: String?) {
        val callback = pendingNative
        pendingNative = null
        val cancelled = code == Checkout.PAYMENT_CANCELED ||
            description?.contains("cancel", ignoreCase = true) == true
        callback?.invoke(
            PaymentLaunchResult.NativeFailure(
                message = description ?: "Payment failed",
                cancelled = cancelled,
            ),
        )
    }
}
