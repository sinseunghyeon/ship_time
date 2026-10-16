package com.edencrew.shiptime

import android.content.Context
import android.os.Build
import android.sun.misc.BASE64Encoder
import android.sun.security.provider.X509Factory
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * 같은 기기의 무선 디버깅(adbd)에 연결하기 위한 ADB 클라이언트.
 * 키/인증서는 앱 private 저장소에 보관해 한 번 페어링하면 재사용한다.
 */
class ShipAdbManager private constructor(context: Context) : AbsAdbConnectionManager() {
    private val app = context.applicationContext
    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val loadedKey = readPrivateKey()
        val loadedCert = readCertificate()
        if (loadedKey != null && loadedCert != null) {
            privateKey = loadedKey
            certificate = loadedCert
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            val kp = kpg.generateKeyPair()
            privateKey = kp.private
            certificate = generateCert(kp.public, privateKey)
            writePrivateKey(privateKey)
            writeCertificate(certificate)
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "ShipTime"

    private fun keyFile() = File(app.filesDir, "adb_private.key")
    private fun certFile() = File(app.filesDir, "adb_cert.pem")

    private fun readPrivateKey(): PrivateKey? {
        val f = keyFile()
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        } catch (_: Throwable) {
            null
        }
    }

    private fun readCertificate(): Certificate? {
        val f = certFile()
        if (!f.exists()) return null
        return try {
            FileInputStream(f).use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writePrivateKey(key: PrivateKey) {
        FileOutputStream(keyFile()).use { it.write(key.encoded) }
    }

    private fun writeCertificate(cert: Certificate) {
        val encoder = BASE64Encoder()
        FileOutputStream(certFile()).use { os ->
            os.write(X509Factory.BEGIN_CERT.toByteArray(StandardCharsets.UTF_8))
            os.write('\n'.code)
            encoder.encode(cert.encoded, os)
            os.write('\n'.code)
            os.write(X509Factory.END_CERT.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun generateCert(publicKey: java.security.PublicKey, privateKey: PrivateKey): Certificate {
        val subject = "CN=Ship Time"
        val algorithmName = "SHA512withRSA"
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650))
        val extensions = CertificateExtensions().apply {
            set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier))
            set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        }
        val x500 = X500Name(subject)
        val info = X509CertInfo().apply {
            set("version", CertificateVersion(2))
            set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithmName)))
            set("subject", CertificateSubjectName(x500))
            set("key", CertificateX509Key(publicKey))
            set("validity", CertificateValidity(notBefore, notAfter))
            set("issuer", CertificateIssuerName(x500))
            set("extensions", extensions)
        }
        return X509CertImpl(info).also { it.sign(privateKey, algorithmName) }
    }

    companion object {
        @Volatile private var instance: ShipAdbManager? = null

        fun get(context: Context): ShipAdbManager {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ShipAdbManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
