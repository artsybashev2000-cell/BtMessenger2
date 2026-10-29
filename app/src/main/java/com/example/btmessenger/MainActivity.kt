package com.example.btmessenger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.btmessenger.BtLink.State
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), BtLink.Listener {

    companion object {
        const val EXTRA_PEER = "peer"
        private const val STATE_OPEN_CHAT = "open_chat"

        private const val M_CONNECT = 1
        private const val M_DISCONNECT = 2
        private const val M_SOS = 3
        private const val M_CLEAR = 4
        private const val M_VISIBLE = 5
        private const val M_REFRESH = 6
        private const val M_SHOW_ALL = 7
        private const val M_PROFILE = 8
        private const val M_EXIT = 9
    }

    private val link get() = core.link
    private val prefs get() = core.prefs
    private val repo get() = core.repo

    private var btAdapter: BluetoothAdapter? = null
    private lateinit var location: LocationHelper
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var btnBack: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var btnSos: TextView
    private lateinit var headerAvatar: TextView
    private lateinit var headerTitle: TextView
    private lateinit var headerSubtitle: TextView
    private lateinit var listPanel: View
    private lateinit var chatPanel: View
    private lateinit var banner: View
    private lateinit var bannerDot: View
    private lateinit var bannerText: TextView
    private lateinit var bannerAction: TextView
    private lateinit var fabScan: ImageButton
    private lateinit var homeList: RecyclerView
    private lateinit var messageList: RecyclerView
    private lateinit var offlineBar: View
    private lateinit var offlineText: TextView
    private lateinit var offlineAction: TextView
    private lateinit var geoProgress: TextView
    private lateinit var input: EditText
    private lateinit var searchInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnLocation: ImageButton

    private lateinit var homeAdapter: HomeAdapter
    private lateinit var messageAdapter: MessageAdapter

    /** Адрес открытого чата (null — главный экран). */
    private var openChat: String? = null
    private var openChatRecord: PeerRecord? = null
    /** Открыть чат, как только установится связь с этим адресом (подключение начал пользователь). */
    private var openWhenConnected: String? = null
    private var lastState: State? = null
    private var resumed = false

    private var scanning = false
    private var foundThisScan = 0
    private var scanStoppedByUser = false
    private val nearby = LinkedHashMap<String, NearbyDevice>()
    private var summaries: List<PeerSummary> = emptyList()

    private var geoRequest: LocationHelper.Request? = null
    private var geoPeer: String? = null
    private var requestDialog: AlertDialog? = null
    private var requestFor: String? = null
    private var bannerHandler: (() -> Unit)? = null
    private var offlineHandler: (() -> Unit)? = null

    private var pendingBtAction: (() -> Unit)? = null
    private var pendingNeedScan = false
    private var afterLocationPermission: (() -> Unit)? = null
    private var receiverRegistered = false

    // ---------- Разрешения ----------

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            link.refresh()
            val needed = if (pendingNeedScan) scanPermissions() else corePermissions()
            if (needed.all { granted(it) }) {
                loadBonded()
                withBluetooth(pendingNeedScan, pendingBtAction)
            } else {
                pendingBtAction = null
                val forever = needed.any { !granted(it) && !shouldShowRequestPermissionRationale(it) }
                if (forever) {
                    showPermissionSettingsDialog(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.need_bt_permissions_settings
                        else R.string.need_location_for_scan_settings
                    )
                } else {
                    toast(R.string.need_bt_permissions)
                }
                renderState(link.state)
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val enableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (btAdapter?.isEnabled == true) {
                withBluetooth(pendingNeedScan, pendingBtAction)
            } else {
                pendingBtAction = null
                toast(R.string.bt_not_enabled)
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val action = afterLocationPermission
            afterLocationPermission = null
            if (result.values.any { it }) {
                action?.invoke()
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                showPermissionSettingsDialog(R.string.need_location_settings)
            } else {
                toast(R.string.need_location_permission)
            }
        }

    /** На Android 12+ нужны разрешения «Устройства поблизости»; на 11 и ниже обычные Bluetooth-разрешения выдаются при установке. */
    private fun corePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            emptyList()
        }

    /** Для поиска устройств на Android 11 и ниже нужна ещё точная геопозиция. */
    private fun scanPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) corePermissions()
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /**
     * Единая точка входа для всего, что трогает Bluetooth: сначала разрешения, потом включение
     * адаптера, потом действие. Раньше кнопки работали и без разрешений — и приложение падало.
     */
    private fun withBluetooth(needScan: Boolean = false, action: (() -> Unit)?) {
        val a = btAdapter
        if (a == null) {
            toast(R.string.no_bluetooth)
            return
        }
        val missing = (if (needScan) scanPermissions() else corePermissions()).filter { !granted(it) }
        if (missing.isNotEmpty()) {
            pendingBtAction = action
            pendingNeedScan = needScan
            val request = missing.toMutableList()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !granted(Manifest.permission.POST_NOTIFICATIONS) && !prefs.notificationsAsked
            ) {
                request += Manifest.permission.POST_NOTIFICATIONS
                prefs.notificationsAsked = true
            }
            permissionLauncher.launch(request.toTypedArray())
            return
        }
        if (!a.isEnabled) {
            pendingBtAction = action
            pendingNeedScan = needScan
            try {
                enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: ActivityNotFoundException) {
                toast(R.string.bt_not_enabled)
            } catch (e: SecurityException) {
                toast(R.string.need_bt_permissions)
            }
            return
        }
        pendingBtAction = null
        startLink()
        action?.invoke()
    }

    private fun startLink() {
        link.start() // сразу, чтобы следующее действие (connect) не обогнало запуск
        val i = Intent(this, LinkService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            // система может не дать запустить службу — связь работает, пока открыт экран
        }
        link.refresh()
        askNotificationsOnce()
    }

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (granted(Manifest.permission.POST_NOTIFICATIONS) || prefs.notificationsAsked) return
        prefs.notificationsAsked = true
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---------- Жизненный цикл ----------

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeChat() // «назад» больше не разрывает связь
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        location = LocationHelper(applicationContext)
        btAdapter = getSystemService(BluetoothManager::class.java)?.adapter

        homeAdapter = HomeAdapter(::onPeerClick, ::onPeerLongClick, ::onDeviceClick)
        homeList.layoutManager = LinearLayoutManager(this)
        homeList.adapter = homeAdapter

        messageAdapter = MessageAdapter(::onGeoClick, ::copyMessage)
        messageList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messageList.adapter = messageAdapter
        // когда открывается клавиатура, держим последнее сообщение на виду
        messageList.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom && messageAdapter.itemCount > 0) {
                messageList.post { messageList.scrollToPosition(messageAdapter.itemCount - 1) }
            }
        }

        findViewById<View>(R.id.rowVisible).setOnClickListener { makeDiscoverable() }
        fabScan.setOnClickListener { startScan() }
        btnBack.setOnClickListener { closeChat() }
        btnMore.setOnClickListener { showMenu() }
        btnSos.setOnClickListener { confirmSos() }
        btnSend.setOnClickListener { sendMessage() }
        btnLocation.setOnClickListener { sendLocation() }
        bannerAction.setOnClickListener { bannerHandler?.invoke() }
        banner.setOnClickListener { bannerHandler?.invoke() }
        offlineAction.setOnClickListener { offlineHandler?.invoke() }
        geoProgress.setOnClickListener { cancelGeo() }
        searchInput.doAfterTextChanged { rebuildHomeRows() }
        input.doAfterTextChanged { updateSendButton() }
        onBackPressedDispatcher.addCallback(this, backCallback)

        registerDiscoveryReceiver()

        val restore = savedInstanceState?.getString(STATE_OPEN_CHAT) ?: intent.getStringExtra(EXTRA_PEER)
        if (restore != null) openChatScreen(restore) else showChat(false)
        updateSendButton()

        if (btAdapter == null) return
        if (corePermissions().all { granted(it) }) loadBonded()
        if (!prefs.profileAsked) showProfileDialog(first = true) else withBluetooth(action = null)
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        btnMore = findViewById(R.id.btnMore)
        btnSos = findViewById(R.id.btnSos)
        headerAvatar = findViewById(R.id.headerAvatar)
        headerTitle = findViewById(R.id.headerTitle)
        headerSubtitle = findViewById(R.id.headerSubtitle)
        listPanel = findViewById(R.id.listPanel)
        chatPanel = findViewById(R.id.chatPanel)
        banner = findViewById(R.id.statusBanner)
        bannerDot = findViewById(R.id.bannerDot)
        bannerText = findViewById(R.id.bannerText)
        bannerAction = findViewById(R.id.bannerAction)
        fabScan = findViewById(R.id.fabScan)
        homeList = findViewById(R.id.homeList)
        messageList = findViewById(R.id.messageList)
        offlineBar = findViewById(R.id.offlineBar)
        offlineText = findViewById(R.id.offlineText)
        offlineAction = findViewById(R.id.offlineAction)
        geoProgress = findViewById(R.id.geoProgress)
        input = findViewById(R.id.input)
        searchInput = findViewById(R.id.searchInput)
        btnSend = findViewById(R.id.btnSend)
        btnLocation = findViewById(R.id.btnLocation)
    }

    override fun onStart() {
        super.onStart()
        link.addListener(this)
        lastState = link.state
        renderState(link.state)
        reloadHome()
        reloadChat()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        core.uiVisible = true
        link.visibleChat = openChat
        openChat?.let { markRead(it) }
        core.notifier.cancelRequest()
        syncRequestDialog(link.state)
    }

    override fun onPause() {
        resumed = false
        core.uiVisible = false
        link.visibleChat = null
        super.onPause()
    }

    override fun onStop() {
        link.removeListener(this)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_OPEN_CHAT, openChat)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_PEER)?.let { openChatScreen(it) }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(discoveryReceiver)
            receiverRegistered = false
        }
        if (scanning) stopScan()
        geoRequest?.cancel()
        geoRequest = null
        requestDialog?.dismiss()
        io.shutdown()
        super.onDestroy()
    }

    private fun bg(block: () -> Unit) {
        try {
            io.execute(block)
        } catch (e: RejectedExecutionException) {
        }
    }

    // ---------- BtLink.Listener ----------

    override fun onStateChanged(state: State) {
        val prev = lastState
        lastState = state
        if (state is State.Connected) {
            val addr = state.peer.address
            val wasConnected = prev is State.Connected && prev.peer.address == addr
            if (!wasConnected) {
                if (openWhenConnected == addr) {
                    openWhenConnected = null
                    if (openChat != addr) openChatScreen(addr)
                } else if (openChat != addr) {
                    toast(getString(R.string.notice_connected, state.peer.title))
                }
            }
        } else if (state is State.Idle || state is State.Off) {
            openWhenConnected = null
        }
        renderState(state)
        reloadHome()
        if (openChat != null && state.peerAddress == openChat) reloadChat()
    }

    override fun onMessagesChanged(peer: String) {
        if (peer == openChat) {
            reloadChat()
            if (resumed) markRead(peer) else reloadHome()
        } else {
            reloadHome()
        }
    }

    override fun onIncoming(message: ChatMessage, peer: BtLink.PeerInfo) {
        if (message.kind == Kind.SOS) showSosDialog(message, peer)
    }

    override fun onNotice(text: String) = toast(text)

    // ---------- Отрисовка состояния ----------

    private fun renderState(state: State) {
        if (openChat == null) renderHomeHeader(state) else renderChatHeader(state)
        renderBanner(state)
        renderOfflineBar(state)
        syncRequestDialog(state)
        fabScan.isEnabled = !scanning
        fabScan.alpha = if (scanning) 0.5f else 1f
    }

    private fun setSubtitle(text: String, accent: Boolean = false) {
        headerSubtitle.text = text
        headerSubtitle.setTextColor(
            ContextCompat.getColor(this, if (accent) R.color.tg_accent else R.color.tg_secondary)
        )
    }

    private fun renderHomeHeader(state: State) {
        headerTitle.text = getString(R.string.app_name)
        when (state) {
            is State.Off -> setSubtitle(getString(offText(state.reason)))
            is State.Idle -> setSubtitle(getString(if (scanning) R.string.status_scanning else R.string.status_idle))
            is State.Connecting -> setSubtitle(connectingText(state))
            is State.WaitingAccept -> setSubtitle(getString(R.string.status_waiting_accept, state.peer.title))
            is State.IncomingRequest -> setSubtitle(getString(R.string.status_request, state.peer.title), true)
            is State.Connected -> setSubtitle(getString(R.string.status_connected, state.peer.title), true)
        }
    }

    @StringRes
    private fun offText(r: BtLink.OffReason): Int = when (r) {
        BtLink.OffReason.NO_ADAPTER -> R.string.status_no_adapter
        BtLink.OffReason.BLUETOOTH_OFF -> R.string.status_bt_off
        BtLink.OffReason.NO_PERMISSION -> R.string.status_no_permission
        BtLink.OffReason.STOPPED -> R.string.status_stopped
    }

    private fun connectingText(s: State.Connecting): String =
        if (s.auto) getString(R.string.status_reconnecting, s.peer.title, s.attempt)
        else getString(R.string.status_connecting, s.peer.title)

    /** Сведения о собеседнике из текущего состояния, если оно про него. */
    private fun State.peerFor(address: String): BtLink.PeerInfo? = when (this) {
        is State.Connecting -> peer.takeIf { it.address == address }
        is State.WaitingAccept -> peer.takeIf { it.address == address }
        is State.IncomingRequest -> peer.takeIf { it.address == address }
        is State.Connected -> peer.takeIf { it.address == address }
        else -> null
    }

    private fun chatTitle(): String {
        val addr = openChat ?: return ""
        val live = link.state.peerFor(addr)
        return live?.takeIf { it.name.isNotBlank() }?.title
            ?: openChatRecord?.title
            ?: summaries.firstOrNull { it.address == addr }?.title
            ?: live?.title
            ?: addr
    }

    private fun renderChatHeader(state: State) {
        val addr = openChat ?: return
        val title = chatTitle()
        headerTitle.text = title
        headerAvatar.text = avatarLetter(title)
        headerAvatar.background = circle(avatarColor(addr))
        val role = state.peerFor(addr)?.role?.takeIf { it.isNotBlank() } ?: openChatRecord?.role.orEmpty()
        when {
            state is State.Connected && state.peer.address == addr ->
                setSubtitle(if (role.isBlank()) getString(R.string.chat_online) else getString(R.string.chat_online_role, role), true)
            state is State.Connecting && state.peer.address == addr ->
                setSubtitle(
                    if (state.auto) getString(R.string.chat_reconnecting, state.attempt)
                    else getString(R.string.chat_connecting)
                )
            state is State.WaitingAccept && state.peer.address == addr -> setSubtitle(getString(R.string.chat_waiting))
            else -> setSubtitle(if (role.isBlank()) getString(R.string.chat_offline) else getString(R.string.chat_offline_role, role))
        }
    }

    private fun renderBanner(state: State) {
        fun show(text: String, action: Int?, dotColor: Int, handler: (() -> Unit)?) {
            banner.visibility = View.VISIBLE
            bannerText.text = text
            bannerAction.visibility = if (action != null) View.VISIBLE else View.GONE
            if (action != null) bannerAction.setText(action)
            bannerDot.background = circle(ContextCompat.getColor(this, dotColor))
            bannerHandler = handler
        }
        when (state) {
            is State.Off -> when (state.reason) {
                BtLink.OffReason.NO_ADAPTER -> show(getString(R.string.status_no_adapter), null, R.color.sos_red, null)
                BtLink.OffReason.BLUETOOTH_OFF ->
                    show(getString(R.string.banner_bt_off), R.string.action_turn_on, R.color.sos_red) { withBluetooth(action = null) }
                BtLink.OffReason.NO_PERMISSION ->
                    show(getString(R.string.banner_no_permission), R.string.action_allow, R.color.sos_red) { withBluetooth(action = null) }
                BtLink.OffReason.STOPPED ->
                    show(getString(R.string.banner_stopped), R.string.action_turn_on, R.color.tg_secondary) { withBluetooth(action = null) }
            }
            is State.Idle -> {
                banner.visibility = View.GONE
                bannerHandler = null
            }
            is State.Connecting -> show(connectingText(state), R.string.cancel, R.color.warning) { link.disconnect() }
            is State.WaitingAccept ->
                show(getString(R.string.status_waiting_accept, state.peer.title), R.string.cancel, R.color.warning) { link.disconnect() }
            is State.IncomingRequest ->
                show(getString(R.string.status_request, state.peer.title), R.string.action_answer, R.color.tg_accent) {
                    requestDialog?.dismiss()
                    requestDialog = null
                    syncRequestDialog(state)
                }
            is State.Connected ->
                show(getString(R.string.status_connected, state.peer.title), R.string.action_open, R.color.online) {
                    openChatScreen(state.peer.address)
                }
        }
        // в чате баннер не нужен — там своя строка состояния
        if (openChat != null) banner.visibility = View.GONE
    }

    private fun renderOfflineBar(state: State) {
        val addr = openChat
        if (addr == null) {
            offlineBar.visibility = View.GONE
            return
        }
        fun show(text: String, action: Int?, handler: (() -> Unit)?) {
            offlineBar.visibility = View.VISIBLE
            offlineText.text = text
            offlineAction.visibility = if (action != null) View.VISIBLE else View.GONE
            if (action != null) offlineAction.setText(action)
            offlineHandler = handler
        }
        val connectHere: () -> Unit = { withBluetooth { link.connect(addr) } }
        when {
            state is State.Connected && state.peer.address == addr -> {
                offlineBar.visibility = View.GONE
                offlineHandler = null
            }
            (state is State.Connecting || state is State.WaitingAccept) && state.peerAddress == addr ->
                show(getString(R.string.offline_connecting), R.string.cancel) { link.disconnect() }
            state is State.Off && state.reason == BtLink.OffReason.NO_ADAPTER ->
                show(getString(R.string.status_no_adapter), null, null)
            state is State.Off -> show(getString(R.string.offline_bt_off), R.string.action_connect, connectHere)
            state.peerAddress != null && state.peerAddress != addr ->
                show(getString(R.string.offline_busy, state.peerFor(state.peerAddress ?: "")?.title ?: ""), R.string.action_switch, connectHere)
            else -> show(getString(R.string.offline_idle), R.string.action_connect, connectHere)
        }
    }

    private fun syncRequestDialog(state: State) {
        if (state is State.IncomingRequest) {
            if (!resumed) return
            if (requestDialog?.isShowing == true && requestFor == state.peer.address) return
            requestDialog?.dismiss()
            requestFor = state.peer.address
            requestDialog = AlertDialog.Builder(this)
                .setTitle(R.string.request_title)
                .setMessage(Format.peerDescription(this, state.peer))
                .setPositiveButton(R.string.accept) { _, _ ->
                    openWhenConnected = state.peer.address
                    link.acceptIncoming()
                }
                .setNegativeButton(R.string.reject) { _, _ -> link.rejectIncoming() }
                .setCancelable(false)
                .show()
        } else {
            requestDialog?.dismiss()
            requestDialog = null
            requestFor = null
        }
    }

    // ---------- Главный экран ----------

    private fun reloadHome() {
        bg {
            val list = repo.summaries()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                summaries = list
                rebuildHomeRows()
                if (openChat != null) renderChatHeader(link.state)
            }
        }
    }

    private fun matches(q: String, vararg fields: String) =
        q.isEmpty() || fields.any { it.contains(q, ignoreCase = true) }

    private fun rebuildHomeRows() {
        val q = searchInput.text.toString().trim()
        val online = (link.state as? State.Connected)?.peer?.address
        val known = summaries.mapTo(HashSet()) { it.address }
        val rows = ArrayList<HomeRow>()

        val peers = summaries.filter { matches(q, it.title, it.address, it.role) }
        if (peers.isNotEmpty()) {
            rows += HomeRow.Header(getString(R.string.section_chats))
            peers.forEach { rows += HomeRow.Peer(it, it.address == online, nearby[it.address]?.rssi) }
        }

        val showAll = prefs.showAllDevices
        val devices = nearby.values
            .filter { it.address !in known && (showAll || it.looksLikePhone()) && matches(q, it.title, it.address) }
            .sortedWith(
                compareByDescending<NearbyDevice> { it.rssi != null }
                    .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                    .thenByDescending { it.bonded }
            )
        rows += HomeRow.Header(getString(if (scanning) R.string.section_devices_scanning else R.string.section_devices))
        if (devices.isEmpty()) {
            rows += HomeRow.Hint(
                getString(
                    when {
                        btAdapter == null -> R.string.status_no_adapter
                        scanning -> R.string.devices_scanning_hint
                        q.isNotEmpty() -> R.string.devices_not_matched
                        else -> R.string.devices_empty_hint
                    }
                )
            )
        } else {
            devices.forEach { rows += HomeRow.Device(it) }
        }
        homeAdapter.submitList(rows)
    }

    private fun onPeerClick(s: PeerSummary) {
        openChatScreen(s.address)
        // если ни с кем не на связи — сразу подключаемся
        if (link.state is State.Idle) link.connect(s.address)
    }

    private fun onPeerLongClick(s: PeerSummary) {
        val items = arrayOf<CharSequence>(getString(R.string.menu_connect), getString(R.string.menu_forget))
        AlertDialog.Builder(this)
            .setTitle(s.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> withBluetooth {
                        openWhenConnected = s.address
                        link.connect(s.address)
                    }
                    1 -> confirmForget(s)
                }
            }
            .show()
    }

    private fun confirmForget(s: PeerSummary) {
        AlertDialog.Builder(this)
            .setTitle(R.string.forget_title)
            .setMessage(getString(R.string.forget_text, s.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (openChat == s.address) closeChat()
                link.forgetPeer(s.address)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onDeviceClick(d: NearbyDevice) = withBluetooth {
        stopScan()
        openWhenConnected = d.address
        link.connect(d.address)
    }

    // ---------- Поиск устройств ----------

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = intent.device() ?: return
                    val rssiRaw = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val rssi = if (rssiRaw == Short.MIN_VALUE) null else rssiRaw.toInt()
                    if (!nearby.containsKey(d.address) || nearby[d.address]?.rssi == null) foundThisScan++
                    putDevice(d, intent.getStringExtra(BluetoothDevice.EXTRA_NAME), rssi, intent.btClass()?.majorDeviceClass)
                    rebuildHomeRows()
                }
                BluetoothDevice.ACTION_NAME_CHANGED -> {
                    val d = intent.device() ?: return
                    val old = nearby[d.address] ?: return
                    val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    if (!name.isNullOrBlank()) nearby[d.address] = old.copy(name = name)
                    rebuildHomeRows()
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    scanning = true
                    renderState(link.state)
                    rebuildHomeRows()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    val wasScanning = scanning
                    scanning = false
                    renderState(link.state)
                    rebuildHomeRows()
                    if (wasScanning && !scanStoppedByUser && foundThisScan == 0) toast(R.string.scan_nothing_found)
                    scanStoppedByUser = false
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.device(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @Suppress("DEPRECATION")
    private fun Intent.btClass(): BluetoothClass? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_CLASS)
        }

    private fun registerDiscoveryReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(this, discoveryReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun putDevice(d: BluetoothDevice, nameHint: String?, rssi: Int?, majorClass: Int?) {
        val old = nearby[d.address]
        val name = try {
            d.name
        } catch (e: SecurityException) {
            null
        }
        val bonded = try {
            d.bondState == BluetoothDevice.BOND_BONDED
        } catch (e: SecurityException) {
            old?.bonded ?: false
        }
        val cls = majorClass ?: try {
            d.bluetoothClass?.majorDeviceClass
        } catch (e: SecurityException) {
            null
        }
        nearby[d.address] = NearbyDevice(
            address = d.address,
            name = name?.takeIf { it.isNotBlank() } ?: nameHint?.takeIf { it.isNotBlank() } ?: old?.name.orEmpty(),
            bonded = bonded,
            majorClass = cls ?: old?.majorClass,
            rssi = rssi ?: old?.rssi
        )
    }

    private fun loadBonded() {
        val a = btAdapter ?: return
        val bonded = try {
            a.bondedDevices
        } catch (e: SecurityException) {
            null
        } ?: return
        bonded.forEach { putDevice(it, null, null, null) }
        rebuildHomeRows()
    }

    private fun startScan() = withBluetooth(needScan = true) {
        // На Android 11 и ниже поиск без включённой геолокации молча ничего не находит
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !location.isEnabled()) {
            showLocationSettingsDialog(R.string.location_off_for_scan)
            return@withBluetooth
        }
        val a = btAdapter ?: return@withBluetooth
        try {
            if (a.isDiscovering) {
                scanStoppedByUser = true
                a.cancelDiscovery()
            }
            if (a.startDiscovery()) {
                scanning = true
                foundThisScan = 0
                scanStoppedByUser = false
                nearby.values.removeAll { !it.bonded }
                renderState(link.state)
                rebuildHomeRows()
            } else {
                toast(R.string.scan_failed)
            }
        } catch (e: SecurityException) {
            toast(R.string.need_bt_permissions)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanStoppedByUser = true
        try {
            btAdapter?.cancelDiscovery()
        } catch (e: SecurityException) {
        }
    }

    private fun makeDiscoverable() = withBluetooth {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.visible_failed)
        } catch (e: SecurityException) {
            toast(R.string.need_bt_permissions)
        }
    }

    // ---------- Чат ----------

    private fun openChatScreen(address: String) {
        if (openChat != address) {
            messageAdapter.submitList(emptyList())
            input.setText("")
        }
        openChat = address
        openChatRecord = null
        if (resumed) {
            link.visibleChat = address
            markRead(address)
        }
        core.notifier.cancelChat(address)
        showChat(true)
        reloadChat(scrollToEnd = true)
    }

    private fun closeChat() {
        openChat = null
        openChatRecord = null
        link.visibleChat = null
        showChat(false)
        reloadHome()
    }

    private fun showChat(show: Boolean) {
        backCallback.isEnabled = show
        listPanel.visibility = if (show) View.GONE else View.VISIBLE
        chatPanel.visibility = if (show) View.VISIBLE else View.GONE
        btnBack.visibility = if (show) View.VISIBLE else View.GONE
        headerAvatar.visibility = if (show) View.VISIBLE else View.GONE
        btnSos.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) hideKeyboard()
        updateGeoProgress()
        renderState(link.state)
    }

    private fun markRead(peer: String) {
        bg {
            repo.clearUnread(peer)
            runOnUiThread { if (!isDestroyed) reloadHome() }
        }
    }

    private fun reloadChat(scrollToEnd: Boolean = false) {
        val peer = openChat ?: return
        bg {
            val list = repo.messages(peer)
            val record = repo.peer(peer)
            runOnUiThread {
                if (isDestroyed || openChat != peer) return@runOnUiThread
                openChatRecord = record
                renderChatHeader(link.state)
                val lm = messageList.layoutManager as LinearLayoutManager
                val atEnd = scrollToEnd || lm.findLastVisibleItemPosition() >= messageAdapter.itemCount - 2
                messageAdapter.submitList(list) {
                    if (atEnd && list.isNotEmpty()) messageList.scrollToPosition(list.size - 1)
                }
            }
        }
    }

    /**
     * Сообщение сначала сохраняется (значок часов), потом уходит. Раньше при обрыве связи
     * оно пропадало вместе с очищенным полем ввода.
     */
    private fun sendMessage() {
        val peer = openChat ?: return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        link.sendText(peer, text)
        input.setText("")
        if (link.state is State.Idle) link.connect(peer)
    }

    private fun updateSendButton() {
        val hasText = input.text.isNotBlank()
        btnSend.visibility = if (hasText) View.VISIBLE else View.GONE
        btnLocation.visibility = if (hasText) View.GONE else View.VISIBLE
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun copyMessage(m: ChatMessage) {
        val text = when (m.kind) {
            Kind.TEXT -> m.text
            else -> listOfNotNull(m.text.takeIf { it.isNotBlank() }, m.geo?.let { Format.coords(it) }).joinToString("\n")
        }
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        toast(R.string.copied)
    }

    // ---------- Геопозиция ----------

    private fun requestLocationPermission(then: () -> Unit) {
        afterLocationPermission = then
        locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    private fun sendLocation() {
        val peer = openChat ?: return
        if (geoRequest != null) return // уже определяем — повторные нажатия не плодят сообщения
        if (!location.hasPermission()) {
            requestLocationPermission { sendLocation() }
            return
        }
        if (!location.isEnabled()) {
            showLocationSettingsDialog(R.string.location_off_for_geo)
            return
        }
        geoPeer = peer
        val req = location.requestFix { geo ->
            geoRequest = null
            geoPeer = null
            updateGeoProgress()
            if (geo == null) {
                toast(R.string.geo_failed)
            } else {
                link.sendGeo(peer, geo)
                if (link.state is State.Idle) link.connect(peer)
            }
        }
        geoRequest = req
        if (req == null) geoPeer = null
        updateGeoProgress()
    }

    private fun cancelGeo() {
        geoRequest?.cancel()
        geoRequest = null
        geoPeer = null
        updateGeoProgress()
    }

    private fun updateGeoProgress() {
        val busy = geoRequest != null
        geoProgress.visibility = if (busy && openChat != null && openChat == geoPeer) View.VISIBLE else View.GONE
        btnLocation.isEnabled = !busy
        btnLocation.alpha = if (busy) 0.4f else 1f
    }

    private fun onGeoClick(m: ChatMessage) {
        val g = m.geo ?: return
        val label = if (m.fromMe) getString(R.string.my_location) else chatTitle()
        val items = arrayOf<CharSequence>(
            getString(R.string.geo_direction),
            getString(R.string.geo_open_maps),
            getString(R.string.geo_open_yandex),
            getString(R.string.geo_copy)
        )
        AlertDialog.Builder(this)
            .setTitle(Format.coords(g))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDirection(g, label, m.sentAt)
                    1 -> MapLinks.openGeo(this, g, label)
                    2 -> MapLinks.openYandex(this, g)
                    3 -> MapLinks.copy(this, g)
                }
            }
            .show()
    }

    private fun showDirection(g: GeoPoint, label: String, sentAt: Long) {
        if (!location.hasPermission()) {
            requestLocationPermission { showDirection(g, label, sentAt) }
            return
        }
        if (!location.isEnabled()) {
            showLocationSettingsDialog(R.string.location_off_for_direction)
            return
        }
        DirectionDialog(this, g, label, sentAt).show()
    }

    // ---------- SOS ----------

    private fun sosTarget(): String? = openChat ?: (link.state as? State.Connected)?.peer?.address

    private fun confirmSos() {
        val peer = sosTarget()
        if (peer == null) {
            toast(R.string.sos_no_peer)
            return
        }
        val title = if (peer == openChat) chatTitle()
        else (link.state as? State.Connected)?.peer?.title ?: peer
        AlertDialog.Builder(this)
            .setTitle(R.string.sos_confirm_title)
            .setMessage(getString(R.string.sos_confirm_text, title))
            .setPositiveButton(R.string.sos_send) { _, _ -> sendSos(peer) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sendSos(peer: String) {
        // Тревога уходит сразу — с последней известной точкой (получатель увидит её возраст),
        // а следом отдельным сообщением — свежие координаты.
        link.sendSos(peer, getString(R.string.sos_default_text), location.lastKnown())
        if (link.state is State.Idle) link.connect(peer)
        toast(R.string.sos_sent)
        val followUp = {
            location.requestFix { geo -> if (geo != null) link.sendGeo(peer, geo) }
            Unit
        }
        when {
            !location.hasPermission() -> requestLocationPermission(followUp)
            location.isEnabled() -> followUp()
        }
    }

    private fun showSosDialog(m: ChatMessage, peer: BtLink.PeerInfo) {
        val b = AlertDialog.Builder(this)
            .setTitle(getString(R.string.sos_from, peer.title))
            .setMessage(Format.sosSummary(this, m))
            .setNeutralButton(R.string.open_chat) { _, _ -> openChatScreen(peer.address) }
            .setNegativeButton(R.string.close, null)
        val g = m.geo
        if (g != null) b.setPositiveButton(R.string.geo_direction) { _, _ -> showDirection(g, peer.title, m.sentAt) }
        b.show()
    }

    // ---------- Меню и диалоги ----------

    private fun showMenu() {
        val popup = PopupMenu(this, btnMore)
        val menu = popup.menu
        val st = link.state
        val chat = openChat
        if (chat != null) {
            val linked = st.peerAddress == chat && st !is State.IncomingRequest
            if (linked) menu.add(0, M_DISCONNECT, 0, R.string.menu_disconnect)
            else menu.add(0, M_CONNECT, 0, R.string.menu_connect)
            menu.add(0, M_SOS, 1, R.string.menu_sos)
            menu.add(0, M_CLEAR, 2, R.string.menu_clear_history)
        } else {
            menu.add(0, M_VISIBLE, 0, R.string.menu_visible)
            menu.add(0, M_REFRESH, 1, R.string.menu_refresh)
            if (st.peerAddress != null && st !is State.IncomingRequest) menu.add(0, M_DISCONNECT, 2, R.string.menu_disconnect)
            if (st is State.Connected) menu.add(0, M_SOS, 3, R.string.menu_sos)
            menu.add(0, M_SHOW_ALL, 4, R.string.menu_show_all).apply {
                isCheckable = true
                isChecked = prefs.showAllDevices
            }
            menu.add(0, M_PROFILE, 5, R.string.menu_profile)
            menu.add(0, M_EXIT, 6, R.string.menu_exit)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                M_CONNECT -> chat?.let { addr -> withBluetooth { link.connect(addr) } }
                M_DISCONNECT -> link.disconnect()
                M_SOS -> confirmSos()
                M_CLEAR -> chat?.let { confirmClear(it) }
                M_VISIBLE -> makeDiscoverable()
                M_REFRESH -> withBluetooth {
                    loadBonded()
                    reloadHome()
                }
                M_SHOW_ALL -> {
                    prefs.showAllDevices = !prefs.showAllDevices
                    rebuildHomeRows()
                }
                M_PROFILE -> showProfileDialog(first = false)
                M_EXIT -> exitApp()
            }
            true
        }
        popup.show()
    }

    private fun confirmClear(peer: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_text)
            .setPositiveButton(R.string.delete) { _, _ -> link.clearHistory(peer) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exitApp() {
        stopService(Intent(this, LinkService::class.java))
        link.stop()
        finish()
    }

    private fun showProfileDialog(first: Boolean) {
        val v = layoutInflater.inflate(R.layout.dialog_profile, null)
        val name = v.findViewById<EditText>(R.id.profileName)
        val role = v.findViewById<EditText>(R.id.profileRole)
        name.setText(prefs.userName)
        role.setText(prefs.userRole)
        AlertDialog.Builder(this)
            .setTitle(if (first) R.string.profile_title_first else R.string.profile_title)
            .setView(v)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.userName = name.text.toString()
                prefs.userRole = role.text.toString()
                if (!first) toast(R.string.profile_saved)
            }
            .setNegativeButton(if (first) R.string.later else R.string.cancel, null)
            .setOnDismissListener {
                if (first) {
                    prefs.profileAsked = true
                    withBluetooth(action = null)
                }
            }
            .show()
    }

    private fun showPermissionSettingsDialog(@StringRes text: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.permissions_title)
            .setMessage(text)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                } catch (e: ActivityNotFoundException) {
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLocationSettingsDialog(@StringRes text: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.location_off_title)
            .setMessage(text)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (e: ActivityNotFoundException) {
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(@StringRes res: Int) = toast(getString(res))

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
