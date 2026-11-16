package com.powersound.player.data

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's language (Settings → Look and Feel → Language: Auto / English / العربية).
 *
 * The English text in the code stays the source; [tr] gives its Arabic version from assets/i18n/ar.tsv. Texts with
 * numbers or names in them ("Scanning… 12 of 340") are found by patterns: in the file {n} is a number, {t} any text
 * and %d / %.1f / %s are as in String.format. Every Text on screen goes through [tr] (ui/Text.kt).
 */
object Lang {
    /** Arabic is on: texts are translated and the app is laid out right to left. */
    @Volatile
    var arabic = false
        private set

    private val exact = HashMap<String, String>()
    private val patterns = ArrayList<Pattern>()
    private val cache = ConcurrentHashMap<String, String>()
    private var loaded = false

    private class Pattern(val regex: Regex, val hint: String, val target: String, val slots: Int, val percent: Boolean)

    /** 0 Auto (the phone's language), 1 English, 2 Arabic. */
    fun init(context: Context, setting: Int = SettingsStore.value.language) {
        val phone = context.resources.configuration.locales.get(0)?.language == "ar"
        arabic = setting == 2 || (setting == 0 && phone)
        cache.clear()
        if (arabic && !loaded) load(context)
    }

    private fun load(context: Context) {
        loaded = true
        runCatching {
            context.assets.open("i18n/ar.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val en = unescape(line.substring(0, tab))
                    val ar = unescape(line.substring(tab + 1))
                    if (SLOT.containsMatchIn(en)) pattern(en, ar)?.let { patterns += it } else exact[en] = ar
                }
            }
        }
    }

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> b.append('\n')
                    't' -> b.append('\t')
                    '\\' -> b.append('\\')
                    else -> b.append(c).append(s[i + 1])
                }
                i += 2
            } else {
                b.append(c)
                i++
            }
        }
        return b.toString()
    }

    /** {n} a number, {t} any text, %d / %.1f / %+.1f / %s like String.format ("%%" is a plain %). */
    private val SLOT = Regex("""\{[nt]\}|%[-#+0,(]*\d*(?:\.\d+)?[sdf]""")

    private fun pattern(en: String, ar: String): Pattern? = runCatching {
        val re = StringBuilder("^")
        var last = 0
        var slots = 0
        var hint = ""
        for (m in SLOT.findAll(en)) {
            val lit = en.substring(last, m.range.first).replace("%%", "%")
            if (lit.length > hint.length) hint = lit
            re.append(Regex.escape(lit))
            re.append(
                when {
                    m.value == "{n}" -> """([-+−]?\d[\d.,:/]*)"""
                    m.value == "{t}" -> "(.+?)"
                    m.value.endsWith("s") -> "(.+?)"
                    else -> """([-+−]?\d[\d.,]*)"""
                }
            )
            slots++
            last = m.range.last + 1
        }
        val tail = en.substring(last).replace("%%", "%")
        if (tail.length > hint.length) hint = tail
        re.append(Regex.escape(tail)).append("$")
        // a pattern must say something besides its slots ("{t}" alone would match every text)
        if (hint.isBlank()) return null
        Pattern(Regex(re.toString(), RegexOption.DOT_MATCHES_ALL), hint, ar, slots, percent = en.contains('%'))
    }.getOrNull()

    /** The Arabic of [text] (or [text] itself when Arabic is off or nothing matches). */
    fun tr(text: String): String {
        if (!arabic || text.isEmpty()) return text
        cache[text]?.let { return it }
        val out = find(text, deep = true) ?: text
        if (cache.size > 6000) cache.clear()
        cache[text] = out
        return out
    }

    private fun find(text: String, deep: Boolean): String? {
        exact[text]?.let { return it }
        // the same text with spaces or a line break around it
        val core = text.trim()
        if (core.length != text.length && core.isNotEmpty()) {
            exact[core]?.let { t ->
                val start = text.indexOf(core)
                return text.substring(0, start) + t + text.substring(start + core.length)
            }
        }
        if (!deep || core.length > 600) return null
        for (p in patterns) {
            if (!text.contains(p.hint.trim())) continue
            val m = p.regex.matchEntire(text) ?: continue
            val parts = m.groupValues.drop(1)
            // a name in a slot is translated too ("Reverb (Large Hall)"), numbers stay
            var i = 0
            val done = SLOT.replace(p.target) { r ->
                val v = parts.getOrElse(i++) { "" }
                if (r.value == "{t}" || r.value.endsWith("s")) find(v, deep = false) ?: v else v
            }
            // "%%" is how a String.format text writes a plain %
            return if (p.percent) done.replace("%%", "%") else done
        }
        return null
    }
}
