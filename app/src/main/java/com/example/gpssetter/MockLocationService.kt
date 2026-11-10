package com.example.gpssetter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale

/**
 * خدمة أمامية (Foreground Service) ترسل الموقع الوهمي كل ثانية،
 * عشان يبقى ثابت حتى لو قفلت التطبيق.
 */
class MockLocationService : Service() {

    companion object {
        const val ACTION_START = "com.example.gpssetter.START"
        const val ACTION_STOP = "com.example.gpssetter.STOP"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"

        private const val CHANNEL_ID = "mock_location"
        private const val NOTIF_ID = 1
        private const val PREFS = "service_state"

        @Volatile
        var running = false
            private set

        @Volatile
        var currentLat = 0.0
            private set

        @Volatile
        var currentLon = 0.0
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            MockHelper.push(this@MockLocationService, currentLat, currentLon)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMocking()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
                val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
                if (lat.isNaN() || lon.isNaN()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startMocking(lat, lon)
            }

            else -> {
                // النظام رجّع تشغيل الخدمة بعد ما قتلها: نكمل من آخر موقع محفوظ
                val p = getSharedPreferences(PREFS, MODE_PRIVATE)
                val lat = p.getString("lat", null)?.toDoubleOrNull()
                val lon = p.getString("lon", null)?.toDoubleOrNull()
                if (p.getBoolean("want", false) && lat != null && lon != null) {
                    startMocking(lat, lon)
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun startMocking(lat: Double, lon: Double) {
        currentLat = lat
        currentLon = lon

        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(lat, lon),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_perm_needed, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        try {
            MockHelper.start(this)
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.dlg_mock_msg, Toast.LENGTH_LONG).show()
            stopMocking()
            return
        }

        running = true
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("want", true)
            .putString("lat", lat.toString())
            .putString("lon", lon.toString())
            .apply()

        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun stopMocking() {
        handler.removeCallbacks(tick)
        if (running) {
            MockHelper.stop(this)
        }
        running = false
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("want", false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (running) {
            MockHelper.stop(this)
            running = false
        }
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(lat: Double, lon: Double): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            flags
        )

        val coords = String.format(Locale.US, "%.6f, %.6f", lat, lon)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, coords))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
