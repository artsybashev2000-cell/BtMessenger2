package com.example.btmessenger

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Служба переднего плана: держит связь, пока приложение свёрнуто или экран выключен,
 * показывает постоянное уведомление и уведомления о входящих сообщениях.
 */
class LinkService : Service(), BtLink.Listener {

    private lateinit var link: BtLink
    private lateinit var notifier: Notifier

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        link = core.link
        notifier = core.notifier
        link.addListener(this)
        link.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                link.stop()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ACCEPT -> link.acceptIncoming()
            ACTION_REJECT -> link.rejectIncoming()
        }
        // startForeground при каждом запуске: так требование startForegroundService() всегда выполнено
        if (!goForeground()) {
            link.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        link.refresh()
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        val n = notifier.status(link.state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifier.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(Notifier.ID_STATUS, n)
        }
        true
    } catch (e: Exception) {
        // например, отозвали разрешение Bluetooth — без него Android 14 не даёт запустить службу
        false
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        link.removeListener(this)
        link.stop()
        notifier.cancelRequest()
        super.onDestroy()
    }

    // ---------- BtLink.Listener ----------

    override fun onStateChanged(state: BtLink.State) {
        notifier.updateStatus(state)
        if (state is BtLink.State.IncomingRequest) {
            if (!core.uiVisible) notifier.showRequest(state.peer)
        } else {
            notifier.cancelRequest()
        }
    }

    override fun onIncoming(message: ChatMessage, peer: BtLink.PeerInfo) {
        when {
            message.kind == Kind.SOS -> notifier.showSos(message, peer)
            !core.uiVisible || link.visibleChat != peer.address -> notifier.showMessage(message, peer)
        }
    }

    companion object {
        const val ACTION_STOP = "com.example.btmessenger.STOP"
        const val ACTION_ACCEPT = "com.example.btmessenger.ACCEPT"
        const val ACTION_REJECT = "com.example.btmessenger.REJECT"
    }
}
