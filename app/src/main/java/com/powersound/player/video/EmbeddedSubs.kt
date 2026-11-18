package com.powersound.player.video

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayOutputStream

/**
 * Subtitles inside an MKV / WebM file (SRT, ASS / SSA and WebVTT text tracks) as WebVTT, for the Chromecast: it only
 * shows subtitle files, so the phone reads the video once and writes each subtitle track out.
 * Picture subtitles (PGS / VobSub, often on Blu-ray copies) are pictures, not text, and are left out.
 */
@OptIn(UnstableApi::class)
object EmbeddedSubs {

    /** One subtitle track of the file, as WebVTT. */
    class Track(val label: String, val language: String?, val vtt: String, val cues: Int)

    /** True for the files that can hold such tracks (by MIME type, or by the address's ending). */
    fun canHold(uri: Uri, mime: String?): Boolean {
        val m = mime.orEmpty().lowercase()
        val p = (uri.lastPathSegment ?: uri.toString()).lowercase().substringBefore('?')
        return m.contains("matroska") || m.contains("webm") || p.endsWith(".mkv") || p.endsWith(".webm") || p.endsWith(".mka")
    }

    /**
     * Reads the whole file once through [factory] (the phone's files, shared folders…) and returns its text subtitle
     * tracks (empty when it has none). [progress] gets 0..1 when the file's size is known; [cancelled] stops early.
     */
    fun extract(uri: Uri, factory: DataSource.Factory, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): List<Track> {
        val outs = LinkedHashMap<Int, Out>()
        val extractor = MatroskaExtractor(
            SubtitleParser.Factory.UNSUPPORTED,
            MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA or MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES
        )
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = outs.getOrPut(id) { Out() }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        val source = factory.createDataSource()
        var total = C.LENGTH_UNSET.toLong()
        fun open(at: Long): DefaultExtractorInput {
            val len = source.open(DataSpec.Builder().setUri(uri).setPosition(at).build())
            if (len != C.LENGTH_UNSET.toLong()) total = at + len
            return DefaultExtractorInput(source, at, if (len == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else at + len)
        }
        try {
            var input = open(0)
            val holder = PositionHolder()
            var lastReport = 0L
            loop@ while (!cancelled()) {
                when (extractor.read(input, holder)) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_SEEK -> {
                        source.close()
                        input = open(holder.position)
                    }
                    else -> break@loop
                }
                if (total > 0 && input.position - lastReport > 8L shl 20) {
                    lastReport = input.position
                    progress((input.position.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        } finally {
            runCatching { source.close() }
            runCatching { extractor.release() }
        }
        var n = 0
        return outs.values.filter { it.isText && it.count > 0 }.map { o ->
            n++
            val f = o.format
            Track(
                label = f?.label ?: f?.language?.let { java.util.Locale(it).displayLanguage.ifBlank { null } } ?: "Subtitles $n",
                language = f?.language,
                vtt = o.vtt.toString(),
                cues = o.count
            )
        }
    }

    /** One track of the file: subtitle samples become WebVTT cues; everything else (video, sound) is skipped. */
    private class Out : TrackOutput {
        var format: Format? = null
        val isText: Boolean
            get() = format?.sampleMimeType.let { it == MimeTypes.APPLICATION_SUBRIP || it == MimeTypes.TEXT_SSA || it == MimeTypes.TEXT_VTT }
        val vtt = StringBuilder("WEBVTT\n\n")
        var count = 0
        private val pending = ByteArrayOutputStream()
        private val skip = ByteArray(64 * 1024)

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val n = input.read(skip, 0, minOf(length, skip.size))
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw java.io.EOFException()
            }
            if (isText) pending.write(skip, 0, n)
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (isText) {
                val b = ByteArray(length)
                data.readBytes(b, 0, length)
                pending.write(b, 0, length)
            } else data.skipBytes(length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (!isText) return
            val bytes = pending.toByteArray()
            pending.reset()
            val text = String(bytes, Charsets.UTF_8)
            cue(timeUs, text)
        }

        /** "…--> 00:00:02,500\ntext" (SRT / WebVTT) or "Dialogue: 0:00:00:00,0:00:02:50,ReadOrder,Layer,…,Text" (ASS). */
        private fun cue(startUs: Long, sample: String) {
            val durationUs: Long
            val body: String
            if (format?.sampleMimeType == MimeTypes.TEXT_SSA) {
                val head = Regex("^Dialogue:\\s*[^,]*,(\\d+):(\\d{2}):(\\d{2}):(\\d{2}),").find(sample) ?: return
                val (h, m, s, cs) = head.destructured
                durationUs = ((h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + cs.toLong() * 10) * 1000
                // after the time: ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                val fields = sample.substring(head.range.last + 1).split(",", limit = 9)
                body = (fields.getOrNull(8) ?: fields.last())
                    .replace(Regex("\\{[^}]*\\}"), "")
                    .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
            } else {
                val lines = sample.replace("\r\n", "\n").split('\n')
                val ti = lines.indexOfFirst { it.contains("-->") }
                if (ti < 0) return
                val end = Regex("(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})").findAll(lines[ti]).lastOrNull() ?: return
                val (h, m, s, ms) = end.destructured
                durationUs = ((h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + ms.padEnd(3, '0').toLong()) * 1000
                body = lines.drop(ti + 1).joinToString("\n")
            }
            val clean = body.lines().joinToString("\n") { it.trim() }.trim()
            if (clean.isEmpty() || durationUs <= 0) return
            vtt.append(stamp(startUs)).append(" --> ").append(stamp(startUs + durationUs)).append('\n').append(clean).append("\n\n")
            count++
        }

        private fun stamp(us: Long): String {
            val ms = (us / 1000).coerceAtLeast(0)
            return "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        }
    }
}
