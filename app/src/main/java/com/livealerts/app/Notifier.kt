package com.livealerts.app

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    private const val CHANNEL_LIVE = "live"
    private const val CHANNEL_SERVICE = "service"

    fun createChannels(ctx: Context) {
        NotificationManagerCompat.from(ctx).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_LIVE, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(ctx.getString(R.string.channel_live))
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_SERVICE, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(ctx.getString(R.string.channel_service))
                    .setShowBadge(false)
                    .build(),
            )
        )
    }

    fun wentLive(ctx: Context, channel: Channel, live: YouTube.Live) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        createChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx,
            channel.path.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(YouTube.watchUrl(live.videoId))),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.is_live, channel.name))
            .setContentText(live.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(live.title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(channel.path.hashCode(), notification)
    }

    fun serviceNotification(ctx: Context): Notification {
        createChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.service_title))
            .setContentText(ctx.getString(R.string.service_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
