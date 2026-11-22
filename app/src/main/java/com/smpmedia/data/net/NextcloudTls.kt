package com.smpmedia.data.net

import android.util.Log
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Nextcloud bağlantısının TLS davranışı.
 *
 * ## Varsayılan: şüpheci
 * Sistem köklerine dayanır; sertifika_self-signed_, süresi dolmuş ya da yanlış
 * adlıysa bağlantı KURULMAZ ve kullanıcı hatanın ne olduğunu görür. Sessizce
 * "nasılsa yerel ağ" diye doğrulamayı kapatmak, aradaki-adam saldırısı = çalınan
 * uygulama şifresi demekti; bu yüzden kapalı başlarız.
 *
 * ## Ayar açılırsa: "kendi imzaladığım sertifikaya güven"
 * Kendi sunucusunu LAN'da çalıştıranların çoğu Let's Encrypt kullanamaz (dışarıya
 * açık alan adı yok) ve kendinden imzalı sertifika ya da kendi küçük CA'larını
 * kullanır. Android bu sertifikaları sistem köklerinde tanımadığı için bağlantı
 * "Trust anchor for certification path not found" ile düşer. Kullanıcıya bu
 * duvarı aşmayı değil, *bilerek* kabul etmeyi teklif ediyoruz:
 *
 *   • Zincirin SON sertifikası kendi kendini imzalamış olabilir (kendinden imzalı
 *     sertifika VEYA mkcert benzeri özel bir CA), ama
 *   • tarih aralığı geçerli olmalı,
 *   • anahtar ve imza algoritması zayıf olamaz (MD5/SHA-1 reddedilir, RSA < 2048
 *     / EC < 224 bit reddedilir),
 *   • ANA MAKINE ADI DOĞRULAMASI HER ZAMAN AÇIKTIR (OkHttp'in kendi doğrulayıcısı
 *     çalışmaya devam eder; SAN/CN uyuşmazsa bağlantı yine reddedilir).
 *
 * Sertifikanın parmak izi bir kez günlüğe yazılır: kullanıcı sunucudaki parmak
 * iziyle karşılaştırıp "gerçekten benim sunucum" diyebilir.
 */
object NextcloudTls {

    private const val TAG = "SmpMedia"

    /** Günlüğe bir kez yazılan parmak izi (aynı sertifika için tekrar tekrar gürültü üretmeyelim). */
    @Volatile private var loggedFingerprint: String? = null

