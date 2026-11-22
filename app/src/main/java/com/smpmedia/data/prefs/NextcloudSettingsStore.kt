package com.smpmedia.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.smpmedia.data.model.MediaSourceKind
import com.smpmedia.data.model.NextcloudSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nextcloud ayarlarını [SmbSettingsStore] ile AYNI disiplinde saklar:
 * EncryptedSharedPreferences + Android Keystore. Uygulama şifresi diske düz
 * metin yazılmaz.
 *
 * Keystore bozulursa (cihaz yedeğinden geri yükleme vb.) dosya bir kez
 * silinip yeniden kurulur; yine olmazsa uygulama aç kalmasın diye düz
 * tercihlere düşülür. SmbSettingsStore'daki üç kademeli davranış bilinçli
 * olarak tekrarlanmıştır — iki store'un farklı davranması, "ayarlarım
 * kayboluyor" şikâyetini gizlerdi.
 */
@Singleton
class NextcloudSettingsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = createPreferences(context)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<NextcloudSettings> = _settings.asStateFlow()

    /**
     * Her çağrıda diskteki (şifreli) değerleri yeniden okur. Akış değeri
     * yalnızca [save] ile güncellendiğinden, başka bir bileşen (ör. arka plan
     * servisi) dosyayı değiştirdiğinde bellek kopyası bayatlayabiliyordu;
     * bayat şifreyle yapılan istekler Nextcloud'un kaba kuvvet korumasını
     * tetikleyip doğru şifreli istekleri de 401'e düşürüyordu.
     */
    fun current(): NextcloudSettings = read()

    fun save(settings: NextcloudSettings) {
        val clean = settings.copy(
            baseUrl = normalizeBaseUrl(settings.baseUrl),
            username = settings.username.trim(),
            rootFolder = settings.rootFolder.trim().trim('/')
        )
        prefs.edit()
            .putBoolean(KEY_ENABLED, clean.enabled)
            .putString(KEY_BASE_URL, clean.baseUrl)
            .putString(KEY_USERNAME, clean.username)
            .putString(KEY_APP_PASSWORD, clean.appPassword)
            .putString(KEY_ROOT_FOLDER, clean.rootFolder)
            .putBoolean(KEY_ALLOW_SELF_SIGNED, clean.allowSelfSigned)
            .putString(KEY_PRIMARY_SOURCE, clean.primarySource.name)
            .apply()
        _settings.value = clean
    }

    private fun read(): NextcloudSettings = NextcloudSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        baseUrl = prefs.getString(KEY_BASE_URL, NextcloudSettings.DEFAULT_BASE_URL)
            ?: NextcloudSettings.DEFAULT_BASE_URL,
        username = prefs.getString(KEY_USERNAME, "") ?: "",
        appPassword = prefs.getString(KEY_APP_PASSWORD, "") ?: "",
        rootFolder = prefs.getString(KEY_ROOT_FOLDER, "") ?: "",
        allowSelfSigned = prefs.getBoolean(KEY_ALLOW_SELF_SIGNED, false),
        primarySource = runCatching {
            MediaSourceKind.valueOf(prefs.getString(KEY_PRIMARY_SOURCE, null) ?: "")
        }.getOrDefault(MediaSourceKind.SMB)
    )

    /**
     * Kullanıcı adrese şema yazmayabilir ("192.168.1.20:8080"). Şemasız bir
     * adres OkHttp'de "Expected URL scheme" hatasıyla patlar ve kullanıcıya
     * "sunucu bulunamadı" gibi yanlış bir mesaj döner. Burada sessizce http://
     * ekliyoruz; kullanıcı bir şeyi yanlış yazmış hissi yaşamaz.
     */
    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    private fun createPreferences(context: Context): SharedPreferences = try {
        buildEncrypted(context)
    } catch (_: Throwable) {
        context.deleteSharedPreferences(FILE_NAME)
        try {
            buildEncrypted(context)
        } catch (_: Throwable) {
            context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
        }
    }

    private fun buildEncrypted(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        /**
         * SMB tarafindan AYRI dosya: iki store aynı dosyayı paylaşırsa birinin
         * `clear()` çağrısı diğerinin ayarlarını da siler.
         */
        private const val FILE_NAME = "nextcloud_settings_encrypted"
        private const val FALLBACK_FILE = "nextcloud_settings_fallback"

        private const val KEY_ENABLED = "nc_enabled"
        private const val KEY_BASE_URL = "nc_base_url"
        private const val KEY_USERNAME = "nc_username"
        private const val KEY_APP_PASSWORD = "nc_app_password"
        private const val KEY_ROOT_FOLDER = "nc_root_folder"
        private const val KEY_ALLOW_SELF_SIGNED = "nc_allow_self_signed"
        private const val KEY_PRIMARY_SOURCE = "nc_primary_source"
    }
}
