package app.scrcpya2a.ui

import android.app.Application
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.scrcpya2a.adb.AdbDiscovery
import app.scrcpya2a.adb.AdbErrors
import app.scrcpya2a.adb.AdbService
import app.scrcpya2a.adb.AppAdbManager
import app.scrcpya2a.adb.ConnectionHub
import app.scrcpya2a.session.ControlChannel
import app.scrcpya2a.session.ControlMessages
import app.scrcpya2a.session.DeviceTools
import app.scrcpya2a.session.GamepadForwarder
import app.scrcpya2a.session.RemoteDisplay
import app.scrcpya2a.session.ScrcpySession
import app.scrcpya2a.session.SessionConfig
import app.scrcpya2a.session.SessionManager
import app.scrcpya2a.session.SessionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Connect, Home, Mirror }

/** Um aparelho conectado (uma conexão ADB própria). O [id] é o IP. */
data class DeviceInfo(val id: String, val host: String, val port: Int, val name: String)

/** Uma linha da lista de monitores: uma sessão ativa ou uma tela que existe no outro aparelho. */
data class MonitorRow(
    val key: String,
    val displayId: Int, // -1 enquanto desconhecido
    val width: Int,
    val height: Int,
    val title: String,
    val detail: String,
    val sessionId: Int?, // não nulo = este app já está com o monitor aberto
    val kind: SessionManager.Kind?,
    val isMain: Boolean,
) {
    val active: Boolean get() = sessionId != null
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("connection", 0)
    val manager: SessionManager = SessionManager.get(app)

    // ---- formulário de conexão ----
    var host by mutableStateOf(prefs.getString("host", "") ?: "")
    var connectPort by mutableStateOf(prefs.getString("connectPort", "") ?: "")
    var pairCode by mutableStateOf("")
    var manualPairTarget by mutableStateOf("") // "ip:porta" da janela de pareamento (só se não for detectada)

    // ---- opções ----
    var audioEnabled by mutableStateOf(prefs.getBoolean("audio", true))
        private set
    var closeOldServers by mutableStateOf(prefs.getBoolean("closeOld", true))
        private set
    var gamepadEnabled by mutableStateOf(prefs.getBoolean("gamepad", true))
        private set

    // ---- estado geral ----
    var screen by mutableStateOf(Screen.Connect)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Deixa o cartão de pareamento em destaque (ex.: a conexão falhou porque o aparelho não está pareado). */
    var pairingHighlight by mutableStateOf(false)
        private set

    // ---- aparelhos conectados ----
    var devices by mutableStateOf<List<DeviceInfo>>(emptyList())
        private set
    var currentDeviceId by mutableStateOf<String?>(null)
        private set
    private val adbs = HashMap<String, AppAdbManager>() // uma conexão ADB por aparelho

    val connected: Boolean get() = devices.isNotEmpty()
    val currentDevice: DeviceInfo? get() = devices.firstOrNull { it.id == currentDeviceId }

    fun deviceLabel(id: String?): String = devices.firstOrNull { it.id == id }?.name ?: id.orEmpty()

    // ---- descoberta automática ----
    var discoveredDevices by mutableStateOf<List<AdbService>>(emptyList())
        private set
    var pairingServices by mutableStateOf<List<AdbService>>(emptyList())
        private set
    var discovering by mutableStateOf(false)
        private set
    private var discovery: AdbDiscovery? = null

    // ---- monitores (por aparelho) ----
    private var remoteByDevice by mutableStateOf<Map<String, List<RemoteDisplay>>>(emptyMap())
    private var loadedDevices by mutableStateOf<Set<String>>(emptySet())
    private var errorsByDevice by mutableStateOf<Map<String, String>>(emptyMap())

    val remoteDisplays: List<RemoteDisplay> get() = currentDeviceId?.let { remoteByDevice[it] } ?: emptyList()
    val displaysLoaded: Boolean get() = currentDeviceId?.let { it in loadedDevices } ?: false
    val displaysError: String? get() = currentDeviceId?.let { errorsByDevice[it] }

    // ---- visualização ----
    var viewingId by mutableStateOf<Int?>(null)
        private set
    var muted by mutableStateOf(false)
        private set
    var touchpad by mutableStateOf(false)
        private set
    var fullscreen by mutableStateOf(false)
        private set

    /** Cursor do touchpad, em coordenadas do vídeo (-1 = oculto). */
    var cursorX by mutableFloatStateOf(-1f)
        private set
    var cursorY by mutableFloatStateOf(-1f)
        private set

    private var surface: Surface? = null
    private val touchpadController = TouchpadController()
    private val gamepad = GamepadForwarder()
    private var mouseButtons = 0

    init {
        manager.onSessionEnded = { id, err -> handleSessionEnded(id, err) }
        touchpadController.onCursor = { x, y ->
            cursorX = x
            cursorY = y
        }
    }

    /** Todas as sessões ativas, de todos os aparelhos. */
    val sessions: List<SessionManager.Info> get() = manager.infos

    /** A sessão que está na tela de espelhamento. */
    val viewing: SessionManager.Info? get() = sessions.firstOrNull { it.id == viewingId }

    private fun viewingSession(): ScrcpySession? = manager.get(viewingId)

    /** Monitores do aparelho selecionado: sessões ativas dele + telas que existem nele. */
    val monitorRows: List<MonitorRow>
        get() {
            val active = sessions.filter { it.deviceId == currentDeviceId }
            val remote = remoteDisplays
            val rows = ArrayList<MonitorRow>()
            for (s in active) {
                val match = remote.firstOrNull { it.id == s.displayId }
                val title = when (s.kind) {
                    SessionManager.Kind.Main -> "Tela principal"
                    SessionManager.Kind.Virtual -> "Monitor virtual"
                    SessionManager.Kind.Existing -> "Monitor"
                }
                rows += MonitorRow(
                    key = "s${s.id}", displayId = s.displayId,
                    width = match?.width ?: s.width, height = match?.height ?: s.height,
                    title = title, detail = if (s.streaming) "Ativo" else s.status,
                    sessionId = s.id, kind = s.kind, isMain = s.kind == SessionManager.Kind.Main,
                )
            }
            val owned = active.map { it.displayId }.toSet()
            for (d in remote) {
                if (d.id in owned) continue
                rows += MonitorRow(
                    key = "d${d.id}", displayId = d.id, width = d.width, height = d.height,
                    title = if (d.isMain) "Tela principal" else "Monitor",
                    detail = if (d.isMain) "Disponível" else "Sem dono",
                    sessionId = null, kind = null, isMain = d.isMain,
                )
            }
            // A tela principal (#0) sempre aparece, mesmo se a lista do outro aparelho falhar.
            if (rows.none { it.isMain }) {
                rows += MonitorRow(
                    key = "d0", displayId = 0, width = 0, height = 0, title = "Tela principal",
                    detail = "Disponível", sessionId = null, kind = null, isMain = true,
                )
            }
            return rows.sortedBy { if (it.displayId < 0) Int.MAX_VALUE else it.displayId }
        }

    /** Quantas sessões ativas cada aparelho tem (para os rótulos do seletor de aparelhos). */
    fun activeCount(deviceId: String): Int = sessions.count { it.deviceId == deviceId }

    private fun saveForm() {
        prefs.edit().putString("host", host.trim()).putString("connectPort", connectPort.trim()).apply()
    }

    fun updateAudioEnabled(value: Boolean) {
        audioEnabled = value
        prefs.edit().putBoolean("audio", value).apply()
    }

    fun updateCloseOldServers(value: Boolean) {
        closeOldServers = value
        prefs.edit().putBoolean("closeOld", value).apply()
    }

    fun updateGamepadEnabled(value: Boolean) {
        gamepadEnabled = value
        gamepad.reset()
        prefs.edit().putBoolean("gamepad", value).apply()
    }

    // ------------------------------------------------- descoberta e conexão

    fun startDiscovery() {
        if (discovery != null) return
        discovering = true
        discovery = AdbDiscovery(getApplication<Application>()) { connect, pairing ->
            discoveredDevices = connect
            pairingServices = pairing
        }.also { it.start() }
    }

    fun stopDiscovery() {
        discovery?.stop()
        discovery = null
        discovering = false
        discoveredDevices = emptyList()
        pairingServices = emptyList()
    }

    /** Conecta a um aparelho encontrado na rede. Se ainda não estiver pareado, pede o pareamento na própria tela. */
    fun connectTo(service: AdbService) {
        host = service.host
        connectPort = service.port.toString()
        connect()
    }

    /** Conexão com IP e porta já preenchidos. */
    fun connect() {
        val port = connectPort.trim().toIntOrNull()
        if (host.isBlank() || port == null) {
            error = "Informe o IP e a porta de conexão."
            return
        }
        saveForm()
        runAdb("Conectando…") { adb ->
            val result: Any? = try {
                adb.connect(host.trim(), port)
            } catch (e: Exception) {
                pairingHighlight = true
                throw IllegalStateException(
                    "Não foi possível conectar. Se esta é a primeira vez com este aparelho, faça o pareamento abaixo.",
                    e,
                )
            }
            if (result == false) {
                pairingHighlight = true
                throw IllegalStateException(
                    "Não foi possível conectar. Se esta é a primeira vez com este aparelho, faça o pareamento abaixo.",
                )
            }
            registerDevice(host.trim(), port, adb)
        }
    }

    /**
     * Pareia usando a janela "Parear com código" detectada na rede (ou IP:porta informado à mão) e
     * conecta sozinho em seguida.
     */
    fun pairWithCode() {
        val code = pairCode.trim()
        if (code.length != 6) {
            error = "Digite o código de 6 dígitos mostrado no outro aparelho."
            return
        }
        val target = pairingServices.firstOrNull() ?: parseManualTarget()
        if (target == null) {
            error = "Abra “Parear dispositivo com código” no outro aparelho (o Janus detecta a janela sozinho) " +
                "ou informe o IP e a porta mostrados nela."
            return
        }
        host = target.host
        saveForm()
        runAdb("Pareando com ${target.host}…") { adb ->
            val result: Any? = try {
                adb.pair(target.host, target.port, code)
            } catch (e: Exception) {
                // janela antiga: o mDNS pode demorar a perceber que ela fechou
                if (AdbErrors.isRefused(e)) discovery?.forgetPairing(target.host, target.port)
                throw e
            }
            if (result == false) throw IllegalStateException("O aparelho recusou o pareamento (código incorreto ou expirado).")
            pairCode = ""
            pairingHighlight = false
            message = "Pareado! Procurando o aparelho para conectar…"
            var found: AdbService? = null
            for (i in 0 until 24) {
                found = discoveredDevices.firstOrNull { it.host == target.host }
                if (found != null) break
                Thread.sleep(500)
            }
            if (found == null) {
                message = "Pareado. Toque no aparelho em “Aparelhos encontrados” para conectar."
                return@runAdb
            }
            connectPort = found.port.toString()
            saveForm()
            message = "Conectando…"
            val connectResult: Any? = adb.connect(found.host, found.port)
            if (connectResult == false) throw IllegalStateException("Pareado, mas não foi possível conectar. Toque no aparelho na lista.")
            registerDevice(found.host, found.port, adb)
        }
    }

    private fun parseManualTarget(): AdbService? {
        val text = manualPairTarget.trim()
        if (text.isEmpty()) return null
        val parts = text.split(":")
        return if (parts.size == 2) {
            val port = parts[1].trim().toIntOrNull() ?: return null
            if (parts[0].isBlank()) null else AdbService("manual", parts[0].trim(), port)
        } else {
            // só a porta: usa o IP do aparelho já encontrado/informado
            val port = text.toIntOrNull() ?: return null
            val ip = host.trim().ifEmpty { discoveredDevices.firstOrNull()?.host ?: return null }
            AdbService("manual", ip, port)
        }
    }

    fun reportError(text: String) {
        error = text
    }

    fun reportInfo(text: String) {
        error = null
        message = text
    }

    /** O serviço de pareamento por notificação conectou em segundo plano. */
    fun onExternalConnected(incoming: ConnectionHub.Incoming) {
        ConnectionHub.incoming = null
        host = incoming.host
        connectPort = incoming.port.toString()
        saveForm()
        registerDevice(incoming.host, incoming.port, incoming.adb)
    }

    /** Guarda a conexão como um aparelho e o seleciona. Se o IP já estava conectado, a conexão antiga é trocada. */
    private fun registerDevice(host: String, port: Int, adb: AppAdbManager) {
        val id = host
        adbs[id]?.let { old -> if (old !== adb) runCatching { old.disconnect() } }
        adbs[id] = adb
        val previousName = devices.firstOrNull { it.id == id }?.name ?: host
        devices = devices.filter { it.id != id } + DeviceInfo(id, host, port, previousName)
        currentDeviceId = id
        message = null
        error = null
        pairingHighlight = false
        screen = Screen.Home
        // nome amigável (modelo do aparelho), sem atrapalhar a conexão se falhar
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val model = DeviceTools.runShell(adb, "getprop ro.product.model", 5_000).trim()
                if (model.isNotEmpty() && model.length < 40) {
                    devices = devices.map { if (it.id == id) it.copy(name = model) else it }
                }
            } catch (_: Throwable) {
            }
        }
    }

    // ----------------------------------------------------- vários aparelhos

    fun selectDevice(id: String) {
        if (devices.none { it.id == id }) return
        currentDeviceId = id
        error = null
        screen = Screen.Home
    }

    /** Abre a tela de conexão para ligar mais um aparelho, sem desconectar os que já estão conectados. */
    fun addDevice() {
        error = null
        message = null
        screen = Screen.Connect
    }

    fun cancelAdd() {
        error = null
        message = null
        pairingHighlight = false
        if (connected) screen = Screen.Home
    }

    /** Desconecta UM aparelho: encerra os monitores dele e fecha a conexão ADB. */
    fun disconnectDevice(id: String) {
        val toWait = manager.snapshot().filter { it.deviceId == id }
        toWait.forEach { it.stop() }
        if (viewing?.deviceId == id) {
            viewingSession()?.detachSurface()
            viewingId = null
            fullscreen = false
            if (screen == Screen.Mirror) screen = Screen.Home
        }
        viewModelScope.launch(Dispatchers.IO) {
            // deixa cada sessão encerrar o servidor remoto antes de derrubar o ADB
            toWait.forEach { it.awaitEnd(3000) }
            adbs.remove(id)?.let { runCatching { it.disconnect() } }
            devices = devices.filter { it.id != id }
            remoteByDevice = remoteByDevice - id
            loadedDevices = loadedDevices - id
            errorsByDevice = errorsByDevice - id
            if (currentDeviceId == id) currentDeviceId = devices.firstOrNull()?.id
            if (devices.isEmpty()) {
                screen = Screen.Connect
                message = "Desconectado."
            }
        }
    }

    private fun runAdb(status: String, block: (AppAdbManager) -> Unit) {
        if (busy) return
        busy = true
        error = null
        message = status
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // cada conexão nova usa uma instância própria (mesma chave ADB)
                block(AppAdbManager.create(getApplication<Application>()))
            } catch (e: Throwable) {
                error = AdbErrors.describe(e)
                message = null
            } finally {
                busy = false
            }
        }
    }

    // ------------------------------------------------------------- monitores

    /** Atualiza a lista de telas do aparelho selecionado a cada poucos segundos (enquanto a tela inicial está aberta). */
    suspend fun pollDisplays() {
        while (true) {
            refreshDisplaysNow()
            delay(POLL_INTERVAL_MS)
        }
    }

    suspend fun refreshDisplaysNow() {
        val id = currentDeviceId ?: return
        val adb = adbs[id] ?: return
        withContext(Dispatchers.IO) {
            try {
                val list = DeviceTools.listDisplays(getApplication<Application>(), adb)
                remoteByDevice = remoteByDevice + (id to list)
                errorsByDevice = errorsByDevice - id
            } catch (e: Throwable) {
                errorsByDevice = errorsByDevice + (id to "Não foi possível atualizar a lista de telas: ${AdbErrors.describe(e)}")
            } finally {
                loadedDevices = loadedDevices + id
            }
        }
    }

    private fun refreshSoon() {
        viewModelScope.launch {
            delay(1500)
            refreshDisplaysNow()
        }
    }

    private fun options() = SessionOptions(audio = audioEnabled, closeOldServers = closeOldServers)

    /** Abre (ou cria) um monitor no aparelho selecionado e mostra a imagem dele. */
    fun startSession(config: SessionConfig) {
        error = null
        val device = currentDevice ?: return
        val adb = adbs[device.id] ?: return
        val s = manager.create(adb, device.id, config, options())
        openSession(s.id)
        refreshSoon()
    }

    fun openSession(id: Int) {
        viewingId = id
        manager.get(id)?.let { currentDeviceId = it.deviceId }
        muted = false
        touchpad = false
        cursorX = -1f
        cursorY = -1f
        fullscreen = false
        mouseButtons = 0
        gamepad.reset()
        error = null
        screen = Screen.Mirror
    }

    /** Troca, sem sair da tela de espelhamento, para outro monitor (de qualquer aparelho). */
    fun switchViewing(id: Int) {
        val target = manager.get(id) ?: return
        if (id == viewingId) return
        viewingSession()?.detachSurface()
        viewingId = id
        currentDeviceId = target.deviceId
        touchpad = false
        cursorX = -1f
        cursorY = -1f
        mouseButtons = 0
        gamepad.reset()
        target.muted = muted
        surface?.let { target.attachSurface(it) }
    }

    fun mirrorMain() = startSession(SessionConfig.MainScreen)

    /** Um clique: abre o monitor (se este app já está com ele) ou conecta a ele. */
    fun openRow(row: MonitorRow) {
        when {
            row.sessionId != null -> openSession(row.sessionId)
            row.isMain -> mirrorMain()
            else -> startSession(SessionConfig.ExistingDisplay(row.displayId, row.width, row.height))
        }
    }

    /** Fecha um monitor individualmente. */
    fun closeRow(row: MonitorRow) {
        if (row.sessionId != null) {
            manager.close(row.sessionId)
            refreshSoon()
        } else if (!row.isMain) {
            closeOrphans()
        }
    }

    /** Encerra servidores scrcpy do aparelho selecionado que não pertencem a este app (monitores "sem dono"). */
    fun closeOrphans() {
        val id = currentDeviceId ?: return
        val adb = adbs[id] ?: return
        val keep = manager.activeIds(id)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DeviceTools.killServersExcept(adb, keep)
                delay(600)
            } catch (e: Throwable) {
                errorsByDevice = errorsByDevice + (id to "Falha ao fechar: ${AdbErrors.describe(e)}")
            }
            refreshDisplaysNow()
        }
    }

    private fun handleSessionEnded(id: Int, err: String?) {
        if (viewingId == id) {
            viewingId = null
            fullscreen = false
            if (screen == Screen.Mirror) screen = if (connected) Screen.Home else Screen.Connect
        }
        if (err != null) error = err
        refreshSoon()
    }

    /** Sai da tela de espelhamento SEM encerrar o monitor. */
    fun closeViewer() {
        viewingSession()?.detachSurface() // antes de zerar o id: a SurfaceView só é destruída depois
        viewingId = null
        fullscreen = false
        screen = if (connected) Screen.Home else Screen.Connect
    }

    /** Encerra o monitor que está sendo exibido. */
    fun closeViewedMonitor() {
        val id = viewingId
        closeViewer()
        if (id != null) manager.close(id)
        refreshSoon()
    }

    fun onSurfaceReady(s: Surface) {
        surface = s
        viewingSession()?.let {
            it.attachSurface(s)
            it.muted = muted
        }
    }

    fun onSurfaceDestroyed() {
        viewingSession()?.detachSurface()
        surface = null
    }

    override fun onCleared() {
        // As sessões pertencem ao SessionManager e continuam; só paramos o que é da tela.
        discovery?.stop()
        manager.onSessionEnded = null
    }

    fun setFullscreen(value: Boolean) {
        fullscreen = value
    }

    fun toggleMute() {
        muted = !muted
        viewingSession()?.muted = muted
    }

    fun toggleTouchpad() {
        touchpad = !touchpad
        mouseButtons = 0
        if (touchpad) {
            val s = viewingSession()
            if (s != null && s.videoWidth > 0) touchpadController.centerFor(s.videoWidth, s.videoHeight)
        } else {
            cursorX = -1f
            cursorY = -1f
        }
    }

    // ---------------------------------------------------------------- controle

    private val control: ControlChannel? get() = viewingSession()?.control

    fun onTouch(event: MotionEvent, viewWidth: Int, viewHeight: Int) {
        val s = viewingSession() ?: return
        val ctl = s.control ?: return
        val vw = s.videoWidth
        val vh = s.videoHeight
        if (vw <= 0 || vh <= 0 || viewWidth <= 0 || viewHeight <= 0) return

        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            mouseTouch(event, ctl, vw, vh, viewWidth, viewHeight)
            return
        }
        if (touchpad) {
            touchpadController.onTouch(event, ctl, vw, vh, viewWidth)
            return
        }

        // Todos os dedos (até 10, o limite do servidor) são enviados ao outro aparelho.
        fun send(action: Int, index: Int, pressure: Float) {
            val x = (event.getX(index) / viewWidth * vw).toInt().coerceIn(0, vw - 1)
            val y = (event.getY(index) / viewHeight * vh).toInt().coerceIn(0, vh - 1)
            ctl.sendTouch(action, event.getPointerId(index).toLong(), x, y, vw, vh, pressure)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                send(ControlMessages.ACTION_DOWN, event.actionIndex, 1f)
            MotionEvent.ACTION_MOVE ->
                for (i in 0 until event.pointerCount) send(ControlMessages.ACTION_MOVE, i, 1f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                send(ControlMessages.ACTION_UP, event.actionIndex, 0f)
            MotionEvent.ACTION_CANCEL ->
                for (i in 0 until event.pointerCount) send(ControlMessages.ACTION_UP, i, 0f)
        }
    }

    /** Mouse físico (USB/Bluetooth) ligado ao RECEPTOR: cliques e arrasto. */
    private fun mouseTouch(ev: MotionEvent, ctl: ControlChannel, vw: Int, vh: Int, viewW: Int, viewH: Int) {
        val x = (ev.x / viewW * vw).toInt().coerceIn(0, vw - 1)
        val y = (ev.y / viewH * vh).toInt().coerceIn(0, vh - 1)
        val state = ev.buttonState
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val buttons = if (state == 0) ControlMessages.BUTTON_PRIMARY else state
                mouseButtons = buttons
                ctl.sendMouse(ControlMessages.ACTION_DOWN, x, y, vw, vh, buttons, buttons)
            }
            MotionEvent.ACTION_MOVE -> {
                if (state != mouseButtons) {
                    val added = state and mouseButtons.inv()
                    val removed = mouseButtons and state.inv()
                    if (added != 0) ctl.sendMouse(ControlMessages.ACTION_DOWN, x, y, vw, vh, added, state)
                    if (removed != 0) ctl.sendMouse(ControlMessages.ACTION_UP, x, y, vw, vh, removed, state)
                    mouseButtons = state
                } else if (state != 0) {
                    ctl.sendMouse(ControlMessages.ACTION_MOVE, x, y, vw, vh, 0, state)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val released = if (mouseButtons == 0) ControlMessages.BUTTON_PRIMARY else mouseButtons
                mouseButtons = 0
                ctl.sendMouse(ControlMessages.ACTION_UP, x, y, vw, vh, released, 0)
            }
        }
    }

    /** Mouse físico: movimento sem botão (cursor) e roda de rolagem. */
    fun onGenericMotion(ev: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        val s = viewingSession() ?: return false
        val ctl = s.control ?: return false
        val vw = s.videoWidth
        val vh = s.videoHeight
        if (vw <= 0 || vh <= 0 || viewWidth <= 0 || viewHeight <= 0) return false
        if (ev.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) return false
        val x = (ev.x / viewWidth * vw).toInt().coerceIn(0, vw - 1)
        val y = (ev.y / viewHeight * vh).toInt().coerceIn(0, vh - 1)
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE ->
                ctl.sendMouse(ControlMessages.ACTION_HOVER_MOVE, x, y, vw, vh, 0, 0)
            MotionEvent.ACTION_SCROLL ->
                ctl.sendScroll(
                    x, y, vw, vh,
                    ev.getAxisValue(MotionEvent.AXIS_HSCROLL), ev.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    ev.buttonState,
                )
            else -> return false
        }
        return true
    }

    /** Controle de jogo (gamepad) ligado ao RECEPTOR → gamepad virtual no outro aparelho. */
    fun forwardGamepadKey(event: KeyEvent): Boolean {
        if (!gamepadEnabled || screen != Screen.Mirror) return false
        val ctl = control ?: return false
        return gamepad.onKey(event, ctl)
    }

    fun forwardGamepadMotion(event: MotionEvent): Boolean {
        if (!gamepadEnabled || screen != Screen.Mirror) return false
        val ctl = control ?: return false
        return gamepad.onMotion(event, ctl)
    }

    fun sendBack() = control?.sendBack()
    fun sendKey(keycode: Int) = control?.sendKey(keycode)
    fun sendText(text: String) = control?.sendText(text)
    fun rotateSource() = control?.send(ControlMessages.rotateDevice())

    /** Teclado físico conectado ao RECEPTOR → teclas no CÉREBRO. */
    fun forwardHardwareKey(event: KeyEvent): Boolean {
        val ctl = control ?: return false
        if (screen != Screen.Mirror) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_POWER -> return false
        }
        val device = event.device ?: return false
        if (device.isVirtual || device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> ControlMessages.ACTION_DOWN
            KeyEvent.ACTION_UP -> ControlMessages.ACTION_UP
            else -> return false
        }
        ctl.send(ControlMessages.keycode(action, event.keyCode, event.repeatCount, event.metaState))
        return true
    }

    companion object {
        private const val POLL_INTERVAL_MS = 8000L
    }
}
