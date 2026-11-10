package com.example.gpssetter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var startBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton
    private lateinit var searchInput: EditText
    private lateinit var favs: FavoritesStore

    private var marker: Marker? = null
    private var selected: GeoPoint? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateUi()
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val coordRegex =
        Regex("^\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*[,،;\\s]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$")

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            if (hasLocationPermission()) startMocking() else toast(R.string.toast_perm_needed)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // إعدادات الخريطة (OpenStreetMap - بدون مفتاح API)
        val osm = Configuration.getInstance()
        osm.load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        osm.userAgentValue = packageName
        osm.osmdroidBasePath = File(cacheDir, "osmdroid")
        osm.osmdroidTileCache = File(cacheDir, "osmdroid/tiles")

        setContentView(R.layout.activity_main)

        favs = FavoritesStore(this)
        map = findViewById(R.id.map)
        statusText = findViewById(R.id.statusText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        searchInput = findViewById(R.id.searchInput)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                select(p, false)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                select(p, false)
                return true
            }
        }))

        // نبدأ من الموقع الشغّال حالياً، أو من آخر موقع اخترته
        val last = getSharedPreferences("ui", MODE_PRIVATE)
        val lastLat = last.getString("lat", null)?.toDoubleOrNull()
        val lastLon = last.getString("lon", null)?.toDoubleOrNull()
        val start: GeoPoint? = when {
            MockLocationService.running ->
                GeoPoint(MockLocationService.currentLat, MockLocationService.currentLon)
            lastLat != null && lastLon != null -> GeoPoint(lastLat, lastLon)
            else -> null
        }
        map.controller.setZoom(if (start != null) 15.0 else 5.0)
        map.controller.setCenter(start ?: GeoPoint(24.7136, 46.6753))
        if (start != null) select(start, false)

        startBtn.setOnClickListener { onStartClicked() }
        stopBtn.setOnClickListener { stopMocking() }
        findViewById<MaterialButton>(R.id.saveFavBtn).setOnClickListener { saveFavorite() }
        findViewById<MaterialButton>(R.id.favsBtn).setOnClickListener { showFavorites() }
        findViewById<MaterialButton>(R.id.searchBtn).setOnClickListener { doSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else {
                false
            }
        }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        mainHandler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        mainHandler.removeCallbacks(refresh)
        val s = selected
        if (s != null) {
            getSharedPreferences("ui", MODE_PRIVATE).edit()
                .putString("lat", s.latitude.toString())
                .putString("lon", s.longitude.toString())
                .apply()
        }
    }

    // ------------------------------------------------------------------
    // اختيار الموقع
    // ------------------------------------------------------------------

    private fun select(p: GeoPoint, center: Boolean) {
        selected = p
        var m = marker
        if (m == null) {
            m = Marker(map)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            map.overlays.add(m)
            marker = m
        }
        m.position = p
        if (center) {
            map.controller.animateTo(p)
            if (map.zoomLevelDouble < 14.0) map.controller.setZoom(16.0)
        }
        map.invalidate()
        updateUi()
    }

    private fun fmt(p: GeoPoint): String =
        String.format(Locale.US, "%.6f, %.6f", p.latitude, p.longitude)

    private fun updateUi() {
        val running = MockLocationService.running
        val s = selected
        stopBtn.isEnabled = running
        statusText.text = when {
            running -> getString(
                R.string.status_running,
                fmt(GeoPoint(MockLocationService.currentLat, MockLocationService.currentLon))
            )
            s != null -> getString(R.string.status_selected, fmt(s))
            else -> getString(R.string.status_none)
        }
    }

    // ------------------------------------------------------------------
    // تشغيل / إيقاف
    // ------------------------------------------------------------------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun onStartClicked() {
        if (selected == null) {
            toast(R.string.toast_pick_first)
            return
        }
        if (!hasLocationPermission()) {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            permLauncher.launch(perms.toTypedArray())
            return
        }
        startMocking()
    }

    private fun startMocking() {
        val p = selected ?: return
        if (!MockLocationService.running && !MockHelper.isMockAllowed(this)) {
            showMockSetupDialog()
            return
        }
        val i = Intent(this, MockLocationService::class.java)
            .setAction(MockLocationService.ACTION_START)
            .putExtra(MockLocationService.EXTRA_LAT, p.latitude)
            .putExtra(MockLocationService.EXTRA_LON, p.longitude)
        ContextCompat.startForegroundService(this, i)
        mainHandler.postDelayed({ updateUi() }, 400)
    }

    private fun stopMocking() {
        if (!MockLocationService.running) return
        startService(
            Intent(this, MockLocationService::class.java)
                .setAction(MockLocationService.ACTION_STOP)
        )
        toast(R.string.toast_stopped)
        mainHandler.postDelayed({ updateUi() }, 400)
    }

    private fun showMockSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_mock_title)
            .setMessage(R.string.dlg_mock_msg)
            .setPositiveButton(R.string.dlg_open_dev) { _, _ -> openDeveloperSettings() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openDeveloperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            } catch (e2: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    // ------------------------------------------------------------------
    // البحث (اسم مكان أو إحداثيات)
    // ------------------------------------------------------------------

    private fun doSearch() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) return

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)

        val m = coordRegex.matchEntire(q)
        if (m != null) {
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                select(GeoPoint(lat, lon), true)
                return
            }
        }
        searchPlace(q)
    }

    private fun searchPlace(q: String) {
        Thread {
            try {
                val url = URL(
                    "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" +
                        URLEncoder.encode(q, "UTF-8")
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "$packageName/1.0")
                conn.setRequestProperty("Accept-Language", "ar,en")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(body)
                runOnUiThread {
                    if (arr.length() == 0) {
                        toast(R.string.toast_not_found)
                    } else {
                        val o = arr.getJSONObject(0)
                        select(GeoPoint(o.getDouble("lat"), o.getDouble("lon")), true)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast(R.string.toast_search_failed) }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // المفضلة
    // ------------------------------------------------------------------

    private fun saveFavorite() {
        val p = selected
        if (p == null) {
            toast(R.string.toast_pick_first)
            return
        }
        val input = EditText(this)
        input.hint = getString(R.string.dlg_fav_name_hint)
        input.setSingleLine()

        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_fav_name_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { fmt(p) }
                favs.add(Favorite(name, p.latitude, p.longitude))
                toast(R.string.toast_fav_saved)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFavorites() {
        val list = favs.all()
        if (list.isEmpty()) {
            toast(R.string.dlg_favs_empty)
            return
        }
        val names = list.map { it.name }.toMutableList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        val lv = ListView(this)
        lv.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dlg_favs_title)
            .setView(lv)
            .setNegativeButton(R.string.close, null)
            .create()

        lv.setOnItemClickListener { _, _, pos, _ ->
            val f = list[pos]
            select(GeoPoint(f.lat, f.lon), true)
            dialog.dismiss()
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            favs.removeAt(pos)
            list.removeAt(pos)
            names.removeAt(pos)
            adapter.notifyDataSetChanged()
            toast(R.string.toast_fav_deleted)
            if (list.isEmpty()) dialog.dismiss()
            true
        }
        dialog.show()
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }
}
