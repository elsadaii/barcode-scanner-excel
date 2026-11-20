package com.veltrix.scanner

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AppUpdater(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("veltrix_app_updates", Context.MODE_PRIVATE)
    private val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var checking = false
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id != prefs.getLong(KEY_DOWNLOAD_ID, -2L)) return
            val query = DownloadManager.Query().setFilterById(id)
            manager.query(query).use { cursor ->
                if (!cursor.moveToFirst()) return
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    activity.runOnUiThread { installOrRequestPermission() }
                } else if (status == DownloadManager.STATUS_FAILED) {
                    prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
                    activity.runOnUiThread {
                        AlertDialog.Builder(activity)
                            .setTitle("Update gagal")
                            .setMessage("APK gagal diunduh. Silakan coba lagi nanti.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            activity, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    fun checkForUpdate(manual: Boolean = false) {
        if (checking || activity.isFinishing) {
            if (manual) showCheckMessage("Pemeriksaan sedang berlangsung", "Tunggu sebentar sampai pemeriksaan update selesai.")
            return
        }
        checking = true
        Thread {
            try {
                val connection = (URL("https://api.github.com/repos/elsadaii/barcode-scanner-excel/releases/latest")
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                val json = try {
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    connection.disconnect()
                }
                val body = json.optString("body")
                val match = Regex("VELTRIX_VERSION_CODE=(\\d+)").find(body)
                    ?: throw IllegalStateException("Informasi versi rilis tidak ditemukan.")
                val latestCode = match.groupValues[1].toLongOrNull()
                    ?: throw IllegalStateException("Nomor versi terbaru tidak valid.")
                val currentCode = if (Build.VERSION.SDK_INT >= 28) {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode.toLong()
                }
                if (latestCode <= currentCode) {
                    if (manual) showCheckMessage("VELTRIX sudah terbaru", "Aplikasi Anda sudah menggunakan versi terbaru.")
                    return@Thread
                }
                val assets = json.optJSONArray("assets") ?: throw IllegalStateException("File update tidak ditemukan.")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk", true)) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                val downloadUrl = apkUrl ?: throw IllegalStateException("File APK untuk update tidak ditemukan.")
                val latestName = json.optString("tag_name").removePrefix("v")
                activity.runOnUiThread {
                    if (!activity.isFinishing) {
                        AlertDialog.Builder(activity)
                            .setTitle("Versi baru tersedia")
                            .setMessage("VELTRIX versi $latestName sudah tersedia. Perbarui aplikasi sekarang?")
                            .setNegativeButton("Nanti", null)
                            .setPositiveButton("Update") { _, _ -> downloadUpdate(downloadUrl, latestName) }
                            .show()
                    }
                }
            } catch (error: Exception) {
                if (manual) showCheckMessage("Gagal memeriksa update", "Tidak dapat memeriksa pembaruan. Periksa koneksi internet lalu coba lagi.")
            } finally {
                checking = false
            }
        }.start()
    }

    private fun showCheckMessage(title: String, message: String) {
        activity.runOnUiThread {
            if (!activity.isFinishing) {
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun downloadUpdate(url: String, version: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Pembaruan VELTRIX")
                .setDescription("Mengunduh versi $version")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(
                    activity, Environment.DIRECTORY_DOWNLOADS, "veltrix-update-$version.apk"
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val id = manager.enqueue(request)
            prefs.edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        } catch (_: Exception) {
            AlertDialog.Builder(activity)
                .setTitle("Tidak dapat mengunduh")
                .setMessage("Unduhan pembaruan tidak dapat dimulai. Periksa koneksi internet.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun installOrRequestPermission() {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            // Android requires this one-time permission before an app can launch
            // its package installer. Returning from Settings resumes installation.
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.packageName)
            )
            activity.startActivity(settingsIntent)
            return
        }
        // Open Android's installer immediately after the download completes.
        installDownloadedApk()
    }

    fun resumePendingInstall() {
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id < 0) return
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) return
        val query = DownloadManager.Query().setFilterById(id)
        manager.query(query).use { cursor ->
            if (cursor.moveToFirst() &&
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
            ) installDownloadedApk()
        }
    }

    private fun installDownloadedApk() {
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id < 0) return
        val uri = manager.getUriForDownloadedFile(id) ?: return
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
        activity.startActivity(installIntent)
    }

    fun destroy() {
        if (receiverRegistered) {
            try { activity.unregisterReceiver(receiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
    }

    companion object {
        private const val KEY_DOWNLOAD_ID = "pending_download_id"
    }
}
