import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Yayın imzası: kökteki keystore.properties dosyasından okunur.
// Bu dosya DEPODA YOKTUR (.gitignore) — her katkıda bulunan kendi anahtarıyla
// imzalar. Dosya yoksa release APK İMZASIZ üretilir ve CI bunu normal karşılar.
// Anahtar kaybedilirse eski kurulumlar güncelleme kabul etmez; yedeğini al.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.smpmedia"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smpmedia"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    // AndroidX temel
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Jetpack Compose (BOM sürümleri yönetir)
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigasyon
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Hilt (DI)
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-android-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room (yerel veritabanı)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager (periyodik yedekleme)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Şifreli tercihler (SMB kullanıcı adı / şifre)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Görsel yükleme
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Videoların ilk karesini küçük resim olarak çözer (MediaMetadataRetriever).
    implementation("io.coil-kt:coil-video:2.7.0")

    // SMB istemcisi
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    // jcifs-ng SLF4J kullanır; Android'de gürültüsüz bir bağlayıcı yeterlidir.
    implementation("org.slf4j:slf4j-nop:1.7.36")

    // Nextcloud WebDAV istemcisi doğrudan OkHttp kullanıyor (Coil'in getirdiği
    // sürüme GÜVENMİYORUZ: TLS katmanını biz kuruyoruz, sürüm sürprizi olmasın).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
