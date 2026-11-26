package app.scrcpya2a.session

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * Decodificador H.264 por hardware (MediaCodec) que renderiza direto na Surface.
 * Todos os métodos exceto o laço de saída devem ser chamados pela mesma thread (a da sessão).
 */
class VideoDecoder(
    private val surface: Surface,
    private val onError: (String) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null
    private var pendingConfig: ByteArray? = null

    @Volatile
    private var running = false

    /** (Re)cria o decodificador para o tamanho informado. Chamado a cada "session meta" do servidor. */
    fun configure(width: Int, height: Int) {
        release()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(1 shl 20, width * height))
        if (Build.VERSION.SDK_INT >= 23) format.setInteger(MediaFormat.KEY_PRIORITY, 0) // tempo real
        if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)

        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(format, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            c.release()
            throw e
        }
        codec = c
        pendingConfig = null
        running = true
        outputThread = Thread({ drainOutput(c) }, "scrcpy-decoder-out").also { it.start() }
        Log.i(TAG, "decodificador iniciado ${width}x$height")
    }

    /**
     * Envia um pacote ao decodificador. O pacote de configuração (SPS/PPS) é guardado e
     * concatenado ao próximo quadro, como faz o cliente oficial.
     * @return false se o quadro foi descartado (o chamador deve pedir novo quadro-chave).
     */
    fun queue(data: ByteArray, ptsUs: Long, isConfig: Boolean): Boolean {
        val c = codec ?: return true
        if (isConfig) {
            pendingConfig = data
            return true
        }
        val config = pendingConfig
        val payload = if (config != null) {
            pendingConfig = null
            config + data
        } else data

        repeat(5) {
            if (!running) return true
            try {
                val index = c.dequeueInputBuffer(20_000)
                if (index >= 0) {
                    val buffer = c.getInputBuffer(index) ?: return true
                    if (payload.size > buffer.capacity()) {
                        c.queueInputBuffer(index, 0, 0, ptsUs, 0)
                        return false
                    }
                    buffer.clear()
                    buffer.put(payload)
                    c.queueInputBuffer(index, 0, payload.size, ptsUs, 0)
                    return true
                }
            } catch (e: IllegalStateException) {
                if (running) onError("Decodificador: ${e.message}")
                return true
            }
        }
        return false
    }

    private fun drainOutput(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = c.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) c.releaseOutputBuffer(index, true) // renderiza imediatamente
            }
        } catch (e: Exception) {
            if (running) onError("Decodificador: ${e.message}")
        }
    }

    fun release() {
        running = false
        val c = codec
        codec = null
        pendingConfig = null
        try {
            outputThread?.join(500)
        } catch (_: InterruptedException) {
        }
        outputThread = null
        if (c != null) {
            try {
                c.stop()
            } catch (_: Exception) {
            }
            try {
                c.release()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "VideoDecoder"
    }
}
