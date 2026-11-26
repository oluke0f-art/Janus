package app.scrcpya2a.adb

import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Envio de arquivo pelo serviço "sync:" do ADB (equivalente a `adb push`). */
object AdbSync {
    private const val MODE_FILE_644 = 33188 // 0100644 em decimal, como o cliente adb envia
    private const val CHUNK = 32 * 1024

    private fun header(id: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII)).putInt(value).array()

    fun push(adb: AbsAdbConnectionManager, source: InputStream, remotePath: String) {
        val stream = adb.openStream("sync:")
        try {
            val out = stream.openOutputStream()
            val input = DataInputStream(stream.openInputStream())

            val spec = "$remotePath,$MODE_FILE_644".toByteArray(Charsets.UTF_8)
            out.write(header("SEND", spec.size) + spec)
            out.flush()

            val buf = ByteArray(CHUNK)
            while (true) {
                val n = source.read(buf)
                if (n < 0) break
                out.write(header("DATA", n) + buf.copyOf(n))
                out.flush()
            }
            out.write(header("DONE", (System.currentTimeMillis() / 1000).toInt()))
            out.flush()

            val id = ByteArray(4)
            input.readFully(id)
            val status = String(id, Charsets.US_ASCII)
            val lenBytes = ByteArray(4)
            input.readFully(lenBytes)
            val len = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).int
            if (status != "OKAY") {
                val msg = if (status == "FAIL" && len in 1..4096) {
                    ByteArray(len).also { input.readFully(it) }.toString(Charsets.UTF_8)
                } else status
                throw IOException("Falha ao enviar o servidor: $msg")
            }
            try {
                out.write(header("QUIT", 0))
                out.flush()
            } catch (_: Exception) {
                // o arquivo já foi aceito (OKAY); o fim da conversa não importa
            }
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
    }
}
