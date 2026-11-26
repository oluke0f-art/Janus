package app.scrcpya2a.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Gerenciador ADB do RECEPTOR. A chave RSA e o certificado são gerados uma vez e
 * guardados em filesDir: o pareamento registra ESTA chave no CÉREBRO, então ela precisa
 * ser a mesma nas próximas conexões.
 */
class AppAdbManager private constructor(
    private val keys: Pair<PrivateKey, Certificate>,
) : AbsAdbConnectionManager() {

    init {
        setApi(Build.VERSION.SDK_INT)
    }

    override fun getPrivateKey(): PrivateKey = keys.first

    override fun getCertificate(): Certificate = keys.second

    override fun getDeviceName(): String = "Janus"

    companion object {
        @Volatile
        private var sharedKeys: Pair<PrivateKey, Certificate>? = null

        private fun keysFor(context: Context): Pair<PrivateKey, Certificate> =
            sharedKeys ?: synchronized(this) {
                sharedKeys ?: loadOrCreate(context.applicationContext.filesDir).also { sharedKeys = it }
            }

        /**
         * Cria uma conexão nova (uma por aparelho). Todas usam a MESMA chave ADB, que é a que foi pareada.
         */
        fun create(context: Context): AppAdbManager = AppAdbManager(keysFor(context))

        private fun loadOrCreate(dir: File): Pair<PrivateKey, Certificate> {
            val keyFile = File(dir, "adb_key.pk8")
            val certFile = File(dir, "adb_cert.der")
            if (keyFile.exists() && certFile.exists()) {
                try {
                    val key = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(certFile.readBytes()))
                    return key to cert
                } catch (_: Exception) {
                    // arquivo corrompido: gera novamente (será preciso parear de novo)
                }
            }
            val created = generate()
            keyFile.writeBytes(created.first.encoded)
            certFile.writeBytes(created.second.encoded)
            return created
        }

        private fun generate(): Pair<PrivateKey, Certificate> {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom())
            val pair = generator.generateKeyPair()
            val der = Der.selfSignedCertificate(pair.public.encoded, pair.private, "Scrcpy A2A")
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
            return pair.private to cert
        }
    }

    /**
     * Certificado X.509 v3 autoassinado (RSA + SHA-256) montado em DER, sem bibliotecas extras.
     * O adbd só usa a chave pública do certificado do cliente (a que foi pareada).
     */
    private object Der {
        private val SHA256_RSA_ALGORITHM = seq(
            tlv(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)),
            byteArrayOf(0x05, 0x00),
        )

        fun selfSignedCertificate(spki: ByteArray, privateKey: PrivateKey, commonName: String): ByteArray {
            val name = seq(tlv(0x31, seq(tlv(0x06, byteArrayOf(0x55, 0x04, 0x03)), tlv(0x0C, commonName.toByteArray(Charsets.UTF_8)))))
            val now = System.currentTimeMillis()
            val validity = seq(utcTime(Date(now - 24L * 3600 * 1000)), utcTime(Date(now + 10L * 365 * 24 * 3600 * 1000)))
            val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
            val tbs = seq(
                tlv(0xA0, tlv(0x02, byteArrayOf(2))), // versão 3
                tlv(0x02, serial.toByteArray()),
                SHA256_RSA_ALGORITHM,
                name,
                validity,
                name,
                spki, // SubjectPublicKeyInfo já vem em DER de PublicKey.encoded
            )
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(privateKey)
                update(tbs)
                sign()
            }
            return seq(tbs, SHA256_RSA_ALGORITHM, tlv(0x03, byteArrayOf(0) + signature))
        }

        private fun utcTime(date: Date): ByteArray {
            val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return tlv(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
        }

        private fun seq(vararg parts: ByteArray): ByteArray {
            var body = ByteArray(0)
            for (p in parts) body += p
            return tlv(0x30, body)
        }

        private fun tlv(tag: Int, body: ByteArray): ByteArray {
            val n = body.size
            val length = when {
                n < 0x80 -> byteArrayOf(n.toByte())
                n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
                else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
            }
            return byteArrayOf(tag.toByte()) + length + body
        }
    }
}
