package com.guavasure.sdk.internal

import android.webkit.WebView
import com.guavasure.sdk.bridge.BridgeConstants
import org.json.JSONObject

object ChunkedJsDelivery {
    suspend fun resolveFilePick(
        webView: WebView,
        requestId: String,
        payload: JSONObject?,
    ) {
        val encodedId = JSONObject.quote(requestId)
        if (payload == null) {
            webView.evaluateJavascript(
                "window.__guavasureResolveFilePick($encodedId, null);",
                null,
            )
            return
        }

        val base64 = payload.optString("base64")
        if (base64.length <= BridgeConstants.BASE64_CHUNK_SIZE) {
            webView.evaluateJavascript(
                "window.__guavasureResolveFilePick($encodedId, $payload);",
                null,
            )
            return
        }

        val meta = JSONObject().apply {
            put("name", payload.optString("name"))
            put("mimeType", payload.optString("mimeType"))
        }

        webView.evaluateJavascript(
            """
            (function () {
              window.__guavasurePickBuffers = window.__guavasurePickBuffers || {};
              window.__guavasurePickBuffers[$encodedId] = '';
            })();
            """.trimIndent(),
            null,
        )

        var offset = 0
        while (offset < base64.length) {
            val end = minOf(offset + BridgeConstants.BASE64_CHUNK_SIZE, base64.length)
            val chunk = JSONObject.quote(base64.substring(offset, end))
            webView.evaluateJavascript(
                """
                (function () {
                  window.__guavasurePickBuffers[$encodedId] += $chunk;
                })();
                """.trimIndent(),
                null,
            )
            offset = end
        }

        webView.evaluateJavascript(
            """
            (function () {
              var requestId = $encodedId;
              var meta = $meta;
              var base64 = window.__guavasurePickBuffers[requestId] || '';
              delete window.__guavasurePickBuffers[requestId];
              window.__guavasureResolveFilePick(requestId, {
                name: meta.name,
                mimeType: meta.mimeType,
                base64: base64,
              });
            })();
            """.trimIndent(),
            null,
        )
    }

    fun buildPartnerPaymentScript(
        payload: JSONObject,
    ): String {
        val encoded = payload.toString()
        return """
            (function() {
              var msg = $encoded;
              window.postMessage(msg, '*');
            })();
        """.trimIndent()
    }
}
