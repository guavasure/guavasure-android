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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.guavasure.sdk.internal.ChunkedJsDelivery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class CameraBridge(
    private val activity: ComponentActivity,
    private val webViewProvider: () -> WebView,
    private val scope: CoroutineScope,
) {
    private var pendingPermissionRequestId: String? = null
    private var pendingCaptureRequestId: String? = null
    private var captureOutputUri: Uri? = null

    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private lateinit var captureLauncher: ActivityResultLauncher<Uri>

    fun register() {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val requestId = pendingPermissionRequestId ?: return@registerForActivityResult
            pendingPermissionRequestId = null
            resolvePermission(requestId, granted)
        }

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
        val granted = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            resolvePermission(requestId, true)
            return
        }

        pendingPermissionRequestId = requestId
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    fun handleCaptureRequest(requestId: String) {
        val granted = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
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

    private fun resolvePermission(requestId: String, granted: Boolean) {
        val outcome = if (granted) "granted" else "permission_denied"
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
                    "window.__guavasureResolveCameraCapture($encodedId, null);",
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
