package app.scrcpya2a

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // O pareamento ADB usa TLS 1.3 + exportação de material de chave, que o libadb-android
        // obtém do Conscrypt embutido (sem depender de APIs ocultas do sistema).
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }
}
