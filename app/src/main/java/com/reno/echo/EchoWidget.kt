package com.reno.echo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen orb: tap it and Echo opens already listening. */
class EchoWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val v = RemoteViews(c.packageName, R.layout.widget_echo)
            val listen = PendingIntent.getActivity(
                c, 100, Intent(c, MainActivity::class.java).setAction(MainActivity.ACTION_LISTEN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val photo = PendingIntent.getActivity(
                c, 101, Intent(c, MainActivity::class.java).setAction(MainActivity.ACTION_PHOTO)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.w_root, listen)
            v.setOnClickPendingIntent(R.id.w_cam, photo)
            mgr.updateAppWidget(id, v)
        }
    }
}
