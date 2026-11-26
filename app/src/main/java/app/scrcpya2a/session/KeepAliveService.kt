package app.scrcpya2a.session

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.scrcpya2a.R
import app.scrcpya2a.ui.MainActivity

/**
 * Serviço em primeiro plano que mantém o processo vivo enquanto houver monitores ativos, para que
 * eles continuem existindo com o app em segundo plano. Mostra uma notificação com o botão "Encerrar".
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = SessionManager.get(application)
        if (intent?.action == ACTION_STOP_ALL) {
            manager.closeAll()
        }
        val count = manager.activeCount()
        if (count == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(count),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // Sem serviço em primeiro plano as sessões funcionam enquanto o app estiver aberto.
            Log.w(TAG, "não foi possível iniciar o serviço em primeiro plano: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(count: Int): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Monitores ativos", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_janus)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (count == 1) "1 monitor ativo" else "$count monitores ativos")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Encerrar tudo", stop)
            .build()
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "janus_sessions"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP_ALL = "app.scrcpya2a.STOP_ALL"

        /** Liga o serviço quando há sessões e desliga quando não há. */
        fun sync(app: Application, sessionCount: Int) {
            val intent = Intent(app, KeepAliveService::class.java)
            try {
                if (sessionCount > 0) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "serviço não pôde ser alterado: ${e.message}")
            }
        }
    }
}
