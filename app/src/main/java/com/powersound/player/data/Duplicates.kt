package com.powersound.player.data

/**
 * Settings → Library → Find Duplicates: the same song more than once (same title and artist, nearly the same
 * length), e.g. an MP3 and a FLAC. The best copy of each comes first: lossless before lossy, then the bigger file.
 */
object Duplicates {

    private val notLetters = Regex("[^\\p{L}\\p{N}]+")

    private fun key(s: Song) = searchForm(s.title).replace(notLetters, "") + "|" + searchForm(s.artist).replace(notLetters, "")

    fun quality(s: Song): Int = when (s.formatLabel) {
        "FLAC", "WAV", "ALAC", "AIFF", "APE", "DSD" -> 3
        "OPUS", "AAC", "M4A", "OGG" -> 2
        else -> 1
    }

    fun find(songs: List<Song>): List<List<Song>> {
        val out = ArrayList<List<Song>>()
        for ((k, list) in songs.groupBy { key(it) }) {
            if (list.size < 2 || k.startsWith("|")) continue
            // songs of the same name but clearly different length (live, remix …) are not the same
            val left = list.sortedBy { it.durationMs }.toMutableList()
            while (left.isNotEmpty()) {
                val first = left.removeAt(0)
                val same = mutableListOf(first)
                val it = left.iterator()
                while (it.hasNext()) {
                    val s = it.next()
                    if (kotlin.math.abs(s.durationMs - first.durationMs) <= 3000) { same.add(s); it.remove() }
                }
                if (same.size > 1) out.add(same.sortedWith(compareByDescending<Song> { quality(it) }.thenByDescending { it.size }))
            }
        }
        return out.sortedBy { MusicRepository.sortKey(it.first().title) }
    }
}
