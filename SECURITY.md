# Güvenlik / Security

Kısa ve net: bu uygulamada bulut biz değiliz. Uygulamanın konuştuğu tek sunucu,
Ayarlar'a SENİN yazdığın sunucudur.

## Bildirmek istediğin güvenlik açığı

E-posta: bu depodaki geliştirici profilindeki e-posta adresi (GitHub → profil →
"Email"). Açık issue AÇMA; önce özel yaz. 90 gün içinde yanıt gelmezse serbestsin.

## Uygulamanın güvenlik sınırları

| Konu | Durum |
|---|---|
| Sunucu şifreleri | `EncryptedSharedPreferences` + Android Keystore (AES-256-GCM). Diske düz metin yazılmaz. |
| Android yedeği | `allowBackup="false"` — kimlik bilgileri Google yedeğine gitmez. |
| Nextcloud kimliği | **Uygulama şifresi** kullan; hesap şifreni yazma. Tek tuşla iptal edilebilir. |
| TLS varsayılanı | Sıkı: sistem kökleri + ana makine adı doğrulaması. Süresi dolmuş/yanlış adlı sertifika REDDEDİLİR. |
| "Kendi imzalı sertifikaya güven" | Varsayılan KAPALI. Açılırsa yalnızca zinciri gerçekten kendi kendini imzalayan sertifika kabul edilir; tarih kontrolü, anahtar gücü kontrolü (RSA ≥ 2048 / EC ≥ 224, MD5/SHA-1 yasak) ve ana makine doğrulaması açık kalır. Kabul edilen sertifikanın SHA-256 parmak izi bir kez günlüğe yazılır. |
| Düz HTTP | Yerel ağda izin verilir (birçok ev kurulumu böyledir). Şifren o ağ içinde şifresiz gider; HTTPS mümkünse HTTPS. |
| Telemetri | Yok. Analitik, reklam, çökme raporlama SDK'sı yok. Günlükler cihazda kalır (`adb logcat`). |
| Sertifika sabitleme | Yok, bilinçli olarak: bir kişinin sertifikasını gömmek diğer herkesi bozardı. |

## Bildirirken işe yarayan bilgiler

- Uygulama sürümü (Ayarlar altındaki sürüm) ve Android sürümü, cihaz
- Sunucu türü (SMB / Nextcloud + sürüm) ve HTTPS mi HTTP mi
- Sorunun yeniden üretimi: hangi ekranda, hangi dosyada
- Varsa `adb logcat | findstr SmpMedia` çıktısı (şifre/oturum çerezi içeren satırları sil)

## Bu uygulamaya karşı bilinen sınırlamalar

- Temel kimlik doğrulaması (Basic Auth) kullanılır; HTTPS'te sorun değildir, düz
  HTTP'de şifre yerel ağda görünür.
- Uygulama kilitli değildir; telefonu ele geçen biri uygulamayı açarsa önbellekteki
  küçük resimleri görebilir. Cihaz kilidi senin ilk savunma hattındır.
- "Kendi imzalı sertifikaya güven" anahtarı aradaki-adam korumasını zayıflatır;
  yalnızca kontrol ettiğin bir ağda ve kendi sunucun için aç.
