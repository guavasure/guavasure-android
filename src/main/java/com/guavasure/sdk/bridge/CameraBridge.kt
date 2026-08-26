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
 * When [enableCameraCapture] is true, requests runtime camera permission when needed.
 * Partners must declare `CAMERA` in their manifest.
 */
class CameraBridge(
    private val activity: ComponentActivity,
    private val webViewProvider: () -> WebView,
    private val scope: CoroutineScope,
    private var enableCameraCapture: Boolean = false,
) {
    private var pendingCaptureRequestId: String? = null
    private var pendingPermissionRequestId: String? = null
    private var launchCaptureAfterPermission = false
    private var captureOutputUri: Uri? = null

    private lateinit var captureLauncher: ActivityResultLauncher<Uri>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    fun setEnableCameraCapture(enabled: Boolean) {
        enableCameraCapture = enabled
    }

    fun register() {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val requestId = pendingPermissionRequestId ?: return@registerForActivityResult
            val shouldLaunchCapture = launchCaptureAfterPermission
            pendingPermissionRequestId = null
            launchCaptureAfterPermission = false

            if (shouldLaunchCapture) {
                if (granted) {
                    launchCameraCapture(requestId)
                } else {
                    val error = if (
                        !ActivityCompat.shouldShowRequestPermissionRationale(
                            activity,
                            Manifest.permission.CAMERA,
                        )
                    ) {
                        "permission_permanently_denied"
                    } else {
                        "permission_denied"
                    }
                    resolveCaptureError(requestId, error)
                }
                return@registerForActivityResult
            }

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
        if (!enableCameraCapture) {
            resolvePermission(requestId, false)
            return
        }

        if (hasCameraPermission()) {
            resolvePermission(requestId, true)
            return
        }

        requestCameraPermission(requestId, launchCaptureAfter = false)
    }

    fun handleCaptureRequest(requestId: String) {
        if (!enableCameraCapture) {
            resolveCaptureError(requestId, "permission_denied")
            return
        }

        if (pendingCaptureRequestId != null) {
            resolveCaptureError(requestId, "error")
            return
        }

        if (!hasCameraPermission()) {
            requestCameraPermission(requestId, launchCaptureAfter = true)
            return
        }

        launchCameraCapture(requestId)
    }

    private fun launchCameraCapture(requestId: String) {
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

    private fun isAwaitingPermission(): Boolean = pendingPermissionRequestId != null

    private fun requestCameraPermission(
        requestId: String,
        launchCaptureAfter: Boolean,
    ): Boolean {
        if (isAwaitingPermission()) {
            if (launchCaptureAfter) {
                resolveCaptureError(requestId, "error")
            } else {
                resolvePermission(requestId, false)
            }
            return false
        }

        pendingPermissionRequestId = requestId
        launchCaptureAfterPermission = launchCaptureAfter
        permissionLauncher.launch(Manifest.permission.CAMERA)
        return true
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
