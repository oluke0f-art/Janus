package app.scrcpya2a.session

/** O que o CÉREBRO deve transmitir. Os modos são independentes. */
sealed interface SessionConfig {
    /** Tela física na resolução nativa: nenhuma opção de tamanho/densidade é enviada. */
    data object MainScreen : SessionConfig

    /**
     * Display virtual REAL criado pelo Android no CÉREBRO (opção `new_display` do scrcpy,
     * que chama DisplayManager.createNewVirtualDisplay com este tamanho). A tela física não é alterada.
     */
    data class VirtualDisplay(val width: Int, val height: Int, val dpi: Int?) : SessionConfig

    /** Conecta a um display que já existe no CÉREBRO (opção `display_id`), sem criar outro. */
    data class ExistingDisplay(val displayId: Int, val width: Int, val height: Int) : SessionConfig
}

data class SessionOptions(
    /** Recebe o áudio do CÉREBRO (PCM bruto; Android 11+ no CÉREBRO). */
    val audio: Boolean = true,
    /** Ao criar um monitor virtual, encerra antes os servidores antigos (e seus monitores). */
    val closeOldServers: Boolean = true,
)

object ServerArgs {
    fun build(scid: Int, config: SessionConfig, options: SessionOptions): List<String> {
        val args = mutableListOf(
            ServerAsset.SERVER_VERSION, // o servidor recusa versão diferente da sua
            "scid=%08x".format(scid),
            "log_level=info",
            "tunnel_forward=true", // o RECEPTOR conecta nos sockets do CÉREBRO
            "video=true",
            "control=true",
            "video_codec=h264",
            "clipboard_autosync=false",
        )
        if (options.audio) {
            // PCM 16 bits, 48 kHz, estéreo: sem decodificador, menor latência e menos pontos de falha.
            args += "audio=true"
            args += "audio_codec=raw"
        } else {
            args += "audio=false"
        }
        when (config) {
            is SessionConfig.MainScreen -> Unit
            is SessionConfig.VirtualDisplay -> {
                val dpi = config.dpi?.let { "/$it" } ?: ""
                args += "new_display=${config.width}x${config.height}$dpi"
                args += "video_bit_rate=${bitRateFor(config.width, config.height)}"
            }
            is SessionConfig.ExistingDisplay -> {
                args += "display_id=${config.displayId}"
                args += "video_bit_rate=${bitRateFor(config.width, config.height)}"
            }
        }
        return args
    }

    private fun bitRateFor(width: Int, height: Int): Long =
        (width.toLong() * height * 3).coerceIn(4_000_000L, 24_000_000L)
}
