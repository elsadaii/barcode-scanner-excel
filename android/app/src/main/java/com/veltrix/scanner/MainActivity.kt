package com.veltrix.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openNativeScanner() else notifyCameraClosed()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        @JavascriptInterface fun openExternal(url: String) {
            runOnUiThread {
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }
        }
    }

    private fun openNativeScanner() {
        if (scannerActivity != null) return
        startActivity(Intent(this, ScannerActivity::class.java))
    }

    private fun notifyCameraClosed() {
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
        super.onDestroy()
    }

    companion object {
        var scannerActivity: ScannerActivity? = null
    }
}
