package app.scrcpya2a.session

import android.content.Context
import android.util.Log
import android.view.Surface
import app.scrcpya2a.adb.AdbSync
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Uma sessão = um servidor scrcpy no CÉREBRO (um monitor, virtual ou a tela principal) + os sockets
 * de vídeo, áudio e controle pela conexão ADB.
 *
 * A sessão é independente da tela: ela continua rodando (e o monitor continua existindo) mesmo sem
 * Surface. [attachSurface] liga a imagem a uma SurfaceView e [detachSurface] desliga, sem encerrar
 * nada; para encerrar de verdade use [stop] (o que também destrói o monitor virtual dela).
 */
class ScrcpySession(
    private val context: Context,
    private val adb: AbsAdbConnectionManager,
    /** Aparelho dono desta sessão (identificador da conexão). */
    val deviceId: String,
    val config: SessionConfig,
    private val options: SessionOptions,
    /** Servidores de outras sessões que NÃO devem ser encerrados ao limpar monitores sem dono. */
    private val keepServers: Set<Int>,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onStatus(session: ScrcpySession, text: String)
        fun onDeviceName(session: ScrcpySession, name: String)
        fun onDisplayId(session: ScrcpySession, displayId: Int)
        fun onVideoSize(session: ScrcpySession, width: Int, height: Int)
        fun onAudioState(session: ScrcpySession, text: String)
        fun onStopped(session: ScrcpySession, error: String?)
    }

    /** Identificador da sessão (também é o `scid` do servidor). */
    val id: Int = Random.nextInt(0, Int.MAX_VALUE)

    @Volatile
    var displayId: Int = when (config) {
        is SessionConfig.MainScreen -> 0
        is SessionConfig.ExistingDisplay -> config.displayId
        is SessionConfig.VirtualDisplay -> -1 // descoberto pelo log do servidor
    }
        private set

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    @Volatile
    var control: ControlChannel? = null
        private set

    @Volatile
    private var stopped = false

    @Volatile
    private var serverExited = false

    @Volatile
    private var failure: String? = null

    @Volatile
    private var serverStarted = false

    private val lock = Any() // protege surface, decoder e lastConfig
    private var surface: Surface? = null
    private var decoder: VideoDecoder? = null
    private var lastConfig: ByteArray? = null

    private val closeables = CopyOnWriteArrayList<Closeable>()
    private val serverLog = ArrayDeque<String>()
    private val audioPlayer: AudioPlayer? = if (options.audio) AudioPlayer() else null
    private var thread: Thread? = null
    private var lastKeyframeRequest = 0L
    private var userMuted = false

    private val serverPath = DeviceTools.serverPath("%08x".format(id))

    /** Silencia o áudio (o áudio também fica mudo enquanto a sessão não está sendo exibida). */
    var muted: Boolean
        get() = userMuted
        set(value) {
            userMuted = value
            updateAudioMute()
        }

    fun start() {
        thread = Thread({ run() }, "scrcpy-session-%08x".format(id)).also { it.start() }
    }

    fun stop() {
        stopped = true
        closeAll()
    }

    /** Espera a sessão terminar (inclui o encerramento do servidor remoto). */
    fun awaitEnd(timeoutMs: Long) {
        try {
            thread?.join(timeoutMs)
        } catch (_: InterruptedException) {
        }
    }

    // ------------------------------------------------------------ exibição

    /** Liga a imagem a uma SurfaceView. Pede um novo quadro-chave para a imagem aparecer logo. */
    fun attachSurface(s: Surface) {
        synchronized(lock) {
            surface = s
            if (videoWidth > 0) startDecoderLocked(videoWidth, videoHeight)
        }
        updateAudioMute()
        requestKeyFrame(force = true)
    }

    /** Desliga a imagem (a SurfaceView vai ser destruída). A sessão continua rodando. */
    fun detachSurface() {
        synchronized(lock) {
            surface = null
            decoder?.release()
            decoder = null
        }
        updateAudioMute()
    }

    private fun startDecoderLocked(width: Int, height: Int) {
        val s = surface ?: return
        decoder?.release()
        decoder = null
        try {
            val d = VideoDecoder(s) { msg -> onDecoderError(msg) }
            d.configure(width, height)
            lastConfig?.let { d.queue(it, 0, true) }
            decoder = d
        } catch (e: Exception) {
            Log.e(TAG, "não foi possível iniciar o decodificador", e)
        }
    }

    private fun onDecoderError(message: String) {
        // Falha de decodificação não derruba a sessão: solta o decodificador e tenta de novo no próximo reinício de vídeo.
        Log.w(TAG, message)
        synchronized(lock) {
            decoder?.release()
            decoder = null
        }
    }

    private fun updateAudioMute() {
        val attached = synchronized(lock) { surface != null }
        audioPlayer?.muted = userMuted || !attached
    }

    // --------------------------------------------------------------- ciclo

    private fun closeAll() {
        control?.close()
        for (c in closeables) {
            try {
                c.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun run() {
        var error: String? = null
        try {
            runStreaming()
        } catch (e: Throwable) {
            if (!stopped) {
                Log.e(TAG, "sessão encerrada com erro", e)
                error = e.message ?: e.javaClass.simpleName
                val tail = serverLogTail()
                if (tail.isNotEmpty()) error += "\n\nLog do servidor:\n$tail"
            }
        } finally {
            stopped = true
            closeAll()
            synchronized(lock) {
                decoder?.release()
                decoder = null
            }
            callbacks.onStopped(this, error)
            killRemoteServer()
        }
    }

    /** Garante que o servidor desta sessão não fique vivo (e com monitor virtual) no CÉREBRO. */
    private fun killRemoteServer() {
        if (!serverStarted) return
        try {
            DeviceTools.killServers(adb, id)
        } catch (e: Exception) {
            Log.w(TAG, "não foi possível encerrar o servidor remoto: ${e.message}")
        }
    }

    private fun fail(message: String) {
        failure = message
        stopped = true
        closeAll()
    }

    private fun runStreaming() {
        val socketName = "scrcpy_%08x".format(id)

        callbacks.onStatus(this, "Enviando o servidor ao dispositivo…")
        ServerAsset.open(context).use { AdbSync.push(adb, it, serverPath) }
        checkRunning()

        if (options.closeOldServers && config is SessionConfig.VirtualDisplay) {
            callbacks.onStatus(this, "Fechando monitores sem dono…")
            try {
                DeviceTools.killServersExcept(adb, keepServers + id)
            } catch (e: Exception) {
                Log.w(TAG, "não foi possível fechar servidores antigos: ${e.message}")
            }
            checkRunning()
        }

        callbacks.onStatus(this, "Iniciando o servidor scrcpy…")
        val args = ServerArgs.build(id, config, options).joinToString(" ")
        val command = "CLASSPATH=$serverPath app_process / com.genymobile.scrcpy.Server $args"
        Log.i(TAG, "comando: $command")
        val shell = adb.openStream("shell:$command")
        closeables.add(shell)
        serverStarted = true
        startLogReader(shell)

        callbacks.onStatus(this, "Conectando ao vídeo…")
        val video = openSocket(socketName)
        val videoInput = video.openInputStream()
        // Primeiro socket aceito: o servidor escreve 1 byte para detectar falha de conexão.
        if (videoInput.read() < 0) throw IOException("O servidor fechou a conexão de vídeo")

        // ATENÇÃO À ORDEM: o servidor (DesktopConnection.open) aceita vídeo, áudio e controle, nesta ordem,
        // ANTES de enviar o nome do aparelho e o cabeçalho do vídeo. Por isso todos os sockets precisam
        // ser abertos agora; senão os dois lados ficam esperando um ao outro.
        val audioStream = if (options.audio) {
            callbacks.onStatus(this, "Conectando ao áudio…")
            openSocket(socketName)
        } else null

        callbacks.onStatus(this, "Conectando ao controle…")
        val controlStream = openSocket(socketName)
        val channel = ControlChannel(controlStream.openOutputStream()) { e -> fail("Controle: ${e.message}") }
        channel.start()
        control = channel
        val controlInput = controlStream.openInputStream()
        Thread({
            try {
                ControlChannel.drainDeviceMessages(controlInput)
            } catch (e: Exception) {
                if (!stopped) Log.w(TAG, "leitura do controle terminou: ${e.message}")
            }
        }, "scrcpy-control-reader").apply { isDaemon = true }.start()

        if (audioStream != null && audioPlayer != null) {
            val audioInput = audioStream.openInputStream()
            Thread({ audioLoop(audioInput, audioPlayer) }, "scrcpy-audio").apply { isDaemon = true }.start()
        }

        callbacks.onStatus(this, "Recebendo o vídeo…")
        val dis = DataInputStream(BufferedInputStream(videoInput, 64 * 1024))

        val nameBytes = ByteArray(DEVICE_NAME_LENGTH)
        dis.readFully(nameBytes)
        callbacks.onDeviceName(this, String(nameBytes, Charsets.UTF_8).trimEnd('\u0000'))

        val codecId = dis.readInt()
        if (codecId != CODEC_H264) {
            throw IOException("O servidor não iniciou o vídeo H.264 (código 0x%08x)".format(codecId))
        }

        callbacks.onStatus(this, "Aguardando o primeiro quadro…")
        readVideo(dis)
        failure?.let { throw IOException(it) }
    }

    private fun readVideo(dis: DataInputStream) {
        val header = ByteArray(12)
        val headerBuf = ByteBuffer.wrap(header) // big-endian, como o servidor
        while (!stopped) {
            try {
                dis.readFully(header)
            } catch (e: IOException) {
                if (stopped) return
                throw IOException("A transmissão de vídeo foi interrompida", e)
            }
            val first = headerBuf.getLong(0)
            if (first < 0) { // bit 63: pacote de sessão (novo tamanho de vídeo)
                val w = headerBuf.getInt(4)
                val h = headerBuf.getInt(8)
                if (w <= 0 || h <= 0 || w > 16384 || h > 16384) throw IOException("Tamanho de vídeo inválido: ${w}x$h")
                synchronized(lock) {
                    videoWidth = w
                    videoHeight = h
                    if (surface != null) startDecoderLocked(w, h)
                }
                callbacks.onVideoSize(this, w, h)
                continue
            }
            val size = headerBuf.getInt(8)
            if (size < 0 || size > MAX_PACKET) throw IOException("Pacote de vídeo inválido: $size bytes")
            val payload = ByteArray(size)
            dis.readFully(payload)
            val isConfig = (first and FLAG_CONFIG) != 0L
            val pts = first and PTS_MASK
            var dropped = false
            synchronized(lock) {
                if (isConfig) lastConfig = payload
                // Sem Surface (sessão em segundo plano) o pacote é apenas lido e descartado.
                decoder?.let { if (!it.queue(payload, pts, isConfig)) dropped = true }
            }
            if (dropped) requestKeyFrame(force = false)
        }
    }

    /**
     * Áudio: roda numa thread própria. Qualquer falha aqui só silencia o áudio; o vídeo continua.
     */
    private fun audioLoop(input: InputStream, player: AudioPlayer) {
        try {
            val dis = DataInputStream(BufferedInputStream(input, 32 * 1024))
            when (val codec = dis.readInt()) {
                0, 1 -> {
                    // O servidor desligou o áudio (código 0) ou deu erro (1): o motivo está no log dele.
                    Thread.sleep(300)
                    val why = audioLogHint()
                    callbacks.onAudioState(
                        this,
                        "Sem áudio: " + (why.ifEmpty { "o outro aparelho não conseguiu capturar o som (precisa de Android 11+ e tela desbloqueada ao iniciar)" }),
                    )
                    return
                }
                CODEC_RAW -> Unit
                else -> {
                    callbacks.onAudioState(this, "Formato de áudio inesperado (0x%08x)".format(codec))
                    return
                }
            }
            player.start()
            updateAudioMute()
            callbacks.onAudioState(this, "Áudio: aguardando dados…")
            val header = ByteArray(12)
            val headerBuf = ByteBuffer.wrap(header)
            var buf = ByteArray(8192)
            var windowBytes = 0L
            var windowStart = System.currentTimeMillis()
            while (!stopped) {
                dis.readFully(header)
                val first = headerBuf.getLong(0)
                val size = headerBuf.getInt(8)
                if (size < 0 || size > (1 shl 20)) throw IOException("Pacote de áudio inválido: $size bytes")
                if (buf.size < size) buf = ByteArray(size)
                dis.readFully(buf, 0, size)
                if ((first and FLAG_CONFIG) != 0L) continue
                player.write(buf, size)
                // Mostra quantos dados estão chegando: se chega mas não toca, o problema é volume/mudo neste aparelho.
                windowBytes += size
                val now = System.currentTimeMillis()
                if (now - windowStart >= 2000) {
                    val kbps = windowBytes * 1000 / (now - windowStart) / 1024
                    callbacks.onAudioState(this, "Áudio: recebendo $kbps KB/s" + if (audioPlayer?.muted == true) " (mudo)" else "")
                    windowBytes = 0
                    windowStart = now
                }
            }
        } catch (e: Exception) {
            if (!stopped) {
                Log.w(TAG, "áudio interrompido", e)
                callbacks.onAudioState(this, "Áudio interrompido: ${e.message}")
            }
        } finally {
            player.release()
        }
    }

    /** Últimas linhas do log do servidor que falam de áudio (o motivo real de uma falha de captura). */
    private fun audioLogHint(): String = synchronized(serverLog) {
        serverLog.filter { it.contains("audio", ignoreCase = true) }
            .takeLast(2)
            .joinToString(" · ") { it.substringAfter(": ", it).trim() }
    }

    /** Pede ao servidor reiniciar o vídeo (novo quadro-chave e novo pacote de sessão). */
    fun requestKeyFrame(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastKeyframeRequest < 1000) return
        lastKeyframeRequest = now
        control?.send(ControlMessages.resetVideo())
    }

    private fun checkRunning() {
        if (stopped) throw IOException("Cancelado")
    }

    private fun openSocket(name: String): AdbStream {
        var last: Exception? = null
        repeat(OPEN_RETRIES) {
            checkRunning()
            if (serverExited) throw IOException("O servidor encerrou antes de aceitar a conexão")
            try {
                val s = adb.openStream("localabstract:$name")
                closeables.add(s)
                return s
            } catch (e: Exception) {
                last = e
                Thread.sleep(OPEN_DELAY_MS)
            }
        }
        throw IOException("Não foi possível conectar ao servidor (${last?.message})")
    }

    private fun startLogReader(shell: AdbStream) {
        val input = shell.openInputStream()
        Thread({
            try {
                input.bufferedReader().forEachLine { line ->
                    Log.i(TAG, "[server] $line")
                    synchronized(serverLog) {
                        serverLog.addLast(line)
                        while (serverLog.size > 30) serverLog.removeFirst()
                    }
                    DISPLAY_ID_REGEX.find(line)?.let {
                        displayId = it.groupValues[1].toInt()
                        callbacks.onDisplayId(this, displayId)
                    }
                }
            } catch (_: Exception) {
            } finally {
                serverExited = true
            }
        }, "scrcpy-server-log").apply { isDaemon = true }.start()
    }

    private fun serverLogTail(): String = synchronized(serverLog) { serverLog.joinToString("\n") }

    companion object {
        private const val TAG = "ScrcpySession"
        private const val DEVICE_NAME_LENGTH = 64
        private const val CODEC_H264 = 0x68323634
        private const val CODEC_RAW = 0x00726177
        private const val FLAG_CONFIG = 1L shl 62
        private const val PTS_MASK = (1L shl 61) - 1
        private const val MAX_PACKET = 32 * 1024 * 1024
        private const val OPEN_RETRIES = 150
        private const val OPEN_DELAY_MS = 100L

        /** Linha do servidor ao criar o monitor: "New display: 1920x1080/320 (id=7)". */
        private val DISPLAY_ID_REGEX = Regex("New display: .*\\(id=(\\d+)\\)")
    }
}
