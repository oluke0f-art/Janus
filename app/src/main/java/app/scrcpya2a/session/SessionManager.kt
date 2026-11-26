package app.scrcpya2a.session

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.muntashirakon.adb.AbsAdbConnectionManager

/**
 * Dono de todas as sessões ativas. Vive no processo (não na tela): sair da tela de espelhamento ou
 * fechar o app não encerra os monitores; só [close]/[closeAll] fazem isso. Um serviço em primeiro
 * plano ([KeepAliveService]) mantém o processo vivo enquanto houver sessões.
 */
class SessionManager private constructor(private val app: Application) : ScrcpySession.Callbacks {

    enum class Kind { Main, Virtual, Existing }

    /** Retrato imutável de uma sessão, para a interface. */
    data class Info(
        val id: Int,
        val deviceId: String,
        val kind: Kind,
        val displayId: Int, // -1 enquanto o servidor ainda não informou
        val width: Int,
        val height: Int,
        val status: String,
        val deviceName: String,
        val audioState: String,
    ) {
        val streaming: Boolean get() = width > 0
    }

    private class Mutable(val deviceId: String, val kind: Kind, var displayId: Int) {
        var width = 0
        var height = 0
        var status = "Iniciando…"
        var deviceName = ""
        var audioState = ""
    }

    var infos by mutableStateOf<List<Info>>(emptyList())
        private set

    /** Chamado (na thread principal) quando uma sessão termina: id e erro, se houve. */
    var onSessionEnded: ((id: Int, error: String?) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val sessions = LinkedHashMap<Int, ScrcpySession>() // só na thread principal
    private val data = HashMap<Int, Mutable>()

    fun get(id: Int?): ScrcpySession? = if (id == null) null else sessions[id]

    /** Ids das sessões ativas; com [deviceId], só as daquele aparelho. */
    fun activeIds(deviceId: String? = null): Set<Int> =
        sessions.values.filter { deviceId == null || it.deviceId == deviceId }.map { it.id }.toSet()

    fun activeCount(): Int = sessions.size

    /** Cria e inicia uma sessão (thread principal). */
    fun create(adb: AbsAdbConnectionManager, deviceId: String, config: SessionConfig, options: SessionOptions): ScrcpySession {
        val session = ScrcpySession(app, adb, deviceId, config, options, activeIds(deviceId), this)
        sessions[session.id] = session
        data[session.id] = Mutable(
            deviceId = deviceId,
            kind = when (config) {
                is SessionConfig.MainScreen -> Kind.Main
                is SessionConfig.VirtualDisplay -> Kind.Virtual
                is SessionConfig.ExistingDisplay -> Kind.Existing
            },
            displayId = session.displayId,
        )
        refresh()
        KeepAliveService.sync(app, sessions.size)
        session.start()
        return session
    }

    fun close(id: Int) {
        sessions[id]?.stop()
    }

    fun closeAll() {
        sessions.values.toList().forEach { it.stop() }
    }

    /** Cópia da lista de sessões (thread principal), para esperar o fim delas em outra thread. */
    fun snapshot(): List<ScrcpySession> = sessions.values.toList()

    private fun refresh() {
        infos = sessions.keys.mapNotNull { id ->
            val d = data[id] ?: return@mapNotNull null
            Info(id, d.deviceId, d.kind, d.displayId, d.width, d.height, d.status, d.deviceName, d.audioState)
        }
    }

    private fun post(block: () -> Unit) {
        main.post { block() }
    }

    // ---- ScrcpySession.Callbacks (chamados em threads da sessão) ----

    override fun onStatus(session: ScrcpySession, text: String) = post {
        data[session.id]?.status = text
        refresh()
    }

    override fun onDeviceName(session: ScrcpySession, name: String) = post {
        data[session.id]?.deviceName = name
        refresh()
    }

    override fun onDisplayId(session: ScrcpySession, displayId: Int) = post {
        data[session.id]?.displayId = displayId
        refresh()
    }

    override fun onVideoSize(session: ScrcpySession, width: Int, height: Int) = post {
        data[session.id]?.let {
            it.width = width
            it.height = height
            it.status = "Transmitindo"
        }
        refresh()
    }

    override fun onAudioState(session: ScrcpySession, text: String) = post {
        data[session.id]?.audioState = text
        refresh()
    }

    override fun onStopped(session: ScrcpySession, error: String?) = post {
        sessions.remove(session.id)
        data.remove(session.id)
        refresh()
        KeepAliveService.sync(app, sessions.size)
        onSessionEnded?.invoke(session.id, error)
    }

    companion object {
        @Volatile
        private var instance: SessionManager? = null

        fun get(app: Application): SessionManager =
            instance ?: synchronized(this) { instance ?: SessionManager(app).also { instance = it } }
    }
}
