package com.smpmedia.ui.components

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import com.smpmedia.util.Mp4Rotation
import java.io.File

/**
 * Video karesini yüzeye ORANINI KORUYARAK yerleştirmenin tek yolu.
 *
 * Kök neden (kullanıcı şikâyeti — "videolar normalden uzun oynatılıyor"):
 * MediaPlayer + SurfaceView kombinasyonunda kodek karesi yüzeyin TAMAMINA
 * gerilir; en-boy oranı uygulanmaz. Dikey videolar gerik, kareler basık/uzun
 * görünür. Çözüm: TextureView + transform matrisi — kare, görüş alanına
 * sığacak (fit) veya onu dolduracak (crop) biçimde ölçeklenir ve dosyadaki
 * dönüş açısı kadar DÖNDÜRÜLÜR (telefon dikey videosu sensörde yatay
 * saklanır; döndürmezsek yan yatar).
 *
 * Dönüş açısının TEK kaynağı [Mp4Rotation]'dır: ızgara kare üretimi,
 * görüntüleyici ve canlı önizleme aynı açıyı okur — aksi hâlde bir yüzey
 * düz, diğeri yan yatar ("ızgarada yan, açınca düz" hatası).
 */
object VideoFit {

    /**
     * Dönen kareyi (90/270) de doğru hesaplayan matris. TextureView kareyi
     * önce görüş alanına GEREREK doldurur; bu matris o gerilmeyi tersine
     * çevirip oranı geri koyar, sonra ortadan döndürür.
     *
     * [crop] = true → görüş alanını DOLDUR (Google Photos ızgara önizlemesi
     * gibi; kenarlardan kırpar). false → SIĞDIR (görüntüleyici; siyah bant).
     */
    fun fitMatrix(
        viewW: Int,
        viewH: Int,
        bufferW: Int,
        bufferH: Int,
        rotationDeg: Int,
        crop: Boolean
    ): Matrix {
        val matrix = Matrix()
        if (viewW <= 0 || viewH <= 0 || bufferW <= 0 || bufferH <= 0) return matrix

        val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        val bufferRect = RectF(0f, 0f, bufferW.toFloat(), bufferH.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())

        // Görüş alanı ölçeğini iptal et: kare kendi oranına döner.
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)

        val rotated = rotationDeg == 90 || rotationDeg == 270
        val s = if (rotated) {
            // Döndükten sonra içerik bufferH x bufferW olur.
            val sx = viewW.toFloat() / bufferH
            val sy = viewH.toFloat() / bufferW
            if (crop) maxOf(sx, sy) else minOf(sx, sy)
        } else {
            val sx = viewW.toFloat() / bufferW
            val sy = viewH.toFloat() / bufferH
            if (crop) maxOf(sx, sy) else minOf(sx, sy)
        }
        matrix.postScale(s, s, cx, cy)
        if (rotationDeg != 0) matrix.postRotate(rotationDeg.toFloat(), cx, cy)
        return matrix
    }

    /** Dosyadaki dönüş açısı (0/90/180/270) — MMR, olmazsa tkhd matrisi. */
    fun readRotation(file: File): Int = Mp4Rotation.fromFile(file)

    /** content:// adresindeki dönüş açısı. */
    fun readRotation(context: Context, uri: Uri): Int = Mp4Rotation.fromUri(context, uri)
}
