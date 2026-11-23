package com.faxydre.smbsynchdroid

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannel(this)
        SyncScheduler.ensureScheduled(this)
    }
}

object Perms {
    val photoPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasPhotos(ctx: Context) = granted(ctx, photoPermission)
    fun hasMediaLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION)
    fun hasNotifications(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    fun missing(ctx: Context): Array<String> {
        val list = mutableListOf<String>()
        if (!hasPhotos(ctx)) list += photoPermission
        if (!hasMediaLocation(ctx)) list += Manifest.permission.ACCESS_MEDIA_LOCATION
        if (!hasNotifications(ctx)) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    fun batteryUnrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
}

object Notifier {
    private const val CHANNEL = "sync"
    private const val ERROR_ID = 1

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "Synchronisation", NotificationManager.IMPORTANCE_DEFAULT)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    @SuppressLint("MissingPermission")
    fun error(ctx: Context, message: String) {
        if (!Perms.hasNotifications(ctx)) return
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Synchro des photos en échec")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ERROR_ID, n) }
    }

    fun clearError(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ERROR_ID)
    }
}

/** Petit journal local affiché dans l'appli. */
object LogStore {
    private const val MAX_LINES = 300
    private fun file(ctx: Context) = File(ctx.filesDir, "sync.log")

    @Synchronized
    fun add(ctx: Context, message: String) {
        val f = file(ctx)
        val lines = if (f.exists()) f.readLines() else emptyList()
        val ts = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date())
        f.writeText((lines + "$ts  $message").takeLast(MAX_LINES).joinToString("\n"))
    }

    @Synchronized
    fun tail(ctx: Context, n: Int = 40): String {
        val f = file(ctx)
        if (!f.exists()) return "(vide)"
        return f.readLines().takeLast(n).reversed().joinToString("\n")
    }

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
