package com.friday.assistant.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class FridayRoutineStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("friday_routines", Context.MODE_PRIVATE)
    private val lock = Any()

    fun save(name: String, commands: List<String>): Boolean {
        val cleanName = name.trim().lowercase(Locale.ROOT).take(60)
        val cleanCommands = commands.map { it.trim().take(300) }.filter { it.isNotBlank() }.take(12)
        if (cleanName.isBlank() || cleanCommands.isEmpty()) return false
        synchronized(lock) {
            val all = read()
            all.put(cleanName, JSONArray(cleanCommands))
            prefs.edit().putString(KEY, all.toString()).apply()
        }
        return true
    }

    fun get(name: String): List<String> = synchronized(lock) {
        val array = read().optJSONArray(name.trim().lowercase(Locale.ROOT)) ?: return@synchronized emptyList()
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }

    fun names(): List<String> = synchronized(lock) { read().keys().asSequence().toList().sorted() }

    fun delete(name: String) {
        synchronized(lock) {
            val all = read()
            all.remove(name.trim().lowercase(Locale.ROOT))
            prefs.edit().putString(KEY, all.toString()).apply()
        }
    }

    private fun read(): JSONObject = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }.getOrElse { JSONObject() }

    companion object { private const val KEY = "routines" }
}
