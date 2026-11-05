package com.powersound.player.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/** Crossfeed strengths: name, cutoff (Hz), feed (dB — less dB = more of the other ear). Bauer stereophonic-to-binaural. */
val CROSSFEED_LEVELS = listOf(
    Triple("Light", 650.0, 9.5),     // Jan Meier
    Triple("Medium", 700.0, 6.0),    // Chu Moy
    Triple("Strong", 700.0, 4.5)     // bs2b default
)

/**
 * Headphone crossfeed (Bauer bs2b): each ear also hears the other channel, low-passed and a little later — like
 * speakers in a room, where both ears hear both speakers. Hard-panned sounds stop "sitting inside one ear", the singer
 * locks into the centre and long listening is less tiring. Normalised so a centred voice keeps its level at 3 kHz.
 */
class Crossfeed(private val rate: Int) {
    private var a0Lo = 0.0
    private var b1Lo = 0.0
    private var a0Hi = 0.0
    private var a1Hi = 0.0
    private var b1Hi = 0.0
    private var norm = 1.0
    private var level = -1

    private var loL = 0.0
    private var loR = 0.0
    private var hiL = 0.0
    private var hiR = 0.0
    private var inL = 0.0
    private var inR = 0.0

    var outL = 0.0
        private set
    var outR = 0.0
        private set

    fun configure(lvl: Int) {
        val l = lvl.coerceIn(0, CROSSFEED_LEVELS.size - 1)
        if (l == level) return
        level = l
        val (fcLo, feed) = CROSSFEED_LEVELS[l].let { it.second to it.third }
        val gbLo = feed * -5.0 / 6.0 - 3.0
        val gbHi = feed / 6.0 - 3.0
        val gLo = 10.0.pow(gbLo / 20.0)
        val gHi = 1.0 - 10.0.pow(gbHi / 20.0)
        val fcHi = fcLo * 2.0.pow((gbLo - 20.0 * log10(gHi)) / 12.0)
        var x = exp(-2.0 * PI * fcLo / rate)
        a0Lo = gLo * (1.0 - x)
        b1Lo = x
        x = exp(-2.0 * PI * fcHi / rate)
        a0Hi = 1.0 - gHi * (1.0 - x)
        a1Hi = -x
        b1Hi = x
        // A centred sound (the singer) hears direct + crossed: keep its level exactly at 0 dB around 3 kHz, where the
        // voice's presence is; the bass of centred sounds then gets only a slight, natural warmth.
        val w = 2.0 * PI * 3000.0 / rate
        val zr = kotlin.math.cos(w)
        val zi = -kotlin.math.sin(w)      // z^-1
        // lo: a0Lo / (1 - b1Lo z^-1);  hi: (a0Hi + a1Hi z^-1) / (1 - b1Hi z^-1)
        val dLoR = 1.0 - b1Lo * zr
        val dLoI = -b1Lo * zi
        val dHiR = 1.0 - b1Hi * zr
        val dHiI = -b1Hi * zi
        val nHiR = a0Hi + a1Hi * zr
        val nHiI = a1Hi * zi
        val loM = dLoR * dLoR + dLoI * dLoI
        val hiM = dHiR * dHiR + dHiI * dHiI
        val loR = a0Lo * dLoR / loM
        val loI = -a0Lo * dLoI / loM
        val hiR = (nHiR * dHiR + nHiI * dHiI) / hiM
        val hiI = (nHiI * dHiR - nHiR * dHiI) / hiM
        norm = 1.0 / kotlin.math.hypot(loR + hiR, loI + hiI)
    }

    fun reset() {
        loL = 0.0; loR = 0.0; hiL = 0.0; hiR = 0.0; inL = 0.0; inR = 0.0
    }

    fun process(l: Double, r: Double) {
        loL = a0Lo * l + b1Lo * loL
        loR = a0Lo * r + b1Lo * loR
        hiL = a0Hi * l + a1Hi * inL + b1Hi * hiL
        hiR = a0Hi * r + a1Hi * inR + b1Hi * hiR
        inL = l
        inR = r
        outL = (hiL + loR) * norm
        outR = (hiR + loL) * norm
    }
}
