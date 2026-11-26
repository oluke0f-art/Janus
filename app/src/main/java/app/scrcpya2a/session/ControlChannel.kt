package app.scrcpya2a.session

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/** Serialização do protocolo de controle do scrcpy 5.0.1 (ver ControlMessageReader no servidor). */
object ControlMessages {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_HOVER_MOVE = 7

    /** Id de ponteiro reservado pelo scrcpy para o mouse. */
    const val POINTER_ID_MOUSE = -1L
    const val BUTTON_PRIMARY = 1
    const val BUTTON_SECONDARY = 2
    const val BUTTON_TERTIARY = 4

    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_VOLUME_UP = 24
    const val KEYCODE_VOLUME_DOWN = 25
    const val KEYCODE_POWER = 26
    const val KEYCODE_TAB = 61
    const val KEYCODE_ENTER = 66
    const val KEYCODE_DEL = 67
    const val KEYCODE_ESCAPE = 111
    const val KEYCODE_APP_SWITCH = 187

    private const val TYPE_INJECT_KEYCODE = 0
    private const val TYPE_INJECT_TEXT = 1
    private const val TYPE_INJECT_TOUCH_EVENT = 2
    private const val TYPE_INJECT_SCROLL_EVENT = 3
    private const val TYPE_BACK_OR_SCREEN_ON = 4
    private const val TYPE_ROTATE_DEVICE = 11
    private const val TYPE_UHID_CREATE = 12
    private const val TYPE_UHID_INPUT = 13
    private const val TYPE_RESET_VIDEO = 17

    const val TEXT_MAX_LENGTH = 300

    fun touch(
        action: Int, pointerId: Long, x: Int, y: Int, screenW: Int, screenH: Int, pressure: Float,
        actionButton: Int = 0, buttons: Int = 0,
    ): ByteArray {
        val p = (pressure.coerceIn(0f, 1f) * 65535f).toInt()
        return ByteBuffer.allocate(32)
            .put(TYPE_INJECT_TOUCH_EVENT.toByte())
            .put(action.toByte())
            .putLong(pointerId)
            .putInt(x).putInt(y)
            .putShort(screenW.toShort()).putShort(screenH.toShort())
            .putShort(p.toShort())
            .putInt(actionButton)
            .putInt(buttons)
            .array()
    }

    /** Rolagem de mouse. Valores em "linhas" (a roda do mouse costuma enviar ±1 por clique). */
    fun scroll(x: Int, y: Int, screenW: Int, screenH: Int, hScroll: Float, vScroll: Float, buttons: Int = 0): ByteArray {
        fun fixed(v: Float) = ((v / 16f).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        return ByteBuffer.allocate(21)
            .put(TYPE_INJECT_SCROLL_EVENT.toByte())
            .putInt(x).putInt(y)
            .putShort(screenW.toShort()).putShort(screenH.toShort())
            .putShort(fixed(hScroll)).putShort(fixed(vScroll))
            .putInt(buttons)
            .array()
    }

    fun keycode(action: Int, keycode: Int, repeat: Int = 0, metaState: Int = 0): ByteArray =
        ByteBuffer.allocate(14)
            .put(TYPE_INJECT_KEYCODE.toByte())
            .put(action.toByte())
            .putInt(keycode).putInt(repeat).putInt(metaState)
            .array()

    fun text(text: String): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(5 + raw.size)
            .put(TYPE_INJECT_TEXT.toByte()).putInt(raw.size).put(raw).array()
    }

    fun backOrScreenOn(action: Int): ByteArray =
        byteArrayOf(TYPE_BACK_OR_SCREEN_ON.toByte(), action.toByte())

    /** Cria um dispositivo HID virtual no outro aparelho (usado para controles de jogo). */
    fun uhidCreate(id: Int, vendorId: Int, productId: Int, name: String, reportDescriptor: ByteArray): ByteArray {
        var nameBytes = name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > 127) nameBytes = nameBytes.copyOf(127)
        return ByteBuffer.allocate(1 + 2 + 2 + 2 + 1 + nameBytes.size + 2 + reportDescriptor.size)
            .put(TYPE_UHID_CREATE.toByte())
            .putShort(id.toShort()).putShort(vendorId.toShort()).putShort(productId.toShort())
            .put(nameBytes.size.toByte()).put(nameBytes)
            .putShort(reportDescriptor.size.toShort()).put(reportDescriptor)
            .array()
    }

    fun uhidInput(id: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(1 + 2 + 2 + data.size)
            .put(TYPE_UHID_INPUT.toByte())
            .putShort(id.toShort()).putShort(data.size.toShort()).put(data)
            .array()

    fun rotateDevice(): ByteArray = byteArrayOf(TYPE_ROTATE_DEVICE.toByte())

    fun resetVideo(): ByteArray = byteArrayOf(TYPE_RESET_VIDEO.toByte())
}

