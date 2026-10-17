package com.jarvis.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class JarvisNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val body = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && body.isBlank()) return

        val prefs = getSharedPreferences("jarvis_data", MODE_PRIVATE)
        val current = try { JSONArray(prefs.getString("notifications", "[]")) } catch (_: Exception) { JSONArray() }
        val entry = JSONObject()
            .put("app", sbn.packageName)
            .put("title", title.take(180))
            .put("text", body.take(400))
            .put("time", System.currentTimeMillis())
        val updated = JSONArray()
        updated.put(entry)
        for (i in 0 until minOf(current.length(), 29)) updated.put(current.getJSONObject(i))
        prefs.edit().putString("notifications", updated.toString()).apply()
    }
}
