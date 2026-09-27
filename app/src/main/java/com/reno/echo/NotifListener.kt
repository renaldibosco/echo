package com.reno.echo

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/** Keeps the latest notifications so Echo can read them out ("read my messages"). */
class NotifListener : NotificationListenerService() {

    companion object {
        private val skip = setOf("android", "com.android.systemui", "com.reno.echo", "com.android.providers.downloads")
        private const val KEY = "notifs"

        fun enabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(c, NotifListener::class.java).flattenToString()
            return flat.split(":").any { it == me }
        }

        /** Unread items since the last time Echo read them out. */
        fun unread(c: Context, max: Int): JSONArray {
            val p = c.getSharedPreferences("echo", Context.MODE_PRIVATE)
            val all = try { JSONArray(p.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
            val since = p.getLong("notifsRead", 0L)
            val out = JSONArray()
            for (i in all.length() - 1 downTo 0) {
                val o = all.getJSONObject(i)
                if (o.getLong("t") > since) out.put(o)
                if (out.length() >= max) break
            }
            return out
        }

        fun markRead(c: Context) {
            c.getSharedPreferences("echo", Context.MODE_PRIVATE).edit().putLong("notifsRead", System.currentTimeMillis()).apply()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName in skip || sbn.isOngoing) return
            val n = sbn.notification
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val ex = n.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim() ?: ""
            if (title.isEmpty() && text.isEmpty()) return
            val app = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (_: Exception) { sbn.packageName }

            val p = getSharedPreferences("echo", MODE_PRIVATE)
            val all = try { JSONArray(p.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
            // Replace an older copy of the same conversation line
            val out = JSONArray()
            for (i in 0 until all.length()) {
                val o = all.getJSONObject(i)
                if (!(o.optString("app") == app && o.optString("title") == title && o.optString("text") == text)) out.put(o)
            }
            out.put(JSONObject().put("app", app).put("title", title).put("text", text.take(400))
                .put("t", System.currentTimeMillis()).put("cat", n.category ?: ""))
            val trimmed = JSONArray()
            val start = maxOf(0, out.length() - 40)
            for (i in start until out.length()) trimmed.put(out.get(i))
            p.edit().putString(KEY, trimmed.toString()).apply()
        } catch (_: Exception) {}
    }
}