/**
 * Canal de controle (RECEPTOR → CÉREBRO). As mensagens entram numa fila e uma única thread
 * escreve no socket, então a thread de interface nunca bloqueia em rede. Mensagens
 * enfileiradas juntas são enviadas em uma só escrita.
 */
class ControlChannel(private val out: OutputStream, private val onFailure: (Exception) -> Unit) : Closeable {
    private val queue = LinkedBlockingQueue<ByteArray>(4096)

    @Volatile
    private var closed = false

    private val writer = Thread({ writeLoop() }, "scrcpy-control-writer").apply { isDaemon = true }

    fun start() = writer.start()

    fun send(message: ByteArray) {
        if (closed) return
        if (!queue.offer(message)) Log.w(TAG, "fila de controle cheia; mensagem descartada")
    }

    fun sendTouch(action: Int, pointerId: Long, x: Int, y: Int, w: Int, h: Int, pressure: Float) =
        send(ControlMessages.touch(action, pointerId, x, y, w, h, pressure))

    fun sendMouse(
        action: Int, x: Int, y: Int, w: Int, h: Int, actionButton: Int, buttons: Int,
    ) {
        val pressure = if (action == ControlMessages.ACTION_HOVER_MOVE || buttons == 0) 0f else 1f
        send(
            ControlMessages.touch(
                action, ControlMessages.POINTER_ID_MOUSE, x, y, w, h, pressure, actionButton, buttons,
            ),
        )
    }

    fun sendScroll(x: Int, y: Int, w: Int, h: Int, hScroll: Float, vScroll: Float, buttons: Int = 0) =
        send(ControlMessages.scroll(x, y, w, h, hScroll, vScroll, buttons))

    fun sendKey(keycode: Int) {
        send(ControlMessages.keycode(ControlMessages.ACTION_DOWN, keycode))
        send(ControlMessages.keycode(ControlMessages.ACTION_UP, keycode))
    }

    fun sendBack() {
        send(ControlMessages.backOrScreenOn(ControlMessages.ACTION_DOWN))
        send(ControlMessages.backOrScreenOn(ControlMessages.ACTION_UP))
    }

    fun sendText(text: String) {
        // O servidor limita o texto injetado a 300 caracteres por mensagem.
        text.chunked(ControlMessages.TEXT_MAX_LENGTH).forEach { send(ControlMessages.text(it)) }
    }

    private fun writeLoop() {
        val batch = ArrayList<ByteArray>()
        try {
            while (!closed) {
                val first = queue.take()
                batch.clear()
                batch.add(first)
                queue.drainTo(batch)
                val total = batch.sumOf { it.size }
                val buf = ByteArray(total)
                var off = 0
                for (m in batch) {
                    System.arraycopy(m, 0, buf, off, m.size)
                    off += m.size
                }
                out.write(buf)
                out.flush()
            }
        } catch (e: InterruptedException) {
            // fechamento normal
        } catch (e: Exception) {
            if (!closed) onFailure(e)
        }
    }

    override fun close() {
        closed = true
        writer.interrupt()
    }

    companion object {
        private const val TAG = "ControlChannel"

        /** Lê e descarta as mensagens do CÉREBRO (clipboard, ack, UHID) para o socket nunca encher. */
        fun drainDeviceMessages(input: InputStream) {
            val dis = java.io.DataInputStream(java.io.BufferedInputStream(input))
            while (true) {
                when (val type = dis.readUnsignedByte()) {
                    0 -> dis.skipFully(dis.readInt())          // clipboard: len + texto
                    1 -> dis.skipFully(8)                      // ack de clipboard: sequência
                    2 -> { dis.skipFully(2); dis.skipFully(dis.readUnsignedShort()) } // uhid output
                    else -> throw IOException("Mensagem do dispositivo desconhecida: $type")
                }
            }
        }

        private fun java.io.DataInputStream.skipFully(count: Int) {
            if (count < 0 || count > (1 shl 20)) throw IOException("Tamanho inválido: $count")
            readFully(ByteArray(count))
        }
    }
}
