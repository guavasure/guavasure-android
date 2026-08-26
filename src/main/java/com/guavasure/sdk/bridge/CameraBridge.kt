package com.guavasure.sdk.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Native camera bridge for [capture-photo] / [camera-permission] embed messages.
 *
 * Never prompts for camera permission — partners must declare `CAMERA` in their
 * manifest and request runtime access before capture will work. When disabled or
 * permission is missing, the embed falls back to file upload.
 */
class CameraBridge(
    private val activity: ComponentActivity,
    private val webViewProvider: () -> WebView,
    private val scope: CoroutineScope,
    private var enableCameraCapture: Boolean = false,
) {
    private var pendingCaptureRequestId: String? = null
    private var captureOutputUri: Uri? = null

    private lateinit var captureLauncher: ActivityResultLauncher<Uri>

    fun setEnableCameraCapture(enabled: Boolean) {
        enableCameraCapture = enabled
    }

    fun register() {
        captureLauncher = activity.registerForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success ->
            val requestId = pendingCaptureRequestId ?: return@registerForActivityResult
            pendingCaptureRequestId = null
            val uri = captureOutputUri
            captureOutputUri = null
            if (!success || uri == null) {
                resolveCaptureError(requestId, "error")
                return@registerForActivityResult
            }
            resolveCapture(requestId, uri)
        }
    }

    fun handlePermissionRequest(requestId: String) {
        if (!enableCameraCapture) {
            resolvePermission(requestId, false)
            return
        }

        val granted = hasCameraPermission()
        resolvePermission(requestId, granted)
    }

    fun handleCaptureRequest(requestId: String) {
        if (!enableCameraCapture) {
            resolveCaptureError(requestId, "permission_denied")
            return
        }

        if (!hasCameraPermission()) {
            resolveCaptureError(requestId, "permission_denied")
            return
        }

        val photoFile = File(activity.cacheDir, "guavasure_capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.guavasure.fileprovider",
            photoFile,
        )
        captureOutputUri = uri
        pendingCaptureRequestId = requestId
        captureLauncher.launch(uri)
    }

    fun openCameraSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        activity.startActivity(intent)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolvePermission(requestId: String, granted: Boolean) {
        val outcome = when {
            granted -> "granted"
            enableCameraCapture &&
                !hasCameraPermission() &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.CAMERA,
                ) -> "permission_permanently_denied"
            else -> "permission_denied"
        }
        val encodedId = JSONObject.quote(requestId)
        val payload = JSONObject().put("outcome", outcome)
        webViewProvider().evaluateJavascript(
            "window.__guavasureResolveCameraPermission($encodedId, $payload);",
            null,
        )
    }

    private fun resolveCaptureError(requestId: String, error: String) {
        val encodedId = JSONObject.quote(requestId)
        val payload = JSONObject().put("error", error)
        webViewProvider().evaluateJavascript(
            "window.__guavasureResolveCameraCapture($encodedId, $payload);",
            null,
        )
    }

    private fun resolveCapture(requestId: String, uri: Uri) {
        scope.launch {
            val payload = withContext(Dispatchers.IO) {
                readImagePayload(uri)
            }
            val webView = webViewProvider()
            val encodedId = JSONObject.quote(requestId)
            if (payload == null) {
                webView.evaluateJavascript(
                    "window.__guavasureResolveCameraCapture($encodedId, ${JSONObject().put("error", "error")});",
                    null,
                )
                return@launch
            }
            webView.evaluateJavascript(
                "window.__guavasureResolveCameraCapture($encodedId, $payload);",
                null,
            )
        }
    }

    private fun readImagePayload(uri: Uri): JSONObject? {
        return try {
            val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            if (bytes.size > BridgeConstants.MAX_FILE_BYTES) return null
            JSONObject().apply {
                put("name", "capture.jpg")
                put("mimeType", "image/jpeg")
                put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        } catch (_: Exception) {
            null
        }
    }
}
