package com.smpmedia.data.image

/**
 * Coil 2.7'de `ImageRequest` üzerinde yerleşik bir öncelik alanı yoktur.
 * Görünür alan isteklerini öne çıkarmak için düşük öncelikli (ön yükleme ve
 * eksik tamamlama) istekleri bu etiketle işaretliyoruz. Böylece ileride Coil
 * sürümü önceliği desteklerse tek yerden yönetilebilir.
 */
object LowPriorityTag
