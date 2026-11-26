package app.scrcpya2a.adb

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Ponto de encontro entre o serviço de pareamento por notificação (que roda em segundo plano) e a interface:
 * quando o serviço conecta, a interface percebe e vai para a tela de monitores.
 */
object ConnectionHub {
    /** Uma conexão pronta feita em segundo plano, à espera de ser adotada pela interface. */
    data class Incoming(val host: String, val port: Int, val adb: AppAdbManager)

    var incoming by mutableStateOf<Incoming?>(null)
}

/** Mensagens de erro de ADB em português e com a causa provável. */
object AdbErrors {
    fun describe(e: Throwable): String {
        // mensagens já escritas por nós (IllegalStateException com texto próprio) valem mais que a causa técnica
        if (e is IllegalStateException && !e.message.isNullOrBlank()) return e.message ?: ""
        val raw = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        return when {
            raw.contains("ECONNREFUSED", true) || raw.contains("refused", true) ->
                "O aparelho recusou a conexão: a janela de pareamento não está mais aberta. O Android a fecha ao sair das " +
                    "Configurações. Reabra “Parear dispositivo com código” e use “Parear pela notificação” (ou a tela dividida)."
            raw.contains("timed out", true) || raw.contains("ETIMEDOUT", true) || raw.contains("timeout", true) ->
                "Tempo esgotado. Os dois aparelhos estão na mesma rede Wi-Fi e com a Depuração sem fio ligada?"
            raw.contains("unreachable", true) || raw.contains("ENETUNREACH", true) || raw.contains("EHOSTUNREACH", true) ->
                "Aparelho inalcançável. Confira se os dois estão na mesma rede Wi-Fi."
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    fun isRefused(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { (it.message ?: "").contains("refused", true) }
}
