package com.powersound.player.video

/**
 * Subtitles → Synchronization: the subtitle's lines are read once (WebVTT, from the file or from inside the video)
 * and shown moved by the chosen time, earlier or later, without loading the video again.
 */
object SubSync {

    class Line(val startMs: Long, val endMs: Long, val text: String)

    private val TIME = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})")
    private val TAG = Regex("<[^>]*>")

    private fun ms(m: MatchResult): Long {
        val g = m.groupValues
        val h = g[1].toLongOrNull() ?: 0
        return ((h * 60 + g[2].toLong()) * 60 + g[3].toLong()) * 1000 + g[4].padEnd(3, '0').take(3).toLong()
    }

    /** A WebVTT text as lines (styling tags taken out), in time order. */
    fun parse(vtt: String): List<Line> {
        val out = ArrayList<Line>()
        for (block in vtt.replace("\r\n", "\n").split(Regex("\n\\s*\n"))) {
            val lines = block.lines()
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val times = TIME.findAll(lines[ti]).toList()
            if (times.size < 2) continue
            val text = lines.drop(ti + 1).joinToString("\n") { TAG.replace(it, "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim() }.trim()
            if (text.isEmpty()) continue
            out += Line(ms(times[0]), ms(times[1]), text)
        }
        out.sortBy { it.startMs }
        return out
    }

    /** The lines to show at [positionMs] of the video, with the subtitle moved by [delayMs] (+ later). */
    fun at(lines: List<Line>, positionMs: Long, delayMs: Int): List<Line> {
        val t = positionMs - delayMs
        // the lines are in start order: look back from the last one that has started
        var hi = lines.size - 1
        var lo = 0
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= t) {
                last = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (last < 0) return emptyList()
        val out = ArrayList<Line>(2)
        var i = last
        // long lines can overlap the next ones: look back a little
        while (i >= 0 && last - i < 12) {
            val l = lines[i]
            if (l.startMs <= t && t < l.endMs) out.add(0, l)
            i--
        }
        return out
    }

    /** A WebVTT text with every time moved by [delayMs] (for the TV). Lines moved before 0:00 are dropped. */
    fun shift(vtt: String, delayMs: Int): String {
        if (delayMs == 0) return vtt
        val out = StringBuilder("WEBVTT\n\n")
        for (l in parse(vtt)) {
            val s = l.startMs + delayMs
            val e = l.endMs + delayMs
            if (e <= 0) continue
            val text = l.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            out.append(stamp(s.coerceAtLeast(0))).append(" --> ").append(stamp(e)).append('\n').append(text).append("\n\n")
        }
        return out.toString()
    }

    private fun stamp(ms: Long) = "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}
