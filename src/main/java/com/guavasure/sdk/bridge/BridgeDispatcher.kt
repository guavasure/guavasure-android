package com.guavasure.sdk.bridge

import com.guavasure.sdk.CollectPaymentRequest
import com.guavasure.sdk.PartnerPaymentResult
import com.guavasure.sdk.payment.OpenPaymentRequest
import org.json.JSONObject

class BridgeDispatcher(
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onOpenPayment(request: OpenPaymentRequest)
        fun onCollectPayment(request: CollectPaymentRequest)
        fun onPickFile(requestId: String, accept: String)
        fun onCameraPermission(requestId: String)
        fun onCapturePhoto(requestId: String)
        fun onOpenCameraSettings()
        fun onLogout()
        fun onGoBack()
        fun onChromeColor(background: String, statusBarStyle: String)
    }

    fun dispatch(message: String) {
        val trimmed = message.trim()
        if (trimmed.equals(BridgeConstants.LOGOUT, ignoreCase = true)) {
            callbacks.onLogout()
            return
        }
        if (trimmed.equals(BridgeConstants.GO_BACK, ignoreCase = true)) {
            callbacks.onGoBack()
            return
        }

        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return
        val type = json.optString("type")
        val event = json.optString("event")

        when {
            type.equals(BridgeConstants.LOGOUT, true) ||
                event.equals(BridgeConstants.LOGOUT, true) -> {
                callbacks.onLogout()
            }

            type.equals(BridgeConstants.GO_BACK, true) ||
                event.equals(BridgeConstants.GO_BACK, true) -> {
                callbacks.onGoBack()
            }

            type == BridgeConstants.CHROME_COLOR -> {
                val background = json.optString("background")
                val statusBarStyle = json.optString("statusBarStyle", "dark")
                if (background.isNotBlank()) {
                    callbacks.onChromeColor(background, statusBarStyle)
                }
            }

            type == BridgeConstants.OPEN_CAMERA_SETTINGS -> {
                callbacks.onOpenCameraSettings()
            }

            type == BridgeConstants.CAMERA_PERMISSION -> {
                val requestId = json.optString("requestId")
                if (requestId.isNotBlank()) callbacks.onCameraPermission(requestId)
            }

            type == BridgeConstants.CAPTURE_PHOTO -> {
                val requestId = json.optString("requestId")
                if (requestId.isNotBlank()) callbacks.onCapturePhoto(requestId)
            }

            type == BridgeConstants.PICK_FILE -> {
                val requestId = json.optString("requestId")
                val accept = json.optString("accept", "image/*")
                if (requestId.isNotBlank()) callbacks.onPickFile(requestId, accept)
            }

            type == BridgeConstants.OPEN_PAYMENT -> {
                val url = json.optString("url")
                if (url.isBlank()) return
                callbacks.onOpenPayment(parseOpenPayment(json, url))
            }

            type == BridgeConstants.GUAVASURE_PAYMENT && event == BridgeConstants.COLLECT_PAYMENT -> {
                val data = json.optJSONObject("data") ?: return
                val intentId = data.optString("intentId")
                val quoteId = data.optString("quoteId")
                val amount = data.opt("amount") as? Number
                val currency = data.optString("currency")
                val planLabel = data.optString("planLabel")
                if (
                    intentId.isBlank() || quoteId.isBlank() || amount == null ||
                    currency.isBlank() || planLabel.isBlank()
                ) {
                    return
                }
                callbacks.onCollectPayment(
                    CollectPaymentRequest(
                        intentId = intentId,
                        quoteId = quoteId,
                        amount = amount,
                        currency = currency,
                        planLabel = planLabel,
                    ),
                )
            }
        }
    }

    private fun parseOpenPayment(json: JSONObject, url: String): OpenPaymentRequest {
        val prefill = json.optJSONObject("prefill")
        return OpenPaymentRequest(
            url = url,
            quoteId = json.optString("quoteId").takeIf { it.isNotBlank() },
            orderId = json.optString("orderId").takeIf { it.isNotBlank() },
            subscriptionId = json.optString("subscriptionId").takeIf { it.isNotBlank() },
            key = json.optString("key").takeIf { it.isNotBlank() },
            amountPaise = json.optInt("amountPaise").takeIf { json.has("amountPaise") },
            currency = json.optString("currency").takeIf { it.isNotBlank() },
            name = json.optString("name").takeIf { it.isNotBlank() },
            description = json.optString("description").takeIf { it.isNotBlank() },
            prefillName = prefill?.optString("name")?.takeIf { it.isNotBlank() },
            prefillEmail = prefill?.optString("email")?.takeIf { it.isNotBlank() },
            prefillContact = prefill?.optString("contact")?.takeIf { it.isNotBlank() },
        )
    }

    fun buildPaymentConfirmedScript(intentId: String, quoteId: String): String {
        val payload = JSONObject().apply {
            put("type", BridgeConstants.GUAVASURE_PAYMENT)
            put("event", BridgeConstants.PAYMENT_CONFIRMED)
            put("data", JSONObject().apply {
                put("intentId", intentId)
                put("quoteId", quoteId)
            })
            put("timestamp", java.time.Instant.now().toString())
        }
        return com.guavasure.sdk.internal.ChunkedJsDelivery.buildPartnerPaymentScript(payload)
    }

    fun buildPaymentCancelledScript(intentId: String, quoteId: String): String {
        val payload = JSONObject().apply {
            put("type", BridgeConstants.GUAVASURE_PAYMENT)
            put("event", BridgeConstants.PAYMENT_CANCELLED)
            put("data", JSONObject().apply {
                put("intentId", intentId)
                put("quoteId", quoteId)
            })
            put("timestamp", java.time.Instant.now().toString())
        }
        return com.guavasure.sdk.internal.ChunkedJsDelivery.buildPartnerPaymentScript(payload)
    }
}
