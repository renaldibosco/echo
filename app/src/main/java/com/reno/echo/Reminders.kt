package com.reno.echo

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Reminders that fire as notifications, even when Echo is closed. Survive reboots. */
object Reminders {
    const val CHANNEL = "reminders"
    private const val KEY = "reminders"
    private fun prefs(c: Context) = c.getSharedPreferences("echo", Context.MODE_PRIVATE)

    fun list(c: Context): JSONArray = try { JSONArray(prefs(c).getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }

    private fun save(c: Context, a: JSONArray) = prefs(c).edit().putString(KEY, a.toString()).apply()

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders you set with Echo"
                enableVibration(true)
            }
        )
    }

    fun add(c: Context, at: Long, text: String): Int {
        val id = (System.currentTimeMillis() % 1_000_000_000L).toInt()
        val a = list(c)
        a.put(JSONObject().put("id", id).put("at", at).put("text", text))
        save(c, a)
        schedule(c, id, at, text)
        return id
    }

    fun remove(c: Context, id: Int) {
        val a = list(c)
        val out = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getInt("id") == id) cancel(c, id) else out.put(o)
        }
        save(c, out)
    }

    fun clear(c: Context) {
        val a = list(c)
        for (i in 0 until a.length()) cancel(c, a.getJSONObject(i).getInt("id"))
        save(c, JSONArray())
    }

    /** Drop reminders that already fired, reschedule the rest (after reboot / update). */
    fun restore(c: Context) {
        val a = list(c)
        val out = JSONArray()
        val now = System.currentTimeMillis()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getLong("at") > now - 60_000) {
                out.put(o)
                schedule(c, o.getInt("id"), maxOf(o.getLong("at"), now + 5_000), o.getString("text"))
            }
        }
        save(c, out)
    }

    private fun pending(c: Context, id: Int, text: String?): PendingIntent {
        val i = Intent(c, ReminderReceiver::class.java).setAction("com.reno.echo.REMIND").putExtra("id", id)
        if (text != null) i.putExtra("text", text)
        return PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun schedule(c: Context, id: Int, at: Long, text: String) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = pending(c, id, text)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancel(c: Context, id: Int) {
        c.getSystemService(AlarmManager::class.java).cancel(pending(c, id, null))
    }

    fun show(c: Context, id: Int, text: String) {
        channels(c)
        val open = PendingIntent.getActivity(
            c, id, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Echo reminder")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setColor(0xFF2FE0D0.toInt())
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { c.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) {}
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            "com.reno.echo.REMIND" -> {
                val id = intent.getIntExtra("id", 0)
                val text = intent.getStringExtra("text") ?: "Reminder"
                Reminders.show(c, id, text)
                Reminders.remove(c, id)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> Reminders.restore(c)
        }
    }
}
