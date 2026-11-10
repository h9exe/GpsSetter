package com.example.gpssetter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Favorite(val name: String, val lat: Double, val lon: Double)

/** تخزين المفضلة محلياً (SharedPreferences بصيغة JSON) */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun all(): MutableList<Favorite> {
        val out = mutableListOf<Favorite>()
        try {
            val arr = JSONArray(prefs.getString("list", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Favorite(o.getString("name"), o.getDouble("lat"), o.getDouble("lon")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun add(f: Favorite) {
        val list = all()
        list.add(f)
        save(list)
    }

    fun removeAt(index: Int) {
        val list = all()
        if (index in list.indices) {
            list.removeAt(index)
            save(list)
        }
    }

    private fun save(list: List<Favorite>) {
        val arr = JSONArray()
        for (f in list) {
            arr.put(JSONObject().put("name", f.name).put("lat", f.lat).put("lon", f.lon))
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
