package com.powersound.player.data

import java.text.Normalizer

/**
 * The form of a text that Search compares: lower case, no accents, no Arabic harakat or tatweel, one space
 * between words, and one form of the letters people write in different ways:
 * أ إ آ ٱ → ا, ؤ → و, ئ ى ی → ي, ة → ه, ک → ك.
 * So "اليسا" finds "إليسا", "حياه" finds "حياة", and "elissa" finds "Élissa".
 */
fun searchForm(text: String): String {
    if (text.isEmpty()) return text
    // NFD splits أ into ا + hamza mark, é into e + accent mark …; the marks are then dropped
    val d = Normalizer.normalize(text, Normalizer.Form.NFD)
    val b = StringBuilder(d.length)
    var space = false
    for (ch in d) {
        val c = when (ch) {
            'ـ' -> continue                 // tatweel
            'ى', 'ی' -> 'ي'
            'ة' -> 'ه'
            'ٱ' -> 'ا'
            'ک' -> 'ك'
            else -> ch
        }
        if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
        if (c.isWhitespace()) {
            if (!space && b.isNotEmpty()) b.append(' ')
            space = true
            continue
        }
        space = false
        b.append(c.lowercaseChar())
    }
    return b.toString().trimEnd()
}
