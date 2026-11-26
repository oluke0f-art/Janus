package app.scrcpya2a.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.scrcpya2a.R
import app.scrcpya2a.ui.MainActivity
import java.util.concurrent.Executors

/**
 * Pareamento pela notificação. O Android fecha a janela "Parear com código" quando você sai das
 * Configurações, então não dá para digitar o código dentro do app. Este serviço fica em segundo plano
 * procurando a janela e mostra uma notificação com um campo de texto: você puxa a barra de notificações
 * (a janela de pareamento continua aberta por baixo), digita os 6 dígitos e o Janus pareia e conecta.
 */
class PairingService : Service() {

    private var discovery: AdbDiscovery? = null

    @Volatile
    private var pairing: AdbService? = null

    @Volatile
    private var connects: List<AdbService> = emptyList()

    @Volatile
    private var working = false

    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_PAIR -> {
                goForeground(currentNotification())
                handleCode(intent.getStringExtra(EXTRA_CODE).orEmpty().filter(Char::isDigit))
            }
            else -> {
                goForeground(searchingNotification())
                startWatching()
            }
        }
        return START_NOT_STICKY
    }

    private fun startWatching() {
        if (discovery != null) return
        discovery = AdbDiscovery(this) { connect, pairingList ->
            connects = connect
            pairing = pairingList.firstOrNull()
            if (!working) notifyNow(currentNotification())
        }.also { it.start() }
    }

    private fun handleCode(code: String) {
        val target = pairing
        when {
            code.length != 6 -> {
                notifyNow(readyOrSearching("Código inválido: são 6 dígitos."))
            }
            target == null -> {
                notifyNow(searchingNotification("A janela de pareamento não está aberta. Abra “Parear dispositivo com código”."))
            }
            else -> {
                working = true
                notifyNow(workingNotification("Pareando com ${target.host}…"))
                executor.execute { pairAndConnect(target, code) }
            }
        }
    }

    private fun pairAndConnect(target: AdbService, code: String) {
        try {
            val adb = AppAdbManager.create(application)
            val paired: Any? = adb.pair(target.host, target.port, code)
            if (paired == false) throw IllegalStateException("código incorreto ou expirado")
            notifyNow(workingNotification("Pareado! Conectando…"))
            var found: AdbService? = null
            for (i in 0 until 24) {
                found = connects.firstOrNull { it.host == target.host }
                if (found != null) break
                Thread.sleep(500)
            }
            val service = found ?: throw IllegalStateException(
                "pareado, mas o aparelho não apareceu para conectar. Abra o Janus e toque nele em “Aparelhos encontrados”.",
            )
            val connected: Any? = adb.connect(service.host, service.port)
            if (connected == false) throw IllegalStateException("pareado, mas não foi possível conectar")
            ConnectionHub.incoming = ConnectionHub.Incoming(service.host, service.port, adb)
            finish("Conectado a ${service.host}", "Toque para abrir o Janus.")
        } catch (e: Throwable) {
            Log.w(TAG, "pareamento falhou", e)
            working = false
            notifyNow(readyOrSearching("Falhou: ${AdbErrors.describe(e)}"))
        }
    }

    // --------------------------------------------------------------- notificações

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun builder(title: String, text: String): NotificationCompat.Builder {
        if (Build.VERSION.SDK_INT >= 26 && nm().getNotificationChannel(CHANNEL_ID) == null) {
            nm().createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Pareamento", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, PairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_janus)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Cancelar", cancel)
    }

    private fun searchingNotification(extra: String? = null): Notification =
        builder(
            "Janus · procurando a janela de pareamento",
            extra ?: "No outro aparelho, abra Depuração sem fio → “Parear dispositivo com código” e deixe a janela aberta.",
        ).build()

    private fun readyNotification(target: AdbService, note: String? = null): Notification {
        val input = RemoteInput.Builder(KEY_CODE).setLabel("Código de 6 dígitos").build()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val send = PendingIntent.getBroadcast(
            this, 3, Intent(this, PairingReceiver::class.java).setAction(ACTION_PAIR), flags,
        )
        val action = NotificationCompat.Action.Builder(0, "Digitar código", send).addRemoteInput(input).build()
        return builder(
            "Janus · janela detectada (${target.host}:${target.port})",
            note ?: "Toque em “Digitar código” e informe o código de 6 dígitos que aparece no outro aparelho.",
        ).addAction(action).build()
    }

    private fun workingNotification(text: String): Notification =
        builder("Janus · pareando", text).setProgress(0, 0, true).build()

    private fun currentNotification(): Notification =
        pairing?.let { readyNotification(it) } ?: searchingNotification()

    private fun readyOrSearching(note: String): Notification =
        pairing?.let { readyNotification(it, note) } ?: searchingNotification(note)

    private fun notifyNow(n: Notification) {
        try {
            nm().notify(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "notificação não pôde ser exibida: ${e.message}")
        }
    }

    private fun goForeground(n: Notification) {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "serviço em primeiro plano indisponível: ${e.message}")
        }
    }

    private fun finish(title: String, text: String) {
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val done = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_janus)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        shutdown()
        try {
            nm().notify(RESULT_ID, done)
        } catch (_: Exception) {
        }
    }

    private fun shutdown() {
        discovery?.stop()
        discovery = null
        executor.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        discovery?.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PairingService"
        private const val CHANNEL_ID = "janus_pairing"
        private const val NOTIFICATION_ID = 2
        private const val RESULT_ID = 3
        const val ACTION_PAIR = "app.scrcpya2a.PAIR"
        private const val ACTION_STOP = "app.scrcpya2a.PAIR_STOP"
        const val EXTRA_CODE = "code"
        const val KEY_CODE = "pairing_code"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PairingService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "não foi possível iniciar o pareamento por notificação: ${e.message}")
            }
        }
    }
}

/** Recebe o texto digitado na notificação e entrega ao [PairingService]. */
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingService.KEY_CODE)?.toString().orEmpty()
        val service = Intent(context, PairingService::class.java)
            .setAction(PairingService.ACTION_PAIR)
            .putExtra(PairingService.EXTRA_CODE, code)
        try {
            ContextCompat.startForegroundService(context, service)
        } catch (e: Exception) {
            Log.w("PairingReceiver", "não foi possível entregar o código: ${e.message}")
        }
    }
}
