package com.example.gpssetter

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import com.google.android.gms.location.LocationServices

/**
 * كل منطق الموقع الوهمي هنا:
 * - يسجّل مزوّدات اختبار (GPS + NETWORK) في نظام أندرويد
 * - يرسل لها الإحداثيات
 * - يحدّث كذلك Fused Location (اللي تستخدمه قوقل ماب وأغلب التطبيقات)
 */
object MockHelper {

    private val PROVIDERS = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
    )

    private fun lm(ctx: Context): LocationManager =
        ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Suppress("DEPRECATION")
    private fun addProvider(lm: LocationManager, name: String) {
        lm.addTestProvider(
            name,
            false, false, false, false,
            true, true, true,
            Criteria.POWER_LOW,
            Criteria.ACCURACY_FINE
        )
        lm.setTestProviderEnabled(name, true)
    }

    /** true لو التطبيق مختار كـ "تطبيق الموقع الوهمي" في خيارات المطور */
    fun isMockAllowed(ctx: Context): Boolean {
        val lm = lm(ctx)
        return try {
            addProvider(lm, LocationManager.GPS_PROVIDER)
            lm.removeTestProvider(LocationManager.GPS_PROVIDER)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            // المزوّد موجود أصلاً يعني الخدمة شغالة
            true
        }
    }

    /** يجهّز المزوّدات. يرمي SecurityException لو التطبيق مو مختار كتطبيق موقع وهمي */
    fun start(ctx: Context) {
        val lm = lm(ctx)
        for (p in PROVIDERS) {
            try {
                lm.removeTestProvider(p)
            } catch (e: Exception) {
            }
            addProvider(lm, p)
        }
        try {
            LocationServices.getFusedLocationProviderClient(ctx).setMockMode(true)
        } catch (e: Exception) {
        }
    }

    /** يرسل الإحداثيات الحالية (ينادى كل ثانية) */
    fun push(ctx: Context, lat: Double, lon: Double) {
        val lm = lm(ctx)
        for (p in PROVIDERS) {
            try {
                lm.setTestProviderLocation(p, build(p, lat, lon))
            } catch (e: Exception) {
            }
        }
        try {
            LocationServices.getFusedLocationProviderClient(ctx)
                .setMockLocation(build(LocationManager.GPS_PROVIDER, lat, lon))
        } catch (e: Exception) {
        }
    }

    /** يوقف الموقع الوهمي ويرجّع الجهاز لموقعه الحقيقي */
    fun stop(ctx: Context) {
        val lm = lm(ctx)
        for (p in PROVIDERS) {
            try {
                lm.setTestProviderEnabled(p, false)
            } catch (e: Exception) {
            }
            try {
                lm.removeTestProvider(p)
            } catch (e: Exception) {
            }
        }
        try {
            LocationServices.getFusedLocationProviderClient(ctx).setMockMode(false)
        } catch (e: Exception) {
        }
    }

    private fun build(provider: String, lat: Double, lon: Double): Location {
        val l = Location(provider)
        l.latitude = lat
        l.longitude = lon
        l.altitude = 10.0
        l.accuracy = 3f
        l.speed = 0f
        l.bearing = 0f
        l.time = System.currentTimeMillis()
        l.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        l.verticalAccuracyMeters = 3f
        l.speedAccuracyMetersPerSecond = 0.5f
        l.bearingAccuracyDegrees = 5f
        return l
    }
}
