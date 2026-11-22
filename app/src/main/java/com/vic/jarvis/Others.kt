package com.vic.jarvis

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class JarvisTile : TileService() {
    override fun onStartListening() {
        val t = qsTile
        if (t != null) {
            t.state = if (JarvisService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            t.label = "Jarvis"
            t.updateTile()
        }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).putExtra("quick", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else startActivityAndCollapse(i)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Util.channels(c)
        val t = i.getStringExtra("t") ?: "Recordatorio"
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, Util.CH_REMIND)
            .setSmallIcon(R.drawable.ic_jarvis).setContentTitle("⏰ Jarvis · Recordatorio").setContentText(t)
            .setStyle(Notification.BigTextStyle().bigText(t)).setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true).setContentIntent(open).build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        c.getSystemService(NotificationManager::class.java).notify(i.getIntExtra("id", 1), n)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Util.rescheduleAll(c)
        Util.rescheduleWeb(c)
    }
}
