package com.example.btmessenger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

/** Уведомления: постоянное (работа службы), о сообщениях, о запросе подключения и тревожное SOS. */
class Notifier(private val ctx: Context) {
    private val nm: NotificationManager? = ctx.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val m = nm ?: return
        m.createNotificationChannel(
            NotificationChannel(CH_STATUS, ctx.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        m.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, ctx.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH)
        )
        m.createNotificationChannel(
            NotificationChannel(CH_REQUESTS, ctx.getString(R.string.channel_requests), NotificationManager.IMPORTANCE_HIGH)
        )
        m.createNotificationChannel(
            NotificationChannel(CH_SOS, ctx.getString(R.string.channel_sos), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.channel_sos_desc)
                enableVibration(true)
                vibrationPattern = SOS_VIBRATION
                enableLights(true)
                lightColor = 0xFFE53935.toInt()
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        )
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun builder(channel: String, highPriority: Boolean): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, channel)
        } else {
            Notification.Builder(ctx).apply {
                if (highPriority) {
                    setPriority(Notification.PRIORITY_HIGH)
                    setDefaults(Notification.DEFAULT_ALL)
                } else {
                    setPriority(Notification.PRIORITY_LOW)
                }
            }
        }

    private fun openApp(peer: String?, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (peer != null) intent.putExtra(MainActivity.EXTRA_PEER, peer)
        return PendingIntent.getActivity(
            ctx, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            ctx, requestCode, Intent(ctx, LinkService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    // ---------- Постоянное уведомление службы ----------

    fun status(state: BtLink.State): Notification {
        val text = when (state) {
            is BtLink.State.Off -> ctx.getString(
                when (state.reason) {
                    BtLink.OffReason.BLUETOOTH_OFF -> R.string.status_bt_off
                    BtLink.OffReason.NO_PERMISSION -> R.string.status_no_permission
                    BtLink.OffReason.NO_ADAPTER -> R.string.status_no_adapter
                    BtLink.OffReason.STOPPED -> R.string.status_starting
                }
            )
            is BtLink.State.Idle -> ctx.getString(R.string.status_idle)
            is BtLink.State.Connecting -> if (state.auto) {
                ctx.getString(R.string.status_reconnecting, state.peer.title, state.attempt)
            } else {
                ctx.getString(R.string.status_connecting, state.peer.title)
            }
            is BtLink.State.WaitingAccept -> ctx.getString(R.string.status_waiting_accept, state.peer.title)
            is BtLink.State.IncomingRequest -> ctx.getString(R.string.status_request, state.peer.title)
            is BtLink.State.Connected -> ctx.getString(R.string.status_connected, state.peer.title)
        }
        val stop = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_power),
            ctx.getString(R.string.action_turn_off),
            serviceAction(LinkService.ACTION_STOP, RC_STOP)
        ).build()
        return builder(CH_STATUS, highPriority = false)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp(state.peerAddress.takeIf { state is BtLink.State.Connected }, RC_OPEN))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(stop)
            .build()
    }

    fun updateStatus(state: BtLink.State) {
        if (!canNotify()) return
        nm?.notify(ID_STATUS, status(state))
    }

    // ---------- Сообщения ----------

    fun showMessage(m: ChatMessage, peer: BtLink.PeerInfo) {
        if (!canNotify()) return
        val text = when (m.kind) {
            Kind.GEO -> ctx.getString(R.string.preview_geo)
            else -> m.text
        }
        val n = builder(CH_MESSAGES, highPriority = true)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(peer.title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(peer.address, chatId(peer.address)))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        nm?.notify(chatId(peer.address), n)
    }

    fun showSos(m: ChatMessage, peer: BtLink.PeerInfo) {
        if (!canNotify()) return
        val text = Format.sosSummary(ctx, m)
        @Suppress("DEPRECATION")
        val b = builder(CH_SOS, highPriority = true)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(ctx.getString(R.string.sos_from, peer.title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(peer.address, sosId(peer.address)))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setColor(0xFFE53935.toInt())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            b.setVibrate(SOS_VIBRATION)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        }
        nm?.notify(sosId(peer.address), b.build())
    }

    fun cancelChat(peer: String) {
        nm?.cancel(chatId(peer))
        nm?.cancel(sosId(peer))
    }

    // ---------- Запрос на подключение ----------

    fun showRequest(peer: BtLink.PeerInfo) {
        if (!canNotify()) return
        val accept = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_check),
            ctx.getString(R.string.accept),
            serviceAction(LinkService.ACTION_ACCEPT, RC_ACCEPT)
        ).build()
        val reject = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_close),
            ctx.getString(R.string.reject),
            serviceAction(LinkService.ACTION_REJECT, RC_REJECT)
        ).build()
        val n = builder(CH_REQUESTS, highPriority = true)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(ctx.getString(R.string.request_title))
            .setContentText(Format.peerDescription(ctx, peer))
            .setContentIntent(openApp(null, RC_REQUEST))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(accept)
            .addAction(reject)
            .build()
        nm?.notify(ID_REQUEST, n)
    }

    fun cancelRequest() {
        nm?.cancel(ID_REQUEST)
    }

    private fun chatId(peer: String) = 1000 + (peer.hashCode() and 0xFFFF)
    private fun sosId(peer: String) = 100_000 + (peer.hashCode() and 0xFFFF)

    companion object {
        const val ID_STATUS = 1
        const val ID_REQUEST = 2
        private const val CH_STATUS = "status"
        private const val CH_MESSAGES = "messages"
        private const val CH_REQUESTS = "requests"
        private const val CH_SOS = "sos"
        private const val RC_OPEN = 1
        private const val RC_STOP = 2
        private const val RC_ACCEPT = 3
        private const val RC_REJECT = 4
        private const val RC_REQUEST = 5
        private val SOS_VIBRATION = longArrayOf(0, 800, 400, 800, 400, 800, 400, 800)
    }
}