    /**
     * [builder]'a TLS katmanını takar.
     *
     * [allowPrivateTrust] her EL SIKIŞMASINDA yeniden okunur: ayar anahtarı
     * değiştirildiğinde kullanıcının uygulamayı yeniden başlatması gerekmez.
     */
    fun install(builder: OkHttpClient.Builder, allowPrivateTrust: () -> Boolean): OkHttpClient.Builder {
        val system = systemTrustManager() ?: return builder
        val manager = PrivateTrustManager(system, allowPrivateTrust)
        return runCatching {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(manager), null)
            builder.sslSocketFactory(sslContext.socketFactory, manager)
        }.onFailure {
            // TLS katmanı kurulamazsa istemci yine de kurulur; http:// kullanan
            // kurulumlar etkilenmez, https kullanan açık hata görür.
            Log.w(TAG, "TLS yapilandirmasi kurulamadi: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(builder)
    }

    private fun systemTrustManager(): X509TrustManager? = runCatching {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        // null = platformun kurulu kök sertifikaları.
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.getOrNull()

    /**
     * Önce sistem kökleri denenir; olmazsa ve kullanıcı izin verdiyse kendi
     * imzaladığı zincir kabul edilir.
     */
    private class PrivateTrustManager(
        private val system: X509TrustManager,
        private val allowPrivateTrust: () -> Boolean
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            // WebDAV istemcisi istemci sertifikası kullanmaz; yine de sistemi deneriz.
            system.checkClientTrusted(chain, authType)
        }

        @Throws(CertificateException::class)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
                return
            } catch (systemError: CertificateException) {
                if (!allowPrivateTrust()) throw privateTrustNeeded(systemError)
                verifyOwnChain(chain, systemError)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    /** Kullanıcıya NE YAPMASI gerektiğini söyleyen hata. */
    private fun privateTrustNeeded(cause: CertificateException): CertificateException {
        val message = "Sunucunun TLS sertifikası sistem kökleriyle doğrulanamadı " +
            "(${cause.message ?: cause.javaClass.simpleName}). Kendi kendine imzalı bir " +
            "sertifika kullanıyorsan Ayarlar → Nextcloud altındaki \"Kendi imzalı " +
            "sertifikaya güven\" anahtarını aç."
        return CertificateException(message, cause)
    }

    /**
     * Zincirin SON sertifikasının gerçekten KENDİ KENDİNİ imzaladığını doğrular.
     *
     * Bu kontrol olmadan "izin ver" anahtarı her sertifikayı kabul eden bir
     * anahtara dönerdi; oysa amaç yalnızca kullanıcının kendi PKI'sını
     * geçirmektir. Başkasının sunduğu rastgele bir kendinden imzalı sertifika
     * burada geçebilir, ANA MAKINE ADI uyuşmadığı için bağlantı yine kurulamaz
     * (OkHttp doğrulayıcısı devrede) ve parmak izi günlüğe düşer.
     */
    private fun verifyOwnChain(chain: Array<X509Certificate>, systemError: CertificateException) {
        if (chain.isEmpty()) throw CertificateException("Sunucu hiç sertifika göndermedi.", systemError)

        val root = chain.last()
        if (root.subjectX500Principal != root.issuerX500Principal) {
            throw CertificateException(
                "Sertifika bir sertifika otoritesine dayanıyor ama o otorite güvenilenlerde yok " +
                    "(${root.subjectX500Principal.name}); bu anahtar yalnızca KENDİ imzaladığın " +
                    "sertifikalar için tasarlandı.",
                systemError
            )
        }
        requireStrongKey(root)
        runCatching { root.verify(root.publicKey) }
            .onFailure { throw CertificateException("Kök sertifikanın imzası doğrulanamadı.", it) }

        // Kök kendi kendine imzalı olabilir; aradaki/leaf sertifikaların süresi ve
        // imza algoritması ayrıca denetlenir.
        val now = Date()
        chain.forEach { cert ->
            requireSignatureIsModern(cert)
            cert.checkValidity(now)
        }
        logFingerprintOnce(root)
    }

    private fun requireStrongKey(cert: X509Certificate) {
        val key = cert.publicKey
        val bits = when (key) {
            is RSAPublicKey -> key.modulus.bitLength()
            is ECPublicKey -> key.params.order.bitLength()
            else -> -1
        }
        val strong = when (key) {
            is RSAPublicKey -> bits >= 2048
            is ECPublicKey -> bits >= 224
            else -> false
        }
        if (!strong) {
            throw CertificateException(
                "Sertifika anahtarı zayıf (${if (bits < 0) key.algorithm else "$bits bit"}); " +
                    "en az 2048 bit RSA ya da 224 bit EC bekleniyor."
            )
        }
    }

    private fun requireSignatureIsModern(cert: X509Certificate) {
        val algorithm = cert.sigAlgName.uppercase()
        if (algorithm.contains("MD5") || algorithm.contains("SHA1")) {
            throw CertificateException("Sertifika zayıf imza algoritması kullanıyor: ${cert.sigAlgName}")
        }
    }

    private fun logFingerprintOnce(cert: X509Certificate) = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val fingerprint = digest.joinToString(":") { "%02X".format(it) }
        if (loggedFingerprint != fingerprint) {
            loggedFingerprint = fingerprint
            Log.i(
                TAG,
                "Kendi imzali Nextcloud sertifikasi kabul edildi: " +
                    cert.subjectX500Principal.name + " | SHA-256=" + fingerprint
            )
        }
    }
}
