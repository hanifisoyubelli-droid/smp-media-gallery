package com.smpmedia.data.prefs

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Fotoğraflar" zaman tünelinde hangi klasörlerin görüneceğini saklar.
 *
 * Ters mantık kullanılır: varsayılan olarak her şey GÖRÜNÜR; gizlenen
 * klasörlerin anahtarları tutulur. Böylece:
 * - İlk kurulumda filtre yoktur (tüm cihaz + SMP klasörleri görünür).
 * - Yeni bir klasör eklendiğinde kendiliğinden görünür (kullanıcı onu
 *   gizlemediği sürece) — Google Photos davranışı.
 *
 * Anahtar biçimi: `local:<bucketId>` (cihaz) veya `smb:<paylaşıma göreli yol>`.
 *
 * SMP tarafında gizleme ÖZYİNELEMELİDİR: bir üst klasör kapatıldığında içindeki
 * tüm alt klasörler de kapanır ([isCloudPathHidden]). Kullanıcının tek tek
 * "2020 yılına ait fotoğraflar", "2021 yılına ait fotoğraflar" … ile
 * uğraşması gerekmez; üst klasörü seçmesi yeterlidir.
 */
@Singleton
class GalleryFilterStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _hidden = MutableStateFlow(readHidden())
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    /** Bir klasörü gizler/gösterir. */
    fun toggle(key: String) {
        val next = _hidden.value.toMutableSet()
        if (!next.add(key)) next.remove(key)
        prefs.edit().putStringSet(KEY_HIDDEN, next).apply()
        _hidden.value = next
    }

    /** Tüm klasörleri yeniden görünür yap. */
    fun showAll() {
        prefs.edit().remove(KEY_HIDDEN).apply()
        _hidden.value = emptySet()
    }

    fun isVisible(key: String): Boolean = key !in _hidden.value

    /** SMP yolu gizli mi? (üst klasörler de hesaba katılır) */
    fun isCloudPathHidden(path: String): Boolean = isCloudPathHidden(path, _hidden.value)

    private fun readHidden(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN, emptySet())?.toSet() ?: emptySet()

    companion object {
        private const val FILE_NAME = "gallery_filter"
        // v2: ilk sürümde klasör kapama anahtarları yanlışlıkla doldurulmuştu;
        // yeni anahtar herkes için "hepsi görünür" ile başlar.
        private const val KEY_HIDDEN = "hidden_folders_v2"

        private const val LOCAL_PREFIX = "local:"
        private const val CLOUD_PREFIX = "smb:"

        fun localKey(bucketId: String): String = LOCAL_PREFIX + bucketId

        /** SMP klasörü için anahtar; yol paylaşım köküne görelidir. */
        fun cloudKey(folder: String): String = CLOUD_PREFIX + folder.trim('/')

        /** Anahtar bir SMP anahtarı mı? */
        fun isCloudKey(key: String): Boolean = key.startsWith(CLOUD_PREFIX)

        /**
         * [path] altındaki bir görsel gizli mi?
         *
         * Gizli anahtar, görselin klasörünün KENDİSİ veya ÜST klasörlerinden
         * biri ise sonuç true döner — yani üst klasörü kapatmak içindeki her
         * şeyi kapatır (özyinelemeli filtreleme).
         */
        fun isCloudPathHidden(path: String, hidden: Set<String>): Boolean {
            if (hidden.isEmpty()) return false
            val folder = path.trim('/')
            return hidden.any { key ->
                if (!key.startsWith(CLOUD_PREFIX)) return@any false
                val masked = key.removePrefix(CLOUD_PREFIX).trim('/')
                masked.isNotEmpty() && (folder == masked || folder.startsWith("$masked/"))
            }
        }

        /**
         * Bir SMP görselinin dişlide gruplanacağı ÜST klasör.
         *
         * [root] = taramanın yapıldığı kullanıcı klasörü (ör. `usb1_1/Ayse`).
         * Dönüş, kök dahil tam Klasör yoludur; böylece anahtar hem listede
         * gösterilebilir hem de [isCloudPathHidden] ile eşleşebilir.
         *
         * Örnek (root = `usb1_1/Ayse`):
         * - `usb1_1/Ayse/Google Fotoğraf/2024 yılına ait fotoğraflar`
         *     → `usb1_1/Ayse/Google Fotoğraf`
         * - `usb1_1/Ayse` (dosya doğrudan kökte) → `usb1_1/Ayse`
         */
        fun topLevelFolder(parentPath: String, root: String): String {
            val folder = parentPath.trim('/')
            val base = root.trim('/')
            val relative = when {
                base.isEmpty() -> folder
                folder == base -> ""
                folder.startsWith("$base/") -> folder.removePrefix("$base/")
                else -> folder
            }
            // missingDelimiterValue BILINCLI olarak `relative`. Klasör kökün
            // hemen altındaysa (ör. `usb1_1/Ayse/Camera` → `Camera`) ayırıcı
            // bulunamaz; buraya "" verilsaydi bos metin döner ve tüm tek
            // seviyeli klasörler köke yığılırdı — dişlide "Camera" yerine
            // üst klasörün adı ("Ayse") çıkıyordu ve yeni eklenen klasörler
            // hiç görünmüyordu.
            val first = relative.substringBefore('/', relative)
            return when {
                first.isEmpty() -> base
                base.isEmpty() -> first
                else -> "$base/$first"
            }
        }
    }
}
