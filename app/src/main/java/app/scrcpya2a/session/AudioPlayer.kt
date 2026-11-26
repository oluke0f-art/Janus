package app.scrcpya2a.session

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Reproduz o PCM 16 bits / 48 kHz / estéreo enviado pelo servidor (audio_codec=raw).
 * Falhas aqui nunca devem derrubar o vídeo: quem chama trata as exceções.
 */
class AudioPlayer {
    @Volatile
    var muted = false

    private var track: AudioTrack? = null

    fun start() {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val wanted = SAMPLE_RATE * BYTES_PER_FRAME * BUFFER_MS / 1000
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, wanted))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        t.play()
        track = t
    }

    /** Em modo mudo os dados são descartados (o socket continua sendo lido: sem acúmulo de atraso). */
    fun write(data: ByteArray, length: Int) {
        if (muted) return
        val t = track ?: return
        var off = 0
        while (off < length) {
            val n = t.write(data, off, length - off)
            if (n < 0) {
                Log.w(TAG, "AudioTrack.write falhou: $n")
                return
            }
            off += n
        }
    }

    fun release() {
        val t = track
        track = null
        try {
            t?.stop()
        } catch (_: Exception) {
        }
        t?.release()
    }

    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 48000
        const val BYTES_PER_FRAME = 4 // 2 canais × 16 bits
        private const val BUFFER_MS = 100
    }
}
