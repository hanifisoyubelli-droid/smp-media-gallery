package com.smpmedia

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.smpmedia.ui.SmpMediaNavHost
import com.smpmedia.ui.theme.SmpMediaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enableMaxRefreshRate()
        setContent {
            SmpMediaTheme {
                SmpMediaNavHost()
            }
        }
    }

    /**
     * Katlanabilir cihazda (Pixel 10 Pro Fold) kat değiştirince Activity
     * yeniden oluşturulmaz (AndroidManifest'te `configChanges` bildirildi);
     * bunun yerine bu geri çağrılır. PENCERE EKRAN DEĞİŞTİRİR: kapak
     * ekranındaki 120 Hz mod kimliği iç ekranda geçersizdir. Yenileme hızı
     * isteği yeni ekrana göre tazelenmezse uygulama iç ekranda 60 Hz'e
     * düşebilir.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableMaxRefreshRate()
    }

    /**
     * Ekranı mümkün olan en yüksek yenileme hızına (ör. 120 Hz) çıkarır.
     *
     * Android varsayılan olarak "uyarlanabilir" (adaptive) yenileme kullanır;
     * statik bir ızgarada sistem çoğu zaman 60 Hz'de kalır. Fotoğraf galerisi
     * gibi sürekli kayan arayüzlerde 120 Hz'ye sabitlemek kaydırmayı belirgin
     * biçimde akıcılaştırır.
     *
     * İki aşamalı uygulanır:
     * 1. `preferredDisplayModeId` → cihazın en yüksek hızlı display modu seçilir.
     * 2. API 30+ ise `Window.setFrameRate(FIXED_SOURCE)` → sistem bu kare hızını
     *    tutmaya çalışır.
     *
     * Donanım desteklemiyorsa sessizce 60 Hz'de kalır; hata vermez.
     */
    private fun enableMaxRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        } ?: return

        val modes = runCatching { display.supportedModes }.getOrNull() ?: return
        if (modes.isEmpty()) return

        // Native çözünürlüğe en yakın, en yüksek yenileme hızlı modu seç.
        val nativeSize = "${display.mode.physicalWidth}x${display.mode.physicalHeight}"
        val best = modes
            .filter { "${it.physicalWidth}x${it.physicalHeight}" == nativeSize }
            .maxByOrNull { it.refreshRate }
            ?: modes.maxByOrNull { it.refreshRate }
            ?: return

        // 1) Tercih edilen display modu (API 23+).
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != best.modeId) {
            attrs.preferredDisplayModeId = best.modeId
            window.attributes = attrs
        }

        // 2) API 30+: sabit kaynak kare hızı iste. `Window.setFrameRate` bazı
        //    SDK derlemelerinde doğrudan görünmediği için yansıma ile çağrılır;
        //    mevcut değilse sessizce atlanır (preferredDisplayModeId zaten yeterli).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val method = window.javaClass.getMethod(
                    "setFrameRate",
                    Float::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                method.invoke(
                    window,
                    best.refreshRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                )
            }
        }
    }
}