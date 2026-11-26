package app.scrcpya2a.session

import app.scrcpya2a.adb.AdbSync
import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager

/** Um display existente no CÉREBRO (resultado de `list_displays` do servidor). */
data class RemoteDisplay(val id: Int, val width: Int, val height: Int) {
    val isMain: Boolean get() = id == 0
}

/** Comandos auxiliares executados no CÉREBRO pela conexão ADB. */
object DeviceTools {
    /**
     * Cada sessão usa o seu próprio arquivo: ao terminar, o servidor apaga o jar que usou, e assim
     * uma sessão não derruba a vizinha (nem a busca de telas).
     */
    fun serverPath(tag: String) = "/data/local/tmp/janus-server-$tag.jar"

    /**
     * Executa um comando de shell e devolve a saída. O stream é fechado após [timeoutMs].
     * A leitura é feita à mão: o fim do stream pode chegar como exceção ("Stream closed") em vez de -1,
     * e nesse caso o que já foi lido continua valendo.
     */
    fun runShell(adb: AbsAdbConnectionManager, command: String, timeoutMs: Long = 15_000): String {
        val stream = adb.openStream("shell:$command")
        val watchdog = Thread({
            try {
                Thread.sleep(timeoutMs)
                runCatching { stream.close() }
            } catch (_: InterruptedException) {
            }
        }, "shell-watchdog").apply { isDaemon = true }
        watchdog.start()
        val out = java.io.ByteArrayOutputStream()
        try {
            val input = stream.openInputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } catch (_: Exception) {
            // fim do stream (ou tempo esgotado): usa o que chegou
        } finally {
            watchdog.interrupt()
            runCatching { stream.close() }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Lista os displays do CÉREBRO usando o próprio servidor scrcpy (`list_displays=true`). */
    fun listDisplays(context: Context, adb: AbsAdbConnectionManager): List<RemoteDisplay> {
        val path = serverPath("list")
        ServerAsset.open(context).use { AdbSync.push(adb, it, path) }
        val out = runShell(
            adb,
            "CLASSPATH=$path app_process / com.genymobile.scrcpy.Server " +
                "${ServerAsset.SERVER_VERSION} log_level=info list_displays=true",
            timeoutMs = 8_000,
        )
        val regex = Regex("--display-id=(\\d+)\\s+\\((\\d+)x(\\d+)\\)")
        val list = regex.findAll(out).map {
            RemoteDisplay(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }.toList()
        if (list.isEmpty()) {
            throw java.io.IOException("resposta inesperada do servidor: ${out.trim().take(160).ifEmpty { "(vazia)" }}")
        }
        return list
    }

    /**
     * Encerra servidores scrcpy no CÉREBRO. Com [scid], só o da sessão indicada; sem, todos.
     * Quando o servidor morre, o monitor virtual dele é destruído. O colchete no padrão
     * evita que o próprio `sh` do comando case com o padrão e se mate.
     */
    fun killServers(adb: AbsAdbConnectionManager, scid: Int? = null) {
        val pattern = if (scid != null) "[s]cid=%08x".format(scid) else "[c]om.genymobile.scrcpy"
        runShell(
            adb,
            "pkill -f '$pattern' 2>/dev/null || kill \$(pgrep -f '$pattern') 2>/dev/null; echo ok",
            timeoutMs = 5_000,
        )
    }

    /**
     * Encerra os servidores scrcpy que NÃO pertencem às sessões em [keep] (monitores "sem dono",
     * por exemplo sobras de versões antigas). Não afeta as sessões listadas.
     */
    fun killServersExcept(adb: AbsAdbConnectionManager, keep: Collection<Int>) {
        val action = if (keep.isEmpty()) {
            "kill \$pid;"
        } else {
            val patterns = keep.joinToString("|") { "*scid=%08x*".format(it) }
            "case \"\$args\" in $patterns) ;; *) kill \$pid ;; esac;"
        }
        runShell(
            adb,
            "ps -A -o PID,ARGS | grep '[s]cid=' | while read pid args; do $action done; echo ok",
            timeoutMs = 8_000,
        )
    }
}
