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

        try {
            openCustomTab(request.url)
            onResult(PaymentLaunchResult.BrowserOpened)
        } catch (_: Exception) {
            onResult(PaymentLaunchResult.Failed)
        }
    }

    private fun launchNative(
        request: OpenPaymentRequest,
        onResult: (PaymentLaunchResult) -> Unit,
    ) {
        if (PaymentSessionHolder.pendingNative != null) {
            onResult(PaymentLaunchResult.NativeFailure("Payment already in progress", true))
            return
        }

        PaymentSessionHolder.begin(onResult)
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

        try {
            checkout.open(activity, options)
        } catch (error: Exception) {
            PaymentSessionHolder.clear()
            onResult(
                PaymentLaunchResult.NativeFailure(
                    message = error.message ?: "Failed to open payment",
                    cancelled = false,
                ),
            )
        }
    }

    private fun openCustomTab(url: String) {
        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(activity, uri)
        } catch (_: Exception) {
            try {
                activity.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, uri),
                )
            } catch (_: Exception) {
                throw IllegalStateException("No browser available to open payment link")
            }
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?) {
        PaymentSessionHolder.complete(
            PaymentLaunchResult.NativeSuccess(razorpayPaymentId.orEmpty()),
        )
    }

    override fun onPaymentError(code: Int, description: String?) {
        val cancelled = code == Checkout.PAYMENT_CANCELED ||
            description?.contains("cancel", ignoreCase = true) == true
        PaymentSessionHolder.complete(
            PaymentLaunchResult.NativeFailure(
                message = description ?: "Payment failed",
                cancelled = cancelled,
            ),
        )
    }
}
