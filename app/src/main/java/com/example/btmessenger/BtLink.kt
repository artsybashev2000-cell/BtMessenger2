package com.example.btmessenger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Движок связи по Bluetooth (RFCOMM). Живёт всё время работы приложения, а держит его
 * в памяти [LinkService] — поэтому связь больше не рвётся при сворачивании,
 * повороте экрана, смене шрифта и т. п.
 *
 * Вся логика выполняется на одном фоновом потоке [h], поэтому состояние не нужно
 * защищать блокировками. Чтение/запись сокетов — на отдельных потоках.
 * Слушатели вызываются на главном потоке.
 */
@SuppressLint("MissingPermission")
class BtLink(
    private val ctx: Context,
    private val prefs: Prefs,
    private val repo: ChatRepository
) {
    data class PeerInfo(val address: String, val name: String, val role: String, val deviceName: String) {
        val title: String get() = displayName(name, deviceName, address)
    }

    enum class OffReason { STOPPED, NO_ADAPTER, NO_PERMISSION, BLUETOOTH_OFF }

    sealed class State {
        data class Off(val reason: OffReason) : State()

        /** Ждём входящих подключений. */
        object Idle : State()

        /** Устанавливаем соединение (attempt > 0 — автоматическое переподключение). */
        data class Connecting(val peer: PeerInfo, val attempt: Int, val auto: Boolean) : State()

        /** Соединились, ждём, пока собеседник примет подключение. */
        data class WaitingAccept(val peer: PeerInfo) : State()

        /** Кто-то незнакомый хочет подключиться — нужно решение пользователя. */
        data class IncomingRequest(val peer: PeerInfo) : State()

        data class Connected(val peer: PeerInfo) : State()

        val peerAddress: String?
            get() = when (this) {
                is Connecting -> peer.address
                is WaitingAccept -> peer.address
                is IncomingRequest -> peer.address
                is Connected -> peer.address
                else -> null
            }
    }

    interface Listener {
        fun onStateChanged(state: State) {}
        fun onMessagesChanged(peer: String) {}
        fun onIncoming(message: ChatMessage, peer: PeerInfo) {}
        fun onNotice(text: String) {}
    }

    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val thread = HandlerThread("bt-link").apply { start() }
    private val h = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Текущее состояние (можно читать с любого потока). */
    @Volatile
    var state: State = State.Off(if (adapter == null) OffReason.NO_ADAPTER else OffReason.STOPPED)
        private set

    /** Адрес чата, открытого на экране (для него не нужны уведомления и счётчик непрочитанных). */
    @Volatile
    var visibleChat: String? = null

    val hasAdapter: Boolean get() = adapter != null

    // ---------- Состояние: трогать только на потоке h ----------
    private var started = false
    private var receiverRegistered = false
    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectingTo: String? = null
    private val conns = mutableListOf<Conn>()
    private var active: Conn? = null
    private var activeSince = 0L
    private var connSeq = 0
    private var reconnectTarget: String? = null
    private var reconnectAttempt = 0
    private var reconnectStartedAt = 0L

    // ---------- Публичные методы (можно вызывать с главного потока) ----------

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun start() {
        h.post { doStart() }
    }

    fun stop() {
        h.post { doStop() }
    }

    /** Подключиться к телефону (текущее соединение с другим собеседником будет закрыто). */
    fun connect(address: String) {
        h.post { doConnect(address) }
    }

    /** Отключиться осознанно: собеседник получит BYE и не будет переподключаться. */
    fun disconnect() {
        h.post { doDisconnect() }
    }

    fun acceptIncoming() {
        h.post { doAcceptIncoming() }
    }

    fun rejectIncoming() {
        h.post { doRejectIncoming(Frame.REJECT_DECLINED) }
    }

    /** Перечитать разрешения/состояние адаптера (например, после выдачи разрешений). */
    fun refresh() {
        h.post {
            if (started) startListening()
            publish()
        }
    }

    // Сообщения сначала сохраняются, а уходят, когда есть связь. Ничего не теряется молча.
    fun sendText(peer: String, text: String) {
        h.post { enqueue(peer, Kind.TEXT, text, null) }
    }

    fun sendGeo(peer: String, geo: GeoPoint) {
        h.post { enqueue(peer, Kind.GEO, "", geo) }
    }

    fun sendSos(peer: String, text: String, geo: GeoPoint?) {
        h.post { enqueue(peer, Kind.SOS, text, geo) }
    }

    fun clearHistory(peer: String) {
        h.post {
            repo.clearHistory(peer)
            emitMessages(peer)
        }
    }

    fun forgetPeer(peer: String) {
        h.post {
            if (active?.address == peer || connectingTo == peer) doDisconnect()
            prefs.untrust(peer)
            repo.deletePeer(peer)
            emitMessages(peer)
        }
    }

    // ---------- Запуск и остановка ----------

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            h.post { onBluetoothState(s) }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            if (started) h.postDelayed(this, TICK_MS)
        }
    }

    private val retryListen = Runnable { startListening() }

    private val reconnectRunnable = Runnable {
        val target = reconnectTarget ?: return@Runnable
        if (active != null || !bluetoothReady()) {
            cancelReconnect()
            publish()
            return@Runnable
        }
        reconnectAttempt++
        startOutgoing(target)
    }

    private fun doStart() {
        if (started) return
        started = true
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                ctx, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        }
        startListening()
        h.removeCallbacks(tick)
        h.postDelayed(tick, TICK_MS)
        publish()
    }

    private fun doStop() {
        if (!started) return
        started = false
        h.removeCallbacks(tick)
        h.removeCallbacks(retryListen)
        cancelReconnect()
        connectThread?.cancel()
        connectThread = null
        connectingTo = null
        acceptThread?.cancel()
        acceptThread = null
        leaveActive()
        for (c in conns.toList()) c.closeGracefully(Frame.Bye)
        conns.clear()
        if (receiverRegistered) {
            try {
                ctx.unregisterReceiver(btReceiver)
            } catch (e: IllegalArgumentException) {
            }
            receiverRegistered = false
        }
        publish()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun bluetoothReady(): Boolean {
        val a = adapter ?: return false
        return hasConnectPermission() && a.isEnabled
    }

    private fun onBluetoothState(s: Int) {
        when (s) {
            BluetoothAdapter.STATE_ON -> startListening()
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                val hadLink = active?.accepted == true || connectingTo != null || reconnectTarget != null
                cancelReconnect()
                connectThread?.cancel()
                connectThread = null
                connectingTo = null
                acceptThread?.cancel()
                acceptThread = null
                h.removeCallbacks(retryListen)
                active = null
                for (c in conns) c.closeNow()
                conns.clear()
                if (hadLink) notice(R.string.notice_bt_turned_off)
            }
        }
        publish()
    }

    // ---------- Приём входящих ----------

    private fun startListening() {
        if (!started || acceptThread != null || !bluetoothReady()) return
        h.removeCallbacks(retryListen)
        val server = try {
            adapter?.listenUsingInsecureRfcommWithServiceRecord(Protocol.SERVICE_NAME, Protocol.APP_UUID)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
        if (server == null) {
            h.postDelayed(retryListen, RETRY_LISTEN_MS)
            return
        }
        acceptThread = AcceptThread(server).also { it.start() }
    }

    private inner class AcceptThread(private val server: BluetoothServerSocket) : Thread("bt-accept") {
        override fun run() {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                h.post { onIncomingSocket(socket) }
            }
            h.post { onAcceptEnded(this) }
        }

        fun cancel() = closeQuietly(server)
    }

    private fun onAcceptEnded(t: AcceptThread) {
        if (acceptThread !== t) return
        acceptThread = null
        if (started && bluetoothReady()) h.postDelayed(retryListen, RETRY_LISTEN_MS)
    }

    private fun onIncomingSocket(socket: BluetoothSocket) {
        if (!started) {
            closeQuietly(socket)
            return
        }
        val address = socket.remoteDevice.address
        val c = openConn(socket, outgoing = false) ?: return
        if (isBusyFor(address)) {
            // Заняты другим собеседником — вежливо отказываем, чтобы у него было понятное сообщение.
            c.send(helloFrame())
            c.closeGracefully(Frame.Reject(Frame.REJECT_BUSY))
            return
        }
        addConn(c)
    }

    /** Заняты ли мы другим собеседником (на связи, дозваниваемся или восстанавливаем связь). */
    private fun isBusyFor(address: String): Boolean {
        val cur = active
        if (cur != null && cur.address != address) return true
        val dialing = connectingTo ?: reconnectTarget
        return dialing != null && dialing != address
    }

    // ---------- Исходящие ----------

    private fun doConnect(address: String) {
        if (!started || !bluetoothReady()) {
            notice(R.string.notice_bt_not_ready)
            publish()
            return
        }
        val cur = active
        if (cur != null) {
            if (cur.address == address) {
                publish()
                return
            }
            leaveActive()
        }
        if (connectingTo == address && connectThread != null) {
            publish() // уже дозваниваемся
            return
        }
        cancelReconnect()
        // Кому звоним сами — тому доверяем: его ответное подключение примем без вопроса.
        prefs.trust(address)
        startOutgoing(address)
    }

    private fun startOutgoing(address: String) {
        val a = adapter ?: return
        connectThread?.cancel()
        connectThread = null
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return
        }
        try {
            a.cancelDiscovery()
        } catch (e: SecurityException) {
        }
        connectingTo = address
        repo.upsertPeer(address, null, null, null, deviceNameOf(device))
        connectThread = ConnectThread(device).also { it.start() }
        publish()
    }

    private inner class ConnectThread(val device: BluetoothDevice) : Thread("bt-connect") {
        @Volatile
        private var socket: BluetoothSocket? = null

        @Volatile
        private var cancelled = false

        override fun run() {
            val s = try {
                device.createInsecureRfcommSocketToServiceRecord(Protocol.APP_UUID)
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
            socket = s
            if (s == null || cancelled) {
                s?.let { closeQuietly(it) }
                h.post { onConnectResult(this, null) }
                return
            }
            val ok = try {
                s.connect()
                true
            } catch (e: IOException) {
                false
            } catch (e: SecurityException) {
                false
            }
            if (!ok || cancelled) closeQuietly(s)
            h.post { onConnectResult(this, if (ok && !cancelled) s else null) }
        }

        fun cancel() {
            cancelled = true
            socket?.let { closeQuietly(it) }
        }
    }

    private fun onConnectResult(t: ConnectThread, socket: BluetoothSocket?) {
        if (t !== connectThread) {
            socket?.let { closeQuietly(it) }
            return
        }
        connectThread = null
        val address = t.device.address
        if (socket == null || !started) {
            socket?.let { closeQuietly(it) }
            outgoingFailed(address)
            publish()
            return
        }
        val c = openConn(socket, outgoing = true)
        if (c == null) {
            outgoingFailed(address)
            publish()
            return
        }
        addConn(c)
    }

    /** Не удалось дозвониться: либо пробуем ещё раз (автопереподключение), либо сообщаем пользователю. */
    private fun outgoingFailed(address: String) {
        if (connectingTo != address) return
        if (conns.any { it.address == address }) return // есть другое живое соединение с ним
        connectingTo = null
        if (reconnectTarget == address) {
            scheduleReconnect()
        } else {
            notice(R.string.notice_connect_failed, titleFor(address))
        }
    }

    // ---------- Автопереподключение ----------
    // Переподключается только тот, кто подключался первым, — так телефоны не мешают друг другу.

    private fun startReconnect(address: String) {
        reconnectTarget = address
        reconnectAttempt = 0
        reconnectStartedAt = SystemClock.elapsedRealtime()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val target = reconnectTarget ?: return
        if (SystemClock.elapsedRealtime() - reconnectStartedAt > RECONNECT_WINDOW_MS) {
            cancelReconnect()
            notice(R.string.notice_reconnect_gave_up, titleFor(target))
            publish()
            return
        }
        val delay = RECONNECT_DELAYS_MS[minOf(reconnectAttempt, RECONNECT_DELAYS_MS.size - 1)]
        h.removeCallbacks(reconnectRunnable)
        h.postDelayed(reconnectRunnable, delay)
        publish()
    }

    private fun cancelReconnect() {
        reconnectTarget = null
        reconnectAttempt = 0
        h.removeCallbacks(reconnectRunnable)
    }

    // ---------- Соединения ----------

    private fun openConn(socket: BluetoothSocket, outgoing: Boolean): Conn? = try {
        Conn(
            socket, outgoing,
            DataInputStream(BufferedInputStream(socket.inputStream)),
            DataOutputStream(BufferedOutputStream(socket.outputStream))
        )
    } catch (e: IOException) {
        closeQuietly(socket)
        null
    }

    private fun addConn(c: Conn) {
        conns.add(c)
        c.start()
        c.send(helloFrame())
        publish()
    }

    private fun helloFrame() = Frame.Hello(Protocol.VERSION, prefs.installId, prefs.userName, prefs.userRole)

    private fun onFrame(c: Conn, f: Frame) {
        if (c !in conns) return
        when (f) {
            is Frame.Hello -> onHello(c, f)
            is Frame.Accept -> if (c.outgoing && c.hello != null && !c.accepted) {
                c.accepted = true
                if (c === active) becomeConnected(c)
            }
            is Frame.Reject -> onReject(c, f)
            is Frame.Msg -> onMsg(c, f.message)
            is Frame.Ack -> if (repo.upgradeStatus(f.id, c.address, Status.DELIVERED)) emitMessages(c.address)
            is Frame.Ping -> c.send(Frame.Pong)
            is Frame.Pong -> {}
            is Frame.Bye -> {
                val wasLinked = c === active && c.accepted
                val title = peerInfo(c).title
                dropConn(c)
                if (c.outgoing && connectingTo == c.address) connectingTo = null
                if (reconnectTarget == c.address) cancelReconnect()
                if (wasLinked) notice(R.string.notice_peer_left, title)
                publish()
            }
            is Frame.Unknown -> {}
        }
    }

    private fun onHello(c: Conn, hello: Frame.Hello) {
        if (c.hello != null) return
        if (hello.installId.isBlank() || hello.installId == prefs.installId) {
            dropConn(c)
            publish()
            return
        }
        c.hello = hello
        repo.upsertPeer(c.address, hello.installId, hello.name, hello.role, c.deviceName)

        // Два соединения с одним телефоном: оба нажали «подключиться» одновременно или он
        // переподключился, пока старое соединение ещё не закрылось. Обе стороны выбирают
        // одно и то же по одинаковому правилу, остальное закрывают.
        val dup = conns.firstOrNull { it !== c && it.hello?.installId == hello.installId }
        if (dup != null) {
            val winner = pickWinner(c, dup)
            val loser = if (winner === c) dup else c
            dropConn(loser)
            if (winner !== c) {
                publish()
                return
            }
        }

        val cur = active
        if (cur != null && cur !== c && cur.address == c.address) {
            dropConn(cur) // тот же телефон (например, переустановил приложение), старое соединение — заменяем
        }
        if (!c.outgoing && isBusyFor(c.address)) {
            conns.remove(c)
            c.closeGracefully(Frame.Reject(Frame.REJECT_BUSY))
            publish()
            return
        }
        if (c.outgoing && active != null && active !== c) {
            dropConn(c) // пока дозванивались, связь уже установилась с кем-то ещё
            publish()
            return
        }

        active = c
        activeSince = SystemClock.elapsedRealtime()
        if (c.outgoing) {
            if (c.accepted) becomeConnected(c) else publish() // ждём ACCEPT
        } else {
            val known = prefs.isTrusted(c.address) || isBonded(c.address) ||
                connectingTo == c.address || reconnectTarget == c.address
            if (known) acceptConn(c) else publish() // IncomingRequest — решает пользователь
        }
    }

    private fun initiatorId(c: Conn): String = if (c.outgoing) prefs.installId else c.hello?.installId.orEmpty()

    private fun pickWinner(a: Conn, b: Conn): Conn {
        val ia = initiatorId(a)
        val ib = initiatorId(b)
        return when {
            ia < ib -> a
            ib < ia -> b
            else -> if (a.seq > b.seq) a else b // один инициатор — новое соединение заменяет старое
        }
    }

    private fun acceptConn(c: Conn) {
        prefs.trust(c.address)
        c.accepted = true
        c.send(Frame.Accept)
        becomeConnected(c)
    }

    private fun becomeConnected(c: Conn) {
        connectingTo = null
        cancelReconnect()
        // Встречную попытку к тому же собеседнику не обрываем: если оба нажали «подключиться»
        // одновременно, её сокет у него мог уже стать основным. Она дойдёт до HELLO, и обе
        // стороны выберут одно соединение по общему правилу (pickWinner).
        val t = connectThread
        if (t != null && t.device.address != c.address) {
            t.cancel()
            connectThread = null
        }
        publish()
        // всё, что не дошло раньше, отправляем заново (получатель отбросит повторы по id)
        for (m in repo.unacked(c.address)) sendWire(c, m)
    }

    private fun onReject(c: Conn, f: Frame.Reject) {
        val title = peerInfo(c).title
        dropConn(c)
        if (c.outgoing) {
            if (connectingTo == c.address) connectingTo = null
            cancelReconnect()
            notice(
                when (f.reason) {
                    Frame.REJECT_BUSY -> R.string.notice_peer_busy
                    Frame.REJECT_TIMEOUT -> R.string.notice_peer_no_answer
                    else -> R.string.notice_peer_rejected
                },
                title
            )
        }
        publish()
    }

    private fun onMsg(c: Conn, w: WireMessage) {
        if (c !== active || !c.accepted) return
        val m = ChatMessage(
            id = w.id, peer = c.address, fromMe = false, kind = w.kind, text = w.text, geo = w.geo,
            sentAt = w.sentAt, receivedAt = System.currentTimeMillis(), status = Status.RECEIVED
        )
        val isNew = repo.insertIncoming(m)
        c.send(Frame.Ack(w.id)) // подтверждаем и повторы — значит, прошлый ACK потерялся
        if (!isNew) return
        if (visibleChat != c.address) repo.incUnread(c.address)
        emitMessages(c.address)
        val peer = peerInfo(c)
        main.post { listeners.forEach { it.onIncoming(m, peer) } }
    }

    /** Соединение оборвалось само (не мы его закрыли). */
    private fun onConnClosed(c: Conn) {
        if (!conns.remove(c)) return
        c.closeNow()
        val wasActive = c === active
        if (wasActive) active = null
        if (wasActive && c.hello != null) {
            val title = peerInfo(c).title
            when {
                c.accepted && c.outgoing -> startReconnect(c.address)
                c.accepted -> notice(R.string.notice_link_lost, title) // он переподключится сам
                c.outgoing -> {
                    if (connectingTo == c.address) connectingTo = null
                    notice(R.string.notice_connect_failed, title)
                }
                else -> {} // просивший подключения передумал
            }
        } else if (c.outgoing) {
            outgoingFailed(c.address)
        }
        publish()
    }

    private fun doDisconnect() {
        cancelReconnect()
        connectThread?.cancel()
        connectThread = null
        connectingTo = null
        leaveActive()
        for (c in conns.toList()) c.closeGracefully(Frame.Bye)
        conns.clear()
        publish()
    }

    /** Закрыть текущее соединение, сообщив собеседнику причину. */
    private fun leaveActive() {
        val c = active ?: return
        active = null
        conns.remove(c)
        c.closeGracefully(if (c.outgoing || c.accepted) Frame.Bye else Frame.Reject(Frame.REJECT_DECLINED))
    }

    private fun doAcceptIncoming() {
        val c = active ?: return
        if (!c.outgoing && !c.accepted && c.hello != null) acceptConn(c)
    }

    private fun doRejectIncoming(reason: String) {
        val c = active ?: return
        if (c.outgoing || c.accepted) return
        active = null
        conns.remove(c)
        c.closeGracefully(Frame.Reject(reason))
        publish()
    }

    /** Закрыть без событий и без переподключения. */
    private fun dropConn(c: Conn) {
        conns.remove(c)
        if (active === c) active = null
        c.closeNow()
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        for (c in conns.toList()) {
            when {
                c.hello == null && now - c.createdAt > HELLO_TIMEOUT_MS -> onConnClosed(c)
                now - c.lastRx > DEAD_TIMEOUT_MS -> onConnClosed(c)
                now - c.lastTx > PING_INTERVAL_MS -> c.send(Frame.Ping)
            }
        }
        val c = active ?: return
        if (c.accepted) return
        if (!c.outgoing && now - activeSince > REQUEST_TIMEOUT_MS) {
            doRejectIncoming(Frame.REJECT_TIMEOUT)
        } else if (c.outgoing && now - activeSince > REQUEST_TIMEOUT_MS + 15_000) {
            val title = peerInfo(c).title
            dropConn(c)
            if (connectingTo == c.address) connectingTo = null
            cancelReconnect()
            notice(R.string.notice_peer_no_answer, title)
            publish()
        }
    }

    // ---------- Сообщения ----------

    private fun enqueue(peer: String, kind: Kind, text: String, geo: GeoPoint?) {
        val now = System.currentTimeMillis()
        val m = ChatMessage(
            id = UUID.randomUUID().toString(), peer = peer, fromMe = true, kind = kind,
            text = text.take(Protocol.MAX_TEXT), geo = geo, sentAt = now, receivedAt = now, status = Status.PENDING
        )
        repo.insertOutgoing(m)
        emitMessages(peer)
        val c = active
        if (c != null && c.accepted && c.address == peer) sendWire(c, m)
    }

    private fun sendWire(c: Conn, m: ChatMessage) {
        c.send(Frame.Msg(m.toWire())) {
            if (repo.upgradeStatus(m.id, c.address, Status.SENT)) emitMessages(c.address)
        }
    }

    // ---------- Публикация состояния ----------

    private fun computeState(): State {
        val a = adapter ?: return State.Off(OffReason.NO_ADAPTER)
        if (!started) return State.Off(OffReason.STOPPED)
        if (!hasConnectPermission()) return State.Off(OffReason.NO_PERMISSION)
        if (!a.isEnabled) return State.Off(OffReason.BLUETOOTH_OFF)
        val c = active
        if (c != null) {
            val p = peerInfo(c)
            return when {
                c.accepted -> State.Connected(p)
                c.outgoing -> State.WaitingAccept(p)
                else -> State.IncomingRequest(p)
            }
        }
        val target = connectingTo ?: reconnectTarget
        if (target != null) {
            val auto = reconnectTarget == target
            return State.Connecting(peerInfoFor(target), if (auto) reconnectAttempt + 1 else 0, auto)
        }
        return State.Idle
    }

    private fun publish() {
        val s = computeState()
        if (s == state) return
        state = s
        main.post { listeners.forEach { it.onStateChanged(s) } }
    }

    private fun emitMessages(peer: String) {
        main.post { listeners.forEach { it.onMessagesChanged(peer) } }
    }

    private fun notice(@StringRes res: Int, vararg args: Any) {
        val text = ctx.getString(res, *args)
        main.post { listeners.forEach { it.onNotice(text) } }
    }

    private fun peerInfo(c: Conn): PeerInfo {
        val hello = c.hello ?: return peerInfoFor(c.address)
        return PeerInfo(c.address, hello.name, hello.role, c.deviceName)
    }

    private fun peerInfoFor(address: String): PeerInfo {
        val r = repo.peer(address)
        return PeerInfo(address, r?.name.orEmpty(), r?.role.orEmpty(), r?.deviceName.orEmpty())
    }

    private fun titleFor(address: String) = peerInfoFor(address).title

    private fun isBonded(address: String): Boolean = try {
        adapter?.bondedDevices?.any { it.address == address } == true
    } catch (e: SecurityException) {
        false
    }

    private fun deviceNameOf(d: BluetoothDevice): String = try {
        d.name.orEmpty()
    } catch (e: SecurityException) {
        ""
    }

    private fun closeQuietly(c: Closeable) {
        try {
            c.close()
        } catch (e: IOException) {
        }
    }

    /** Одно соединение. Читает в своём потоке, пишет через однопоточный исполнитель. */
    private inner class Conn(
        val socket: BluetoothSocket,
        val outgoing: Boolean,
        private val inp: DataInputStream,
        private val out: DataOutputStream
    ) {
        val seq = ++connSeq
        val address: String = socket.remoteDevice.address
        val deviceName: String = deviceNameOf(socket.remoteDevice)
        val createdAt = SystemClock.elapsedRealtime()
        var hello: Frame.Hello? = null
        var accepted = false

        @Volatile
        var lastRx = createdAt

        @Volatile
        var lastTx = createdAt

        @Volatile
        private var closing = false
        private val writer = Executors.newSingleThreadExecutor()
        private val reader = Thread({ readLoop() }, "bt-read")

        fun start() = reader.start()

        fun send(frame: Frame, onWritten: (() -> Unit)? = null) {
            if (closing) return
            try {
                writer.execute {
                    try {
                        Protocol.write(out, frame)
                        lastTx = SystemClock.elapsedRealtime()
                        if (onWritten != null) h.post { onWritten() }
                    } catch (e: IOException) {
                        closeQuietly(socket) // поток чтения заметит и сообщит об обрыве
                    }
                }
            } catch (e: RejectedExecutionException) {
            }
        }

        /** Отправить последний кадр (BYE/REJECT) и закрыть. */
        fun closeGracefully(last: Frame?) {
            if (closing) return
            closing = true
            try {
                writer.execute {
                    if (last != null) {
                        try {
                            Protocol.write(out, last)
                            Thread.sleep(300) // дать кадру уйти, прежде чем рвать канал
                        } catch (e: IOException) {
                        } catch (e: InterruptedException) {
                        }
                    }
                    closeQuietly(socket)
                }
                writer.shutdown()
            } catch (e: RejectedExecutionException) {
                closeQuietly(socket)
            }
        }

        fun closeNow() {
            closing = true
            writer.shutdownNow()
            closeQuietly(socket)
        }

        private fun readLoop() {
            try {
                while (true) {
                    val f = Protocol.read(inp)
                    lastRx = SystemClock.elapsedRealtime()
                    h.post { onFrame(this, f) }
                }
            } catch (e: IOException) {
            }
            h.post { onConnClosed(this) }
        }
    }

    private companion object {
        const val TICK_MS = 5_000L
        const val PING_INTERVAL_MS = 10_000L
        const val DEAD_TIMEOUT_MS = 30_000L
        const val HELLO_TIMEOUT_MS = 15_000L
        const val REQUEST_TIMEOUT_MS = 60_000L
        const val RETRY_LISTEN_MS = 3_000L
        const val RECONNECT_WINDOW_MS = 5 * 60_000L
        val RECONNECT_DELAYS_MS = longArrayOf(1_500, 3_000, 5_000, 10_000, 20_000, 30_000)
    }
}
