package com.smpmedia.data.image;

import android.content.Context;

import coil.decode.ImageSource;
import coil.decode.ImageSources;
import okio.BufferedSource;

/**
 * Coil 2.7'de ImageSources Kotlin tarafında internal olduğu için
 * JVM üzerinden erişen küçük bir köprü.
 */
public final class SmbImageSourceFactory {

    private SmbImageSourceFactory() {
    }

    public static ImageSource create(BufferedSource source, Context context) {
        return ImageSources.create(source, context);
    }
}
