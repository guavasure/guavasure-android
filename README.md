# GuavaSure Android SDK

Native Android library that embeds the hosted GuavaSure insurance flow in a WebView and implements `GuavasureBridge` for native Razorpay Checkout, file pick, camera capture, and partner auth.

**Full integration guide:** [partners.guavasure.com - Android SDK](https://partners.guavasure.com/integration/docs/integrations/android-sdk)

## Features

| Feature                             | Handled by SDK                      |
| ----------------------------------- | ----------------------------------- |
| Partner auth (`partner-auth-token`) | ✅ via `partnerAuthTokenProvider`   |
| `GuavasureBridge` channel           | ✅                                  |
| Native Razorpay Checkout            | ✅ primary payment path             |
| Custom Tab fallback                 | ✅                                  |
| No reload on Custom Tab return      | ✅ embed keeps polling quote status |
| Native payment success (no reload)  | ✅ embed polling advances checkout  |
| File pick (`pick-file`)             | ✅ chunked base64                   |
| Camera (`capture-photo`)            | ✅                                  |
| Logout / go-back                    | ✅ callbacks                        |
| Partner-collected payment           | ✅ optional handler                 |
| Embed origin + payment URL security | ✅                                  |
| Optional embed warmup               | ✅ `GuavaSureEmbedView.warmup`      |

## Quick start

### 1. Add the dependency

**JitPack (recommended):**

```kotlin
dependencies {
    implementation("com.github.guavasure:guavasure-android:1.0.4")
}
```

Repository: [github.com/guavasure/guavasure-android](https://github.com/guavasure/guavasure-android)

See the [installation section](https://partners.guavasure.com/integration/docs/integrations/android-sdk#installation) for submodule and monorepo options.

### 2. Manifest (host app)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />

<queries>
  <intent>
    <action android:name="android.support.customtabs.action.CustomTabsService" />
  </intent>
</queries>

<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.guavasure.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/guavasure_file_paths" />
</provider>
```

Add `res/xml/guavasure_file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="guavasure_cache" path="." />
</paths>
```

### 3. Activity

Your host activity must extend `GuavaSureEmbedActivity` so Razorpay callbacks reach the SDK.

```kotlin
class InsuranceActivity : GuavaSureEmbedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_insurance)

        val embedView = findViewById<GuavaSureEmbedView>(R.id.embedView)
        embedView.bind(this)
        embedView.configure(
            config = GuavaSureEmbedConfig(
                partnerId = "YOUR_PARTNER_ID",
                environment = GuavaSureEnvironment.SANDBOX,
                partnerAuthTokenProvider = {
                    yourBackend.mintCustomerAuthToken(userId)
                },
                // Optional: claimMode = true for Policy Details home
            ),
            callbacks = GuavaSureEmbedCallbacks(
                onLogout = { finish() },
                onGoBack = { finish() },
            ),
        )
        embedView.loadEmbed()
    }
}
```

## Embed URL

Uses `platform=app-webview` by default:

```
https://embed.guavasure.com?partner=...&env=sandbox&platform=app-webview&partner-auth-token=...
```

## Payment flow

1. Embed sends `open-payment` with `key`, `orderId`, `url`
2. SDK opens **native Razorpay** when possible
3. On native success → embed keeps polling and advances checkout (no reload)
4. On Custom Tab fallback → **no reload on resume**; embed polling completes in-place

## Requirements

- Android API 24+
- `compileSdk` 35
- Java 17
- Host activity extends `GuavaSureEmbedActivity`

## Related

- [iOS SDK](https://partners.guavasure.com/integration/docs/integrations/ios-sdk)
- [Flutter plugin](https://pub.dev/packages/guavasure_flutter)
- [Mobile WebView protocol](https://partners.guavasure.com/integration/docs/integrations/mobile-webview)
