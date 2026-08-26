package com.guavasure.sdk.bridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.guavasure.sdk.internal.ChunkedJsDelivery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

class FilePickerBridge(
    private val activity: ComponentActivity,
    private val webViewProvider: () -> WebView,
    private val scope: CoroutineScope,
) {
    private var webViewFileCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var documentPicker: ActivityResultLauncher<Array<String>>
    private var pendingBridgeRequestId: String? = null

    fun register() {
        documentPicker = activity.registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri -> deliver(uri) }
    }

    fun createWebChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            webViewFileCallback?.onReceiveValue(null)
            webViewFileCallback = filePathCallback
            val mimeTypes = fileChooserParams.acceptTypes
                .filter { it.isNotBlank() }
                .toTypedArray()
                .takeIf { it.isNotEmpty() }
                ?: arrayOf("image/*")
            documentPicker.launch(mimeTypes)
            return true
        }
    }

    fun handlePickFile(requestId: String, accept: String) {
        pendingBridgeRequestId = requestId
        val mimeTypes = accept.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toTypedArray()
            .takeIf { it.isNotEmpty() }
            ?: arrayOf("image/*")
        documentPicker.launch(mimeTypes)
    }

    private fun deliver(uri: Uri?) {
        val bridgeRequestId = pendingBridgeRequestId
        if (bridgeRequestId != null) {
            pendingBridgeRequestId = null
            resolveBridgePick(bridgeRequestId, uri)
            return
        }

        val callback = webViewFileCallback
        webViewFileCallback = null
        callback?.onReceiveValue(if (uri == null) null else arrayOf(uri))
    }

    private fun resolveBridgePick(requestId: String, uri: Uri?) {
        val webView = webViewProvider()
        if (uri == null) {
            scope.launch {
                ChunkedJsDelivery.resolveFilePick(webView, requestId, null)
            }
            return
        }

        scope.launch {
            val payload = withContext(Dispatchers.IO) {
                readPayload(uri)
            }
            ChunkedJsDelivery.resolveFilePick(webView, requestId, payload)
        }
    }

    private fun readPayload(uri: Uri): JSONObject? {
        return try {
            val resolver = activity.contentResolver
            val name = queryDisplayName(uri) ?: "upload"
            val mimeType = resolver.getType(uri) ?: guessMimeType(name)
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > BridgeConstants.MAX_FILE_BYTES) return@use null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: throw IOException("Could not read file")
            if (bytes == null) return null
            JSONObject().apply {
                put("name", name)
                put("mimeType", mimeType)
                put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment
    }

    private fun guessMimeType(filename: String): String {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }
}
