package com.veltrix.scanner

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class VeltrixFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        registerToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: "VELTRIX"
        val body = message.notification?.body ?: message.data["body"] ?: return
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 7302, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, "veltrix_manual_updates")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    companion object {
        private const val PREFS = "veltrix_notifications"
        private const val ENABLED = "enabled"

        fun notificationsEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

        fun setNotificationsEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                sendTokenAction(context, token, if (enabled) "register" else "unregister")
            }
        }

        fun registerToken(context: Context, token: String) {
            if (notificationsEnabled(context)) sendTokenAction(context, token, "register")
        }

        private fun sendTokenAction(context: Context, token: String, action: String) {
            Thread {
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL("https://pjlrzwcfuoeznkkfjpey.supabase.co/functions/v1/register-push-token").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 15000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("apikey", "sb_publishable_CHpVU5_aH5G7E53I7P-hPQ_9qoPJPHk")
                        setRequestProperty("Origin", "https://elsadaii.github.io")
                    }
                    val payload = JSONObject().put("token", token).put("platform", "android").put("action", action).toString()
                    connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    val responseCode = connection.responseCode
                    if (responseCode >= 400) connection.errorStream?.close() else connection.inputStream.close()
                } catch (_: Exception) {
                    // A later token refresh or user toggle can retry registration.
                } finally {
                    connection?.disconnect()
                }
            }.start()
        }
    }
}
