package com.powersound.player.audio.flac

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor

/** Media3's file readers, with FLAC read by [SafeFlacExtractor] (no stop where the audio data looks like a header). */
@OptIn(UnstableApi::class)
class SafeExtractors : ExtractorsFactory {
    private val base = DefaultExtractorsFactory()

    private fun swap(list: Array<Extractor>): Array<Extractor> =
        list.map { if (it is FlacExtractor) SafeFlacExtractor() else it }.toTypedArray()

    override fun createExtractors(): Array<Extractor> = swap(base.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        swap(base.createExtractors(uri, responseHeaders))
}
