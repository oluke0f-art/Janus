package app.scrcpya2a.session

import android.content.Context
import java.security.MessageDigest

/**
 * Acesso ao scrcpy-server 5.0.1 embutido no APK (asset gerado por :server).
 * Na fase 2 este mesmo arquivo será enviado ao CÉREBRO via ADB (sync) e executado
 * com app_process.
 */
object ServerAsset {
    const val ASSET_NAME = "scrcpy-server"
    const val SERVER_VERSION = "5.0.1" // deve coincidir com versionName do módulo :server

    data class Info(val sizeBytes: Long, val sha256: String)

    fun open(context: Context): java.io.InputStream = try {
        context.assets.open(ASSET_NAME)
    } catch (e: java.io.FileNotFoundException) {
        val found = try {
            context.assets.list("")?.joinToString() ?: ""
        } catch (_: Exception) {
            "?"
        }
        throw java.io.IOException(
            "O servidor scrcpy não está dentro do APK (asset \"$ASSET_NAME\" ausente; assets presentes: [$found]). " +
                "No build, procure a linha \"scrcpy-server embutido\" na aba Build. " +
                "Alternativa manual: copie server/build/outputs/apk/release/*.apk para " +
                "app/src/main/assets/$ASSET_NAME e rode de novo.",
            e,
        )
    }

    /** Lê o asset por inteiro; lança IOException se ele não estiver no APK. */
    fun inspect(context: Context): Info {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        open(context).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                total += n
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return Info(total, hex)
    }
}
