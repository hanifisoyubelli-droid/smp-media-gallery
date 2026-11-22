package com.smpmedia.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.smpmedia.data.model.SmbSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SMB ayarlarını EncryptedSharedPreferences içinde saklar.
 * Şifre hem diskte hem de Android Keystore anahtarıyla şifrelenir.
 */
@Singleton
class SmbSettingsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = createPreferences(context)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<SmbSettings> = _settings.asStateFlow()

    fun current(): SmbSettings = _settings.value

    fun save(settings: SmbSettings) {
        val clean = settings.copy(
            host = settings.host.trim(),
            share = settings.share.trim().trim('/'),
            domain = settings.domain.trim(),
            username = settings.username.trim(),
            userFolder = settings.userFolder.trim().trim('/')
        )
        prefs.edit()
            .putString(KEY_HOST, clean.host)
            .putString(KEY_SHARE, clean.share)
            .putString(KEY_DOMAIN, clean.domain)
            .putString(KEY_USERNAME, clean.username)
            .putString(KEY_PASSWORD, clean.password)
            .putInt(KEY_PORT, clean.port)
            .putBoolean(KEY_AUTO_BACKUP, clean.autoBackup)
            .putInt(KEY_INTERVAL, clean.intervalHours)
            .putString(KEY_USER_FOLDER, clean.userFolder)
            .apply()
        _settings.value = clean
    }

    private fun read(): SmbSettings = SmbSettings(
        host = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST,
        share = prefs.getString(KEY_SHARE, "") ?: "",
        domain = prefs.getString(KEY_DOMAIN, "") ?: "",
        username = prefs.getString(KEY_USERNAME, "") ?: "",
        password = prefs.getString(KEY_PASSWORD, "") ?: "",
        port = prefs.getInt(KEY_PORT, 445),
        autoBackup = prefs.getBoolean(KEY_AUTO_BACKUP, true),
        intervalHours = prefs.getInt(KEY_INTERVAL, 6),
        userFolder = prefs.getString(KEY_USER_FOLDER, SmbSettings.DEFAULT_USER_FOLDER)
            ?: SmbSettings.DEFAULT_USER_FOLDER
    )

    private fun createPreferences(context: Context): SharedPreferences {
        return try {
            buildEncrypted(context)
        } catch (_: Throwable) {
            // Keystore bozulmuş olabilir (cihaz yedeğinden geri yükleme vb.).
            // Şifreli dosyayı temizleyip bir kez daha dene.
            context.deleteSharedPreferences(FILE_NAME)
            try {
                buildEncrypted(context)
            } catch (_: Throwable) {
                // Son çare: uygulamanın açılabilmesi için düz tercihler.
                context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
            }
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
        private const val FILE_NAME = "smb_settings_encrypted"
        private const val FALLBACK_FILE = "smb_settings_fallback"
        private const val DEFAULT_HOST = ""

        private const val KEY_HOST = "host"
        private const val KEY_SHARE = "share"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_PORT = "port"
        private const val KEY_AUTO_BACKUP = "auto_backup"
        private const val KEY_INTERVAL = "interval_hours"
        private const val KEY_USER_FOLDER = "user_folder"
    }
}
