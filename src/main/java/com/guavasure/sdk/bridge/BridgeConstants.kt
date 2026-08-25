package com.guavasure.sdk.bridge

object BridgeConstants {
    const val CHANNEL_NAME = "GuavasureBridge"
    const val OPEN_PAYMENT = "open-payment"
    const val GUAVASURE_PAYMENT = "guavasure:payment"
    const val COLLECT_PAYMENT = "COLLECT_PAYMENT"
    const val PAYMENT_CONFIRMED = "PAYMENT_CONFIRMED"
    const val PAYMENT_CANCELLED = "PAYMENT_CANCELLED"
    const val PICK_FILE = "pick-file"
    const val LOGOUT = "logout"
    const val GO_BACK = "go-back"
    const val CHROME_COLOR = "chrome-color"
    const val CAPTURE_PHOTO = "capture-photo"
    const val CAMERA_PERMISSION = "camera-permission"
    const val OPEN_CAMERA_SETTINGS = "open-camera-settings"
    const val BASE64_CHUNK_SIZE = 16_000
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
}
