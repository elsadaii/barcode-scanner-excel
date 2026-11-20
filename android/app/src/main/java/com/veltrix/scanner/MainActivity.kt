package com.veltrix.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var googleDriveAuthorizationLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openNativeScanner() else notifyCameraClosed()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7301)
        }
        googleDriveAuthorizationLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                deliverGoogleDriveResult(null, "Login Google Drive dibatalkan.")
            } else {
                try {
                    val authorization = Identity.getAuthorizationClient(this)
                        .getAuthorizationResultFromIntent(result.data)
                    val token = authorization.accessToken
                    if (token.isNullOrBlank()) deliverGoogleDriveResult(null, "Google tidak memberikan token akses Drive.")
                    else deliverGoogleDriveResult(token, null)
                } catch (error: Exception) {
                    deliverGoogleDriveResult(null, error.message ?: "Otorisasi Google Drive gagal.")
                }
            }
        }
        webView = WebView(this)
        webView.setBackgroundColor(android.graphics.Color.rgb(8, 23, 43))
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = false
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                    if (allowed.isNotEmpty() && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(allowed.toTypedArray())
                    } else request.deny()
                }
            }
        }
        webView.addJavascriptInterface(NativeScannerBridge(), "VeltrixNativeScanner")
        setContentView(webView)
        webView.loadUrl("https://elsadaii.github.io/barcode-scanner-excel/?native=1")
    }

    inner class NativeScannerBridge {
        @JavascriptInterface fun start() {
            runOnUiThread {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    openNativeScanner()
                } else cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
        @JavascriptInterface fun stop() { runOnUiThread { scannerActivity?.finish() } }
        @JavascriptInterface fun authorizeGoogleDrive(): Boolean {
            runOnUiThread { requestGoogleDriveAuthorization() }
            return true
        }
        @JavascriptInterface fun openExternal(url: String) {
            runOnUiThread {
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("veltrix_manual_updates", "Notifikasi VELTRIX", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "Notifikasi yang dikirim secara manual oleh Admin VELTRIX"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestGoogleDriveAuthorization() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive")))
            .build()
        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { authorization ->
                if (authorization.hasResolution()) {
                    val pendingIntent = authorization.pendingIntent
                    if (pendingIntent == null) {
                        deliverGoogleDriveResult(null, "Google meminta verifikasi tambahan, tetapi dialog izin tidak tersedia.")
                    } else {
                        googleDriveAuthorizationLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    }
                } else {
                    val token = authorization.accessToken
                    if (token.isNullOrBlank()) deliverGoogleDriveResult(null, "Google tidak memberikan token akses Drive.")
                    else deliverGoogleDriveResult(token, null)
                }
            }
            .addOnFailureListener { error ->
                deliverGoogleDriveResult(null, error.message ?: "Tidak dapat meminta izin Google Drive.")
            }
    }

    private fun deliverGoogleDriveResult(token: String?, error: String?) {
        val tokenJson = org.json.JSONObject.quote(token ?: "")
        val errorJson = org.json.JSONObject.quote(error ?: "")
        runOnUiThread {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('veltrix-google-drive-token',{detail:{accessToken:$tokenJson,error:$errorJson}}))",
                null
            )
        }
    }

    private fun openNativeScanner() {
        if (scannerActivity != null) return
        startActivity(Intent(this, ScannerActivity::class.java))
    }

    fun notifyCameraClosed() {
        webView.evaluateJavascript("window.dispatchEvent(new Event('veltrix-native-camera-closed'))", null)
    }

    fun deliverScan(value: String) {
        val safe = org.json.JSONObject.quote(value)
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('veltrix-native-scan',{detail:{value:$safe}}))", null)
    }

    override fun onBackPressed() {
        if (scannerActivity != null) scannerActivity?.finish()
        else if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("VeltrixNativeScanner")
        webView.destroy()
        current = null
        super.onDestroy()
    }

    companion object {
        var scannerActivity: ScannerActivity? = null
        var current: MainActivity? = null
    }
}
