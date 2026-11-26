package app.scrcpya2a.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/** Um serviço de Depuração sem fio anunciado na rede (via mDNS). */
data class AdbService(val name: String, val host: String, val port: Int)

/**
 * Descobre, pela rede Wi-Fi, aparelhos com Depuração sem fio ligada:
 *  - `_adb-tls-connect._tcp`: o aparelho aceita conexão (IP e porta de conexão, sempre atuais)
 *  - `_adb-tls-pairing._tcp`: a janela "Parear com código" está aberta (IP e porta de pareamento)
 * Assim o usuário não precisa digitar IP nem portas; só o código de 6 dígitos no pareamento.
 */
class AdbDiscovery(
    context: Context,
    private val onChanged: (connect: List<AdbService>, pairing: List<AdbService>) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectServices = LinkedHashMap<String, AdbService>()
    private val pairingServices = LinkedHashMap<String, AdbService>()
    private val queue = ArrayDeque<Pair<NsdServiceInfo, Boolean>>() // (serviço, é pareamento?)
    private var resolving = false
    private var connectListener: NsdManager.DiscoveryListener? = null
    private var pairingListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        connectListener = listener(pairing = false).also { discover(TYPE_CONNECT, it) }
        pairingListener = listener(pairing = true).also { discover(TYPE_PAIRING, it) }
    }

    fun stop() {
        running = false
        connectListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        pairingListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        connectListener = null
        pairingListener = null
        synchronized(this) {
            connectServices.clear()
            pairingServices.clear()
            queue.clear()
            resolving = false
        }
    }

    /** Esquece uma janela de pareamento que já fechou (a lista do mDNS pode demorar a perceber). */
    fun forgetPairing(host: String, port: Int) {
        val changed = synchronized(this) {
            val before = pairingServices.size
            pairingServices.entries.removeAll { it.value.host == host && it.value.port == port }
            pairingServices.size != before
        }
        if (changed) publish()
    }

    private fun discover(type: String, listener: NsdManager.DiscoveryListener) {
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "não foi possível procurar $type: ${e.message}")
        }
    }

    private fun listener(pairing: Boolean) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "falha ao iniciar a busca ($serviceType): $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            synchronized(this@AdbDiscovery) { queue.addLast(serviceInfo to pairing) }
            resolveNext()
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val changed = synchronized(this@AdbDiscovery) {
                (if (pairing) pairingServices else connectServices).remove(serviceInfo.serviceName) != null
            }
            if (changed) publish()
        }
    }

    /** O NsdManager só resolve um serviço por vez; por isso a fila. */
    private fun resolveNext() {
        val next: Pair<NsdServiceInfo, Boolean>
        synchronized(this) {
            if (resolving || queue.isEmpty() || !running) return
            resolving = true
            next = queue.removeFirst()
        }
        val (info, pairing) = next
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        finishResolve()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val address = serviceInfo.host
                        // Só IPv4: o ADB sem fio é usado assim e endereços IPv6 locais exigem escopo.
                        if (address is Inet4Address && (address.hostAddress ?: "") !in localAddresses()) {
                            val service = AdbService(serviceInfo.serviceName, address.hostAddress ?: "", serviceInfo.port)
                            synchronized(this@AdbDiscovery) {
                                (if (pairing) pairingServices else connectServices)[serviceInfo.serviceName] = service
                            }
                            publish()
                        }
                        finishResolve()
                    }
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "falha ao resolver serviço: ${e.message}")
            finishResolve()
        }
    }

    private fun finishResolve() {
        synchronized(this) { resolving = false }
        resolveNext()
    }

    private fun publish() {
        val connect: List<AdbService>
        val pairing: List<AdbService>
        synchronized(this) {
            connect = connectServices.values.toList()
            pairing = pairingServices.values.toList()
        }
        onChanged(connect, pairing)
    }

    /** Endereços deste próprio aparelho (para não listar o próprio celular como "dispositivo encontrado"). */
    private fun localAddresses(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }

    companion object {
        private const val TAG = "AdbDiscovery"
        private const val TYPE_CONNECT = "_adb-tls-connect._tcp"
        private const val TYPE_PAIRING = "_adb-tls-pairing._tcp"
    }
}
