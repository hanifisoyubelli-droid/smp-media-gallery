# jcifs-ng yansıma ile bazı sınıfları yükler; sürüm adlarını koruyoruz.
-keep class jcifs.** { *; }
-dontwarn jcifs.**

# BouncyCastle (jcifs-ng bağımlılığı) uyarılarını sustur.
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**

# Room ve Hilt üretilen sınıflar zaten kurallarını taşır.
-keep class * extends androidx.room.RoomDatabase
